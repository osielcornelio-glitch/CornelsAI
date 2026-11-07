package com.cornels.ai

import android.content.Context
import java.io.File

/** Guarda el HTML activo, el original (assets) y las últimas 5 versiones. */
class HtmlStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "ui").apply { mkdirs() }
    private val hist = File(dir, "history").apply { mkdirs() }
    private val current = File(dir, "current.html")

    fun originalHtml(): String = ctx.assets.open("default.html").bufferedReader().use { it.readText() }
    fun html(): String = if (current.exists()) current.readText() else originalHtml()

    private fun archive() {
        if (current.exists()) current.copyTo(File(hist, "v${System.currentTimeMillis()}.html"), true)
    }

    fun save(html: String) {
        archive()
        current.writeText(html)
        hist.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }

    fun restoreOriginal() { archive(); current.delete() }
    fun history(): List<File> = hist.listFiles()?.sortedByDescending { it.name } ?: emptyList()
    fun restore(f: File) = save(f.readText())

    fun versionOf(html: String): String =
        Regex("""<meta\s+name=["']cornels-version["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: "sin versión"
}
