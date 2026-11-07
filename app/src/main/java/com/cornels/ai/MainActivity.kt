package com.cornels.ai

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val base = "https://cornels.local/"
    private lateinit var web: WebView
    private lateinit var store: HtmlStore
    private lateinit var bridge: Bridge

    private val pickHtml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val t = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            if (t != null) applyHtml(t)
        }
    }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { bridge.importModel(it) }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HtmlStore(this)
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
        }
        bridge = Bridge(this, web, LlmEngine(this), store) { pickModel.launch(arrayOf("*/*")) }
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
        val items = arrayOf("Introducir HTML (pegar)", "Importar archivo HTML", "Historial de versiones",
            "Restaurar original", "Recargar")
        AlertDialog.Builder(this).setTitle("CORNELS AI").setItems(items) { _, i ->
            when (i) {
                0 -> pasteDialog()
                1 -> pickHtml.launch(arrayOf("text/html", "text/plain", "*/*"))
                2 -> historyDialog()
                3 -> { store.restoreOriginal(); load(store.html()) }
                4 -> load(store.html())
            }
        }.show()
    }

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
