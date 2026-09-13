package org.luismtz.mvdecrypter

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile

/**
 * MV Decrypter para Android.
 *
 * Descifra archivos de RPG Maker MV/MZ (.rpgmvp/.png_, .rpgmvo/.ogg_, .rpgmvm/.m4a_),
 * inspirado en la herramienta web de Petschko (petschko.org/tools/mv_decrypter).
 *
 * Formato: los primeros 16 bytes son una "cabecera falsa" (firma RPGMV) que se descarta.
 * Los siguientes 16 bytes del contenido real están cifrados con XOR contra una clave de 16 bytes.
 * El resto del archivo no está cifrado.
 *
 * Si no se conoce la clave, para PNG se puede derivar: XOR entre los 16 bytes cifrados
 * y la cabecera real conocida de un PNG.
 */
class MainActivity : AppCompatActivity() {

    private val fakeHeaderLen = 16

    // Cabecera PNG real conocida (firma + inicio de chunk IHDR)
    private val pngRealHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    )

    private var selectedFiles: List<Uri> = emptyList()
    private var outputDirUri: Uri? = null
    private var lastDecryptedPng: ByteArray? = null

    private lateinit var tvFilesCount: TextView
    private lateinit var tvOutputDir: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etKey: EditText
    private lateinit var rgMode: RadioGroup
    private lateinit var rbRestorePng: RadioButton
    private lateinit var ivPreview: ImageView

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selectedFiles = uris
            tvFilesCount.text = if (uris.isEmpty())
                "Ningún archivo seleccionado"
            else
                "${uris.size} archivo(s) seleccionado(s)"
        }

    private val pickOutputLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                outputDirUri = uri
                tvOutputDir.text = uri.path ?: uri.toString()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFilesCount = findViewById(R.id.tvFilesCount)
        tvOutputDir = findViewById(R.id.tvOutputDir)
        tvStatus = findViewById(R.id.tvStatus)
        etKey = findViewById(R.id.etKey)
        rgMode = findViewById(R.id.rgMode)
        rbRestorePng = findViewById(R.id.rbRestorePng)
        ivPreview = findViewById(R.id.ivPreview)

        findViewById<Button>(R.id.btnPickFiles).setOnClickListener {
            pickFilesLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnPickOutput).setOnClickListener {
            pickOutputLauncher.launch(null)
        }

        findViewById<Button>(R.id.btnDecrypt).setOnClickListener {
            decryptSelected()
        }
    }

    private fun log(msg: String) {
        tvStatus.append(msg + "\n")
    }

    private fun decryptSelected() {
        tvStatus.text = ""
        ivPreview.visibility = ImageView.GONE
        lastDecryptedPng = null

        val outDir = outputDirUri
        if (outDir == null) {
            Toast.makeText(this, "Elige primero una carpeta de salida", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Selecciona al menos un archivo", Toast.LENGTH_SHORT).show()
            return
        }

        val restoreMode = rbRestorePng.isChecked
        val keyBytes: ByteArray? = if (!restoreMode) {
            val hex = etKey.text.toString().trim()
            if (hex.length < 32) {
                Toast.makeText(this, "Clave inválida: se necesitan 32 caracteres hex (16 bytes)", Toast.LENGTH_LONG).show()
                return
            }
            try {
                hexToBytes(hex)
            } catch (e: Exception) {
                Toast.makeText(this, "Clave hex mal formada", Toast.LENGTH_SHORT).show()
                return
            }
        } else null

        val destDir = DocumentFile.fromTreeUri(this, outDir)
        if (destDir == null || !destDir.isDirectory) {
            log("No se pudo abrir la carpeta de salida")
            return
        }

        var ok = 0
        var fail = 0

        for (uri in selectedFiles) {
            val name = queryFileName(uri) ?: "archivo_desconocido"
            try {
                val input = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("no se pudo leer")

                if (input.size < fakeHeaderLen * 2) {
                    throw IllegalArgumentException("archivo demasiado pequeño / no cifrado")
                }

                val (outBytes, outExt) = if (restoreMode) {
                    val derivedKey = deriveKeyFromPng(input)
                    Pair(decryptBody(input, derivedKey), "png")
                } else {
                    Pair(decryptBody(input, keyBytes!!), outputExtension(name))
                }

                val baseName = name.substringBeforeLast('.', name)
                val outFile = destDir.createFile(mimeFor(outExt), "$baseName.$outExt")
                    ?: throw IllegalStateException("no se pudo crear el archivo de salida")

                contentResolver.openOutputStream(outFile.uri)?.use { os ->
                    os.write(outBytes)
                } ?: throw IllegalStateException("no se pudo escribir")

                if (outExt == "png") {
                    lastDecryptedPng = outBytes
                }

                log("✔ $name → $baseName.$outExt")
                ok++
            } catch (e: Exception) {
                log("✘ $name — ${e.message}")
                fail++
            }
        }

        log("\nListo: $ok correcto(s), $fail con error.")

        lastDecryptedPng?.let { bytes ->
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                ivPreview.setImageBitmap(bmp)
                ivPreview.visibility = ImageView.VISIBLE
            }
        }
    }

    /** Quita la cabecera falsa (16 bytes) y descifra por XOR los siguientes 16 bytes del contenido. */
    private fun decryptBody(input: ByteArray, key: ByteArray): ByteArray {
        val body = input.copyOfRange(fakeHeaderLen, input.size)
        val out = body.copyOf()
        val n = minOf(fakeHeaderLen, out.size)
        for (i in 0 until n) {
            out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    /** Deriva la clave de 16 bytes comparando los bytes cifrados con la cabecera PNG real conocida. */
    private fun deriveKeyFromPng(input: ByteArray): ByteArray {
        val encryptedChunk = input.copyOfRange(fakeHeaderLen, fakeHeaderLen * 2)
        return ByteArray(fakeHeaderLen) { i ->
            (encryptedChunk[i].toInt() xor pngRealHeader[i].toInt()).toByte()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("0x", "", ignoreCase = true)
        require(clean.length % 2 == 0) { "longitud impar" }
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    private fun outputExtension(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".rpgmvp") || lower.endsWith(".png_") -> "png"
            lower.endsWith(".rpgmvo") || lower.endsWith(".ogg_") -> "ogg"
            lower.endsWith(".rpgmvm") || lower.endsWith(".m4a_") -> "m4a"
            else -> "bin"
        }
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "png" -> "image/png"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }
}
