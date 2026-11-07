package com.cornels.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val base = "https://cornels.local/"
    private val qwenUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private val qwenName = "Qwen2.5-0.5B-q8.task"

    private lateinit var web: WebView
    private lateinit var store: HtmlStore
    private lateinit var engine: LlmEngine
    private lateinit var bridge: Bridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null

    private val pickHtml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val t = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            if (t != null) applyHtml(t)
        }
    }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { bridge.importModel(it) }
    }
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HtmlStore(this)
        engine = LlmEngine(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0f1115"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Bloquea navegar a otras páginas: solo vive tu HTML.
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            }
            webChromeClient = object : WebChromeClient() {
                // Elegir archivos (<input type="file">) para importar modelos, texturas, proyectos…
                override fun onShowFileChooser(
                    w: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?
                ): Boolean {
                    val intent = params?.createIntent() ?: return false
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = cb
                    return try { fileChooser.launch(intent); true }
                    catch (e: Exception) { filePathCallback = null; false }
                }
                // alert / confirm / prompt del HTML
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    AlertDialog.Builder(this@MainActivity).setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.confirm() }.show()
                    return true
                }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    AlertDialog.Builder(this@MainActivity).setMessage(message)
                        .setPositiveButton("Aceptar") { _, _ -> result?.confirm() }
                        .setNegativeButton("Cancelar") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }.show()
                    return true
                }
                override fun onJsPrompt(
                    view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
                ): Boolean {
                    val et = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
                    AlertDialog.Builder(this@MainActivity).setMessage(message).setView(et)
                        .setPositiveButton("Aceptar") { _, _ -> result?.confirm(et.text.toString()) }
                        .setNegativeButton("Cancelar") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }.show()
                    return true
                }
            }
        }
        bridge = Bridge(this, web, engine, store) { pickModel.launch(arrayOf("*/*")) }
        bridge.onEvent = { e, m -> onModelEvent(e, m) }
        web.addJavascriptInterface(bridge, "Cornels")

        // Botón nativo ⋮ (siempre accesible aunque el HTML falle)
        val menu = TextView(this).apply {
            text = "⋮"; textSize = 22f; setTextColor(Color.WHITE); alpha = 0.55f
            setPadding(28, 8, 28, 8); setOnClickListener { showMenu() }
        }
        val root = FrameLayout(this)
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(menu, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(root)
        load(store.html())

        // Carga automática del modelo; si no hay ninguno, ofrece descargarlo (una sola vez).
        val models = engine.list()
        if (models.isNotEmpty()) bridge.cargarModelo(models[0].name)
        else {
            val prefs = getSharedPreferences("cornels", MODE_PRIVATE)
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                askDownload()
            }
        }
    }

    private fun load(html: String) = web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)

    private fun applyHtml(html: String) {
        if (!(html.contains("<html", true) || html.contains("<body", true) || html.contains("<div", true))) {
            Toast.makeText(this, "Eso no parece HTML", Toast.LENGTH_LONG).show(); return
        }
        store.save(html); load(html)
        Toast.makeText(this, "Versión aplicada: ${store.versionOf(html)}", Toast.LENGTH_SHORT).show()
    }

    private fun showMenu() {
        val items = arrayOf("Modelo de IA…", "Introducir HTML (pegar)", "Importar archivo HTML",
            "Historial de versiones", "Restaurar original", "Recargar")
        AlertDialog.Builder(this).setTitle("CORNELS AI").setItems(items) { _, i ->
            when (i) {
                0 -> modelMenu()
                1 -> pasteDialog()
                2 -> pickHtml.launch(arrayOf("text/html", "text/plain", "*/*"))
                3 -> historyDialog()
                4 -> { store.restoreOriginal(); load(store.html()) }
                5 -> load(store.html())
            }
        }.show()
    }

    // ---------- Modelo de IA ----------
    private fun modelMenu() {
        val estado = engine.loadedName?.let { "cargado: $it" } ?: "sin modelo cargado"
        val items = arrayOf("Descargar Qwen 2.5 0.5B (≈547 MB, usa Wi-Fi)", "Importar archivo .task",
            "Cargar un modelo instalado", "Borrar un modelo")
        AlertDialog.Builder(this).setTitle("Modelo de IA · $estado").setItems(items) { _, i ->
            when (i) {
                0 -> askDownload()
                1 -> pickModel.launch(arrayOf("*/*"))
                2 -> chooseModel(false)
                3 -> chooseModel(true)
            }
        }.show()
    }

    private fun askDownload() {
        AlertDialog.Builder(this).setTitle("Modelo de IA")
            .setMessage("Para que la IA funcione necesitas un modelo (Qwen 2.5 0.5B, ≈547 MB). Conviene descargarlo con Wi-Fi.")
            .setPositiveButton("Descargar") { _, _ ->
                showProgress("Descargando… 0 %")
                bridge.descargarModelo(qwenUrl, qwenName)
            }
            .setNegativeButton("Ahora no", null).show()
    }

    private fun chooseModel(delete: Boolean) {
        val ms = engine.list()
        if (ms.isEmpty()) { Toast.makeText(this, "No hay modelos instalados", Toast.LENGTH_SHORT).show(); return }
        val labels = ms.map { "${it.name} (${it.length() / 1_000_000} MB)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(if (delete) "Borrar modelo" else "Cargar modelo")
            .setItems(labels) { _, i ->
                val name = ms[i].name
                if (delete) {
                    AlertDialog.Builder(this).setMessage("¿Borrar $name?")
                        .setPositiveButton("Borrar") { _, _ -> bridge.borrarModelo(name) }
                        .setNegativeButton("Cancelar", null).show()
                } else bridge.cargarModelo(name)
            }.show()
    }

    private fun showProgress(msg: String) {
        progressDialog?.dismiss()
        val tv = TextView(this).apply { text = msg; setPadding(48, 32, 48, 32) }
        progressText = tv
        progressDialog = AlertDialog.Builder(this).setTitle("Modelo de IA").setView(tv)
            .setNegativeButton("Ocultar", null).create().also { it.show() }
    }

    private fun onModelEvent(e: String, m: String) = runOnUiThread {
        when (e) {
            "progreso" -> progressText?.text = "Descargando… $m %"
            "cargando" -> Toast.makeText(this, "Cargando modelo…", Toast.LENGTH_SHORT).show()
            "listo" -> { progressDialog?.dismiss(); Toast.makeText(this, "Modelo listo ✅", Toast.LENGTH_LONG).show() }
            "descargado" -> {
                progressDialog?.dismiss()
                Toast.makeText(this, "Instalado: $m", Toast.LENGTH_SHORT).show()
                bridge.cargarModelo(m)
            }
            "error" -> { progressDialog?.dismiss(); Toast.makeText(this, "Error: $m", Toast.LENGTH_LONG).show() }
            "borrado" -> Toast.makeText(this, "Borrado: $m", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- HTML ----------
    private fun pasteDialog() {
        val et = EditText(this).apply { hint = "Pega aquí tu HTML"; minLines = 8; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("Introducir HTML").setView(et)
            .setPositiveButton("Guardar y aplicar") { _, _ -> applyHtml(et.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun historyDialog() {
        val h = store.history()
        if (h.isEmpty()) { Toast.makeText(this, "Aún no hay versiones anteriores", Toast.LENGTH_SHORT).show(); return }
        val labels = h.map { f -> store.versionOf(f.readText()) + "  ·  " +
            java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(f.name.removePrefix("v").removeSuffix(".html").toLongOrNull() ?: 0L)) }
        AlertDialog.Builder(this).setTitle("Volver a una versión").setItems(labels.toTypedArray()) { _, i ->
            store.restore(h[i]); load(store.html())
        }.show()
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
