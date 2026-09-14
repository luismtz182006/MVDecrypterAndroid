package org.luismtz.mvdecrypter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText

/**
 * MV Decrypter para Android.
 *
 * Descifra archivos de RPG Maker MV/MZ (.rpgmvp/.png_, .rpgmvo/.ogg_, .rpgmvm/.m4a_).
 *
 * Formato: los primeros 16 bytes son una "cabecera falsa" (firma RPGMV) que se descarta.
 * Los siguientes 16 bytes del contenido real están cifrados con XOR contra una clave de 16 bytes.
 * El resto del archivo no está cifrado.
 *
 * Si no se conoce la clave, para PNG se puede derivar comparando los bytes cifrados
 * contra la cabecera real conocida de cualquier PNG.
 */
class MainActivity : AppCompatActivity() {

    private val fakeHeaderLen = 16

    private val pngRealHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    )

    private val prefsName = "mv_decrypter_prefs"
    private val keyOutputDir = "output_dir_uri"
    private val keyLastKey = "last_key_hex"

    private var selectedFiles: List<Uri> = emptyList()
    private var outputDirUri: Uri? = null
    private var lastOutputFileUri: Uri? = null
    private var lastOutputMime: String = "*/*"

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var tvFilesCount: TextView
    private lateinit var tvOutputDir: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etKey: TextInputEditText
    private lateinit var rgMode: RadioGroup
    private lateinit var rbRestorePng: RadioButton
    private lateinit var ivPreview: ImageView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnDecrypt: Button
    private lateinit var btnShareLast: Button

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
                prefs.edit().putString(keyOutputDir, uri.toString()).apply()
            }
        }

    private val detectKeyLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) detectKeyFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        tvFilesCount = findViewById(R.id.tvFilesCount)
        tvOutputDir = findViewById(R.id.tvOutputDir)
        tvStatus = findViewById(R.id.tvStatus)
        etKey = findViewById(R.id.etKey)
        rgMode = findViewById(R.id.rgMode)
        rbRestorePng = findViewById(R.id.rbRestorePng)
        ivPreview = findViewById(R.id.ivPreview)
        progressBar = findViewById(R.id.progressBar)
        btnDecrypt = findViewById(R.id.btnDecrypt)
        btnShareLast = findViewById(R.id.btnShareLast)

        // Ajustes recordados: carpeta de salida y última clave usada
        prefs.getString(keyLastKey, null)?.let { etKey.setText(it) }
        prefs.getString(keyOutputDir, null)?.let { saved ->
            try {
                val uri = Uri.parse(saved)
                // Verifica que el permiso persistente siga vigente
                val stillGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
                if (stillGranted) {
                    outputDirUri = uri
                    tvOutputDir.text = uri.path ?: uri.toString()
                }
            } catch (_: Exception) { }
        }

        findViewById<Button>(R.id.btnPickFiles).setOnClickListener {
            pickFilesLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnPickOutput).setOnClickListener {
            pickOutputLauncher.launch(null)
        }

        findViewById<Button>(R.id.btnDetectKey).setOnClickListener {
            detectKeyLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnCopyKey).setOnClickListener {
            val key = etKey.text?.toString().orEmpty()
            if (key.isBlank()) {
                Toast.makeText(this, "No hay clave para copiar", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Clave MV Decrypter", key))
                Toast.makeText(this, "Clave copiada", Toast.LENGTH_SHORT).show()
            }
        }

        btnDecrypt.setOnClickListener {
            decryptSelected()
        }

        btnShareLast.setOnClickListener {
            shareLastFile()
        }
    }

    private fun log(msg: String) {
        runOnUiThread { tvStatus.append(msg + "\n") }
    }

    /** Detecta automáticamente la clave hex a partir de una imagen cifrada (PNG conocido). */
    private fun detectKeyFrom(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("no se pudo leer el archivo")
            if (input.size < fakeHeaderLen * 2) {
                throw IllegalArgumentException("archivo demasiado pequeño")
            }
            val derived = deriveKeyFromPng(input)
            val hex = derived.joinToString("") { "%02x".format(it) }
            etKey.setText(hex)
            prefs.edit().putString(keyLastKey, hex).apply()
            rgMode.check(R.id.rbWithKey)
            Toast.makeText(this, "Clave detectada y aplicada", Toast.LENGTH_SHORT).show()
            log("Clave detectada: $hex")
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo detectar la clave: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnDecrypt.isEnabled = enabled
        btnDecrypt.text = if (enabled) "Descifrar" else "Descifrando…"
    }

    private fun decryptSelected() {
        tvStatus.text = ""
        ivPreview.visibility = ImageView.GONE
        btnShareLast.visibility = ImageView.GONE
        lastOutputFileUri = null

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
        val keyHex = etKey.text?.toString()?.trim().orEmpty()
        val keyBytes: ByteArray? = if (!restoreMode) {
            if (keyHex.length < 32) {
                Toast.makeText(this, "Clave inválida: se necesitan 32 caracteres hex (16 bytes)", Toast.LENGTH_LONG).show()
                return
            }
            try {
                hexToBytes(keyHex)
            } catch (e: Exception) {
                Toast.makeText(this, "Clave hex mal formada", Toast.LENGTH_SHORT).show()
                return
            }
        } else null

        if (!restoreMode) {
            prefs.edit().putString(keyLastKey, keyHex).apply()
        }

        val destDir = DocumentFile.fromTreeUri(this, outDir)
        if (destDir == null || !destDir.isDirectory) {
            log("No se pudo abrir la carpeta de salida")
            return
        }

        val filesToProcess = selectedFiles
        progressBar.visibility = LinearProgressIndicator.VISIBLE
        progressBar.max = filesToProcess.size
        progressBar.progress = 0
        setUiEnabled(false)

        // Corre en un hilo en segundo plano para no congelar la interfaz en lotes grandes.
        Thread {
            var ok = 0
            var fail = 0
            var lastPngBytes: ByteArray? = null

            for ((index, uri) in filesToProcess.withIndex()) {
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

                    lastOutputFileUri = outFile.uri
                    lastOutputMime = mimeFor(outExt)
                    if (outExt == "png") lastPngBytes = outBytes

                    log("✔ $name → $baseName.$outExt")
                    ok++
                } catch (e: Exception) {
                    log("✘ $name — ${e.message}")
                    fail++
                }

                runOnUiThread { progressBar.progress = index + 1 }
            }

            val finalOk = ok
            val finalFail = fail
            runOnUiThread {
                log("\nListo: $finalOk correcto(s), $finalFail con error.")
                progressBar.visibility = LinearProgressIndicator.GONE
                setUiEnabled(true)

                lastPngBytes?.let { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        ivPreview.setImageBitmap(bmp)
                        ivPreview.visibility = ImageView.VISIBLE
                    }
                }
                if (lastOutputFileUri != null) {
                    btnShareLast.visibility = Button.VISIBLE
                }
                val msg = if (finalFail == 0) "Listo: $finalOk archivo(s) descifrado(s)" else "$finalOk ok, $finalFail con error"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun shareLastFile() {
        val uri = lastOutputFileUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, lastOutputMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Abrir con"))
        } catch (e: Exception) {
            Toast.makeText(this, "No hay app para abrir este archivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decryptBody(input: ByteArray, key: ByteArray): ByteArray {
        val body = input.copyOfRange(fakeHeaderLen, input.size)
        val out = body.copyOf()
        val n = minOf(fakeHeaderLen, out.size)
        for (i in 0 until n) {
            out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

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
