package dev.voxkey.bench

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transcription par API distante. Groq et OpenRouter exposent le même contrat OpenAI
 * (`POST /audio/transcriptions`, multipart `file` + `model`), donc un seul chemin de code :
 * seules l'URL, le modèle par défaut et l'en-tête de test changent.
 *
 * L'audio part en WAV 16 kHz mono. Une dictée fait quelques secondes, très loin des 25 Mo.
 */
object RemoteEngine {

    const val PREF_ENGINE = "engine"           // "local" (défaut) ou "cloud"
    const val PREF_PROVIDER = "cloud_provider" // "groq" ou "openrouter"
    const val PREF_MODEL = "cloud_model"

    /** Ancienne clé unique, d'avant la séparation par fournisseur : relue une fois puis effacée. */
    const val PREF_KEY = "cloud_key"

    const val GROQ = "groq"
    const val OPENROUTER = "openrouter"

    private fun prefs(c: Context) = c.getSharedPreferences("voxkey", Context.MODE_PRIVATE)

    fun isCloud(c: Context) = prefs(c).getString(PREF_ENGINE, "local") == "cloud"

    fun provider(c: Context) = prefs(c).getString(PREF_PROVIDER, GROQ) ?: GROQ

    fun defaultModel(provider: String) =
        if (provider == OPENROUTER) "openai/whisper-large-v3" else "whisper-large-v3-turbo"

    fun model(c: Context) =
        prefs(c).getString(PREF_MODEL, null)?.takeIf { it.isNotBlank() } ?: defaultModel(provider(c))

    // shortcut: la clé est chiffrée par le Keystore (voir Secrets) mais reste lisible par l'app
    // elle-même ; suffisant contre une copie du fichier, pas contre un appareil rooté.
    fun key(c: Context) = key(c, provider(c))

    /** Clé du fournisseur demandé : chacune la sienne, donc changer d'API ne perd pas l'autre. */
    fun key(c: Context, provider: String): String {
        val p = prefs(c)
        val mine = p.getString(keyPref(provider), null)
        if (mine != null) return Secrets.open(mine).trim()

        // Migration : la clé unique de la version précédente appartenait à Groq par défaut.
        val legacy = if (provider == GROQ) p.getString(PREF_KEY, null) else null
        if (legacy.isNullOrBlank()) return ""
        val plain = Secrets.open(legacy).trim()
        saveKey(c, GROQ, plain)
        p.edit().remove(PREF_KEY).apply()
        return plain
    }

    fun saveKey(c: Context, provider: String, value: String) {
        val v = value.trim()
        prefs(c).edit().putString(keyPref(provider), if (v.isEmpty()) "" else Secrets.seal(v)).apply()
    }

    private fun keyPref(provider: String) =
        if (provider == OPENROUTER) "cloud_key_openrouter" else "cloud_key_groq"

    private fun transcriptionUrl(p: String) = if (p == OPENROUTER)
        "https://openrouter.ai/api/v1/audio/transcriptions"
    else
        "https://api.groq.com/openai/v1/audio/transcriptions"

    /** Entrée gratuite (liste des modèles / état de la clé) : vérifie la clé sans consommer de crédit. */
    private fun checkUrl(p: String) = if (p == OPENROUTER)
        "https://openrouter.ai/api/v1/key"
    else
        "https://api.groq.com/openai/v1/models"

    fun transcribe(ctx: Context, pcm: FloatArray, sampleRate: Int = 16000): String {
        val key = key(ctx)
        if (key.isBlank()) throw IllegalStateException("clé API absente")
        return post(transcriptionUrl(provider(ctx)), key, provider(ctx), model(ctx), wav(pcm, sampleRate))
    }

    /** `null` si la clé répond, sinon le message d'erreur à afficher. */
    fun check(ctx: Context): String? {
        val key = key(ctx)
        if (key.isBlank()) return "clé vide"
        val conn = try {
            URL(checkUrl(provider(ctx))).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return t.message ?: "réseau indisponible"
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            if (code in 200..299) null
            else "HTTP $code · ${errorText(conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())}"
        } catch (t: Throwable) {
            t.message ?: "réseau indisponible"
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun post(url: String, key: String, provider: String, model: String, wav: ByteArray): String {
        val boundary = "----voxkey${System.nanoTime()}"
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\n".toByteArray())
                write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                write(value.toByteArray())
                write("\r\n".toByteArray())
            }
            field("model", model)
            field("response_format", "json")
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray())
            write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            // 45 s : OpenRouter coupe en amont à 60 s, Groq répond en moins d'une seconde sur ces durées.
            conn.readTimeout = 45_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Content-Length", body.size.toString())
            if (provider == OPENROUTER) conn.setRequestProperty("X-Title", "MyWhisper")
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RuntimeException("HTTP $code · ${errorText(raw)}")
            return JSONObject(raw).optString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Les deux services renvoient `{"error":{"message":"…"}}` : on garde le message, pas le JSON. */
    private fun errorText(raw: String): String {
        if (raw.isBlank()) return "réponse vide"
        val msg = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return (msg?.takeIf { it.isNotBlank() } ?: raw).take(160)
    }

    // ------------------------------------------------------------------ WAV

    /** PCM float [-1, 1] → WAV 16 bits mono, en-tête RIFF de 44 octets. */
    private fun wav(pcm: FloatArray, sampleRate: Int): ByteArray {
        val n = pcm.size
        val dataBytes = n * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun le32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
        )
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

        out.write("RIFF".toByteArray()); out.write(le32(36 + dataBytes)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16))
        out.write(le16(1)); out.write(le16(1))            // PCM, mono
        out.write(le32(sampleRate)); out.write(le32(sampleRate * 2))
        out.write(le16(2)); out.write(le16(16))           // alignement bloc, bits
        out.write("data".toByteArray()); out.write(le32(dataBytes))

        val buf = ByteArray(dataBytes)
        for (i in 0 until n) {
            val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[i * 2] = (s and 0xff).toByte()
            buf[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }
}
