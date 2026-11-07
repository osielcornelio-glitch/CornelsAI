package com.cornels.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/** Motor local (MediaPipe LLM Inference). Modelos .task en filesDir/models. */
class LlmEngine(private val ctx: Context) {
    @Volatile private var llm: LlmInference? = null
    @Volatile var loadedName: String? = null
        private set

    fun modelsDir(): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun list(): List<File> =
        modelsDir().listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name } ?: emptyList()

    @Synchronized
    fun load(name: String) {
        close()
        val f = File(modelsDir(), File(name).name)
        require(f.exists()) { "No existe el modelo $name" }
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(f.absolutePath)
            .setMaxTokens(1024)
            .build()
        llm = LlmInference.createFromOptions(ctx, opts)
        loadedName = f.name
    }

    fun generate(prompt: String, onPartial: (String, Boolean) -> Unit) {
        val l = llm ?: throw IllegalStateException("Carga un modelo primero")
        l.generateResponseAsync(prompt) { part, done -> onPartial(part ?: "", done) }
    }

    fun delete(name: String) {
        if (loadedName == name) close()
        File(modelsDir(), File(name).name).delete()
    }

    @Synchronized
    fun close() { llm?.close(); llm = null; loadedName = null }
}
