package com.cornels.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Funciones nativas que el HTML puede llamar como window.Cornels.xxx(). */
class Bridge(
    private val ctx: Context,
    private val web: WebView,
    private val engine: LlmEngine,
    private val store: HtmlStore,
    private val pickModel: () -> Unit
) {
    /** La actividad escucha estos eventos para mostrar avisos nativos. */
    var onEvent: ((String, String) -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor()
    private val gen = Executors.newSingleThreadExecutor()
    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }
    private fun q(s: String) = JSONObject.quote(s)
    private fun modelo(estado: String, msg: String) {
        js("window.onModelo&&onModelo(${q(estado)},${q(msg)})")
        onEvent?.invoke(estado, msg)
    }
    private fun progreso(p: Int) {
        js("window.onDescarga&&onDescarga($p)")
        onEvent?.invoke("progreso", p.toString())
    }

    @JavascriptInterface fun versionNativa(): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    @JavascriptInterface fun versionHtml(): String = store.versionOf(store.html())

    @JavascriptInterface fun estado(): String =
        JSONObject().put("cargado", engine.loadedName != null).put("nombre", engine.loadedName ?: "").toString()

    @JavascriptInterface fun listarModelos(): String =
        JSONArray(engine.list().map { JSONObject().put("nombre", it.name).put("mb", it.length() / 1_000_000) }).toString()

    @JavascriptInterface fun cargarModelo(nombre: String) {
        io.execute {
            if (engine.loadedName == nombre) { modelo("listo", nombre); return@execute }
            modelo("cargando", nombre)
            try { engine.load(nombre); modelo("listo", nombre) }
            catch (e: Throwable) { modelo("error", e.message ?: "Error al cargar") }
        }
    }

    @JavascriptInterface fun borrarModelo(nombre: String) {
        engine.delete(nombre); modelo("borrado", nombre)
    }

    @JavascriptInterface fun importarModelo() { web.post { pickModel() } }

    @JavascriptInterface fun descargarModelo(url: String, nombre: String) {
        io.execute {
            try {
                require(url.startsWith("https://")) { "La URL debe empezar con https://" }
                val safe = File(nombre).name.ifBlank { "modelo.task" }
                val tmp = File(engine.modelsDir(), "$safe.part")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "CornelsAI")
                if (c.responseCode !in 200..299) throw IllegalStateException("El servidor respondió ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(65536); var done = 0L; var last = -1
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; progreso(p) }
                            }
                        }
                    }
                }
                tmp.renameTo(File(engine.modelsDir(), safe))
                modelo("descargado", safe)
            } catch (e: Throwable) { modelo("error", e.message ?: "Error de descarga") }
        }
    }

    fun importModel(uri: Uri) {
        io.execute {
            try {
                var name = "modelo.task"
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = File(it.getString(0)).name
                }
                val tmp = File(engine.modelsDir(), "$name.part")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(File(engine.modelsDir(), name))
                modelo("descargado", name)
            } catch (e: Throwable) { modelo("error", e.message ?: "Error al importar") }
        }
    }

    /** Guarda un archivo (exportaciones del HTML) en la carpeta Descargas. */
    @JavascriptInterface fun guardarArchivo(nombre: String, base64: String, mime: String) {
        io.execute {
            val safe = File(nombre).name.ifBlank { "archivo" }
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, v, null, null)
                } else {
                    val d = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    File(d, safe).writeBytes(bytes)
                }
                web.post { Toast.makeText(ctx, "Guardado en Descargas: $safe", Toast.LENGTH_LONG).show() }
                js("window.onGuardado&&onGuardado(${q(safe)},true)")
            } catch (e: Throwable) {
                web.post { Toast.makeText(ctx, "No se pudo guardar: ${e.message}", Toast.LENGTH_LONG).show() }
                js("window.onGuardado&&onGuardado(${q(safe)},false)")
            }
        }
    }

    @JavascriptInterface fun generar(prompt: String) {
        gen.execute {
            try { engine.generate(prompt) { part, done -> js("window.onToken&&onToken(${q(part)},$done)") } }
            catch (e: Throwable) { js("window.onError&&onError(${q(e.message ?: "Error")})") }
        }
    }
}
