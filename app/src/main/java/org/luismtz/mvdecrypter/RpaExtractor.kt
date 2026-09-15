package org.luismtz.mvdecrypter

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Extractor de archivos Ren'Py (.rpa).
 *
 * Formato (RPA-3.0):
 *   Línea de cabecera ASCII: "RPA-3.0 <offset hex 16> <key hex 8>\n"
 *   ... datos de los archivos ...
 *   En `offset`: índice comprimido con zlib, serializado con pickle de Python.
 *   El índice es un dict { "ruta/archivo.ext": [(offset, length[, prefix]), ...] }
 *   offset y length vienen XOR-ofuscados con `key` (solo en 3.0/3.2; en 2.0 no hay key).
 *
 * RPA-2.0 usa la misma idea pero sin key y sin el prefijo de 3 bytes extra en la tupla.
 */
class RpaExtractor(private val resolver: ContentResolver) {

    data class RpaEntry(val path: String, val offset: Long, val length: Long, val prefix: ByteArray)

    class UnsupportedRpaException(msg: String) : Exception(msg)

    /** Lee la cabecera y el índice de un .rpa, sin extraer nada todavía. */
    fun readIndex(uri: Uri): List<RpaEntry> {
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("no se pudo abrir el archivo")
        pfd.use {
            val fis = FileInputStream(it.fileDescriptor)
            val totalSize = it.statSize

            // Leer la línea de cabecera (hasta 128 bytes es más que suficiente)
            val headerBuf = ByteArray(128)
            fis.read(headerBuf)
            val headerLine = String(headerBuf, Charsets.US_ASCII).substringBefore('\n')

            val parts = headerLine.trim().split(" ").filter { p -> p.isNotEmpty() }
            if (parts.isEmpty() || !parts[0].startsWith("RPA-")) {
                throw UnsupportedRpaException("No parece un archivo .rpa válido (cabecera: '$headerLine')")
            }
            val magic = parts[0]
            if (!(magic == "RPA-2.0" || magic == "RPA-3.0" || magic == "RPA-3.2")) {
                throw UnsupportedRpaException("Versión de RPA no soportada: $magic")
            }
            if (parts.size < 2) throw UnsupportedRpaException("Cabecera RPA incompleta")

            val indexOffset = java.lang.Long.parseLong(parts[1], 16)
            var key = 0L
            if (magic != "RPA-2.0") {
                for (i in 2 until parts.size) {
                    key = key xor java.lang.Long.parseLong(parts[i], 16)
                }
            }

            // Leer el bloque comprimido del índice (desde indexOffset hasta el final del archivo)
            val channel = fis.channel
            channel.position(indexOffset)
            val compressedLen = (totalSize - indexOffset).toInt()
            val compressed = ByteArray(compressedLen)
            var readTotal = 0
            while (readTotal < compressedLen) {
                val n = fis.read(compressed, readTotal, compressedLen - readTotal)
                if (n < 0) break
                readTotal += n
            }

            val indexBytes = inflate(compressed, readTotal)
            val parsed = PickleReader(indexBytes).load()

            @Suppress("UNCHECKED_CAST")
            val dict = parsed as? Map<Any?, Any?>
                ?: throw UnsupportedRpaException("El índice del .rpa no tiene el formato esperado")

            val entries = ArrayList<RpaEntry>()
            for ((k, v) in dict) {
                val path = k as? String ?: continue
                val list = v as? List<Any?> ?: continue
                if (list.isEmpty()) continue
                val tuple = list[0] as? List<Any?> ?: continue
                if (tuple.size < 2) continue
                val rawOffset = (tuple[0] as Number).toLong()
                val rawLength = (tuple[1] as Number).toLong()
                val prefix = if (tuple.size >= 3) (tuple[2] as? ByteArray ?: ByteArray(0)) else ByteArray(0)
                val realOffset = rawOffset xor key
                val realLength = rawLength xor key
                entries.add(RpaEntry(path, realOffset, realLength, prefix))
            }
            return entries
        }
    }

    /** Extrae todas las entradas del .rpa hacia destDir, recreando las subcarpetas. Devuelve (ok, fallidos). */
    fun extractAll(
        uri: Uri,
        entries: List<RpaEntry>,
        destDir: DocumentFile,
        onProgress: (index: Int, total: Int, name: String) -> Unit
    ): Pair<Int, Int> {
        var ok = 0
        var fail = 0

        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("no se pudo abrir el archivo")

        pfd.use {
            val raf = RandomAccessFile(it.fileDescriptor)
            val dirCache = HashMap<String, DocumentFile>()
            dirCache[""] = destDir

            for ((idx, entry) in entries.withIndex()) {
                onProgress(idx, entries.size, entry.path)
                try {
                    val segments = entry.path.split("/")
                    val fileName = segments.last()
                    val dirPath = segments.dropLast(1)

                    var currentDir = destDir
                    var accumPath = ""
                    for (seg in dirPath) {
                        accumPath = if (accumPath.isEmpty()) seg else "$accumPath/$seg"
                        val cached = dirCache[accumPath]
                        currentDir = if (cached != null) {
                            cached
                        } else {
                            val existing = currentDir.findFile(seg)
                            val d = if (existing != null && existing.isDirectory) existing
                                     else currentDir.createDirectory(seg)
                                     ?: throw IllegalStateException("no se pudo crear carpeta $seg")
                            dirCache[accumPath] = d
                            d
                        }
                    }

                    val dataLen = (entry.length - entry.prefix.size).toInt()
                    val buf = ByteArray(dataLen)
                    // FileDescriptor compartido: usamos pread vía canal posicionado para no
                    // interferir con la posición usada por otras lecturas.
                    synchronized(raf) {
                        raf.seek(entry.offset)
                        var readTotal = 0
                        while (readTotal < dataLen) {
                            val n = raf.read(buf, readTotal, dataLen - readTotal)
                            if (n < 0) break
                            readTotal += n
                        }
                    }

                    val outFile = currentDir.createFile(mimeForName(fileName), fileName)
                        ?: throw IllegalStateException("no se pudo crear $fileName")

                    resolver.openOutputStream(outFile.uri)?.use { os ->
                        if (entry.prefix.isNotEmpty()) os.write(entry.prefix)
                        os.write(buf)
                    } ?: throw IllegalStateException("no se pudo escribir $fileName")

                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
        }
        return Pair(ok, fail)
    }

    private fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ogg" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            "txt", "rpy" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun inflate(compressed: ByteArray, len: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(compressed, 0, len)
        val out = java.io.ByteArrayOutputStream(len * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }
}
