package dev.voxkey.bench

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Mise à jour depuis les releases GitHub : une vérification par jour, puis l'APK est
 * déposé dans Téléchargements et passé au programme d'installation du système.
 * Une app installée par le Play Store s'y met à jour toute seule : rien à faire ici.
 */
object Update {

    class Release(val version: String, val notes: String, val url: String)

    private const val API = "https://api.github.com/repos/Espilonn/MyWhisper/releases/latest"
    private const val DAY = 24 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var downloading = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voxkey", Context.MODE_PRIVATE)

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull().orEmpty()

    /** Version publiée plus récente que celle-ci, ou null. Le résultat est gardé 24 h. */
    fun check(ctx: Context, onResult: (Release?) -> Unit) {
        if (installedByPlay(ctx)) {
            onResult(null)
            return
        }
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("updateAt", 0L) < DAY) {
            onResult(cached(ctx))
            return
        }
        thread(name = "voxkey-update") {
            var ok = false
            val r = try {
                fetch(ctx).also { ok = true }
            } catch (t: Throwable) {
                // Pas de réseau : on ne date pas la vérification, elle repartira au prochain lancement.
                Log.w("VXK", "update check", t)
                null
            }
            if (ok) {
                p.edit().putLong("updateAt", now)
                    .putString("updateVersion", r?.version)
                    .putString("updateNotes", r?.notes)
                    .putString("updateUrl", r?.url)
                    .apply()
            }
            main.post { onResult(r) }
        }
    }

    private fun cached(ctx: Context): Release? {
        val p = prefs(ctx)
        val v = p.getString("updateVersion", null) ?: return null
        val url = p.getString("updateUrl", null) ?: return null
        return Release(v, p.getString("updateNotes", "").orEmpty(), url)
    }

    /** Lance la vérification ; null quand l'app est déjà à jour. Lève sur erreur réseau. */
    private fun fetch(ctx: Context): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MyWhisper-Android")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").trimStart('v', 'V')
            if (compare(tag, currentVersion(ctx)) <= 0) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith("-arm64.apk")) {
                    return Release(tag, json.optString("body"), a.optString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Comparaison de versions point par point : « 1.1.10 » est après « 1.1.9 ». */
    private fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    private fun installedByPlay(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
    }.getOrNull() == "com.android.vending"

    /** Télécharge l'APK, le dépose dans Téléchargements, ouvre le programme d'installation. */
    fun install(ctx: Context, r: Release, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        if (downloading) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // Une seule case à cocher pour toute la vie de l'app : on l'ouvre nous-mêmes.
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            onError(ctx.getString(R.string.update_unknown_sources))
            return
        }
        downloading = true
        thread(name = "voxkey-install") {
            val apk = File(ctx.cacheDir, "mywhisper-${r.version}.apk")
            try {
                val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != shown) {
                                        shown = pct
                                        main.post { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                val uri = publish(ctx, apk, r.version)
                apk.delete()
                main.post {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (t: Throwable) {
                Log.w("VXK", "update download", t)
                apk.delete()
                main.post { onError(ctx.getString(R.string.update_failed, t.message ?: "?")) }
            } finally {
                downloading = false
            }
        }
    }

    /** Dépose l'APK dans Téléchargements et rend son URI publique : pas de FileProvider à écrire. */
    private fun publish(ctx: Context, apk: File, version: String): Uri {
        val res = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "MyWhisper-$version-arm64.apk")
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("downloads refused")
        res.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        res.update(uri, values, null, null)
        return uri
    }
}
