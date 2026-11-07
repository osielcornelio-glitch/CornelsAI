package com.cornels.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
    private val io = Executors.newSingleThreadExecutor()
    private val gen = Executors.newSingleThreadExecutor()
    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }
    private fun q(s: String) = JSONObject.quote(s)
    private fun modelo(estado: String, msg: String) = js("window.onModelo&&onModelo(${q(estado)},${q(msg)})")

    @JavascriptInterface fun versionNativa(): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    @JavascriptInterface fun versionHtml(): String = store.versionOf(store.html())

    @JavascriptInterface fun estado(): String =
        JSONObject().put("cargado", engine.loadedName != null).put("nombre", engine.loadedName ?: "").toString()

    @JavascriptInterface fun listarModelos(): String =
        JSONArray(engine.list().map { JSONObject().put("nombre", it.name).put("mb", it.length() / 1_000_000) }).toString()

    @JavascriptInterface fun cargarModelo(nombre: String) {
        io.execute {
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
                val total = c.contentLengthLong
                c.inputStream.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(65536); var done = 0L; var last = -1
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; js("window.onDescarga&&onDescarga($p)") }
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

    @JavascriptInterface fun generar(prompt: String) {
        gen.execute {
            try { engine.generate(prompt) { part, done -> js("window.onToken&&onToken(${q(part)},$done)") } }
            catch (e: Throwable) { js("window.onError&&onError(${q(e.message ?: "Error")})") }
        }
    }
}
