package dev.voxkey.bench

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Modèles téléchargeables. Mêmes fichiers que ceux publiés par ggml-org, donc
 * l'app peut les récupérer directement : pas de serveur à maintenir.
 * Il n'existe pas de Q6_K publié ; Q8_0 est le cran de qualité au-dessus.
 */
object ModelCatalog {

    /** [labelRes] est le libellé affiché, une des clés `catalog_*`. */
    data class Remote(val file: String, val labelRes: Int, val bytes: Long) {
        val mo: Long get() = bytes / 1_048_576
    }

    private const val BASE = "https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/"

    val all = listOf(
        Remote("ggml-parakeet-tdt-0.6b-v3-q4_k.bin", R.string.catalog_q4k, 415_611_879),
        Remote("ggml-parakeet-tdt-0.6b-v3-q8_0.bin", R.string.catalog_q8, 668_757_119),
    )

    fun url(file: String) = BASE + file

    /** Un seul téléchargement à la fois : deux threads écrivaient le même `.part`. */
    @Volatile
    private var running = false

    /** Télécharge vers [dir], écrit dans un `.part` puis renomme : un fichier partiel ne casse pas l'app. */
    fun download(
        ctx: Context,
        file: String,
        dir: File,
        onProgress: (Long, Long) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) return
        running = true
        val dest = File(dir, file)
        thread(name = "voxkey-download") {
            val part = File(dir, "$file.part")
            try {
                try {
                    val conn = (URL(url(file)).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            onError(ctx.getString(R.string.catalog_error_server, code))
                            return@thread
                        }
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                var done = 0L
                                var shown = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (done - shown >= 4L * 1_048_576) {
                                        shown = done
                                        onProgress(done, total)
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }

                    if (part.length() < 1_000_000) {
                        part.delete()
                        onError(ctx.getString(R.string.catalog_error_partial))
                        return@thread
                    }
                    if (!part.renameTo(dest)) {
                        part.delete()
                        onError(ctx.getString(R.string.catalog_error_write))
                        return@thread
                    }
                    onProgress(dest.length(), dest.length())
                    onDone(dest)
                } catch (t: Throwable) {
                    Log.w("VXK", "download $file", t)
                    part.delete()
                    onError(t.message ?: ctx.getString(R.string.catalog_error_network))
                }
            } finally {
                running = false
            }
        }
    }
}
