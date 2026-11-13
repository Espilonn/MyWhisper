package dev.voxkey.bench

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bulle flottante + dictée.
 *
 * Service démarré normalement (pas de notification au repos). Il passe en
 * foreground seulement pendant l'enregistrement, car Android exige un service
 * foreground pour accéder au micro en arrière-plan.
 */
class OverlayService : Service() {

    companion object {
        private const val TICK_MS = 700L
        private const val MAX_WINDOW_SAMPLES = 16000 * 30
        private const val CHANNEL_ID = "voxkey-dictation"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false
            private set

        private var focusListener: ((Boolean) -> Unit)? = null

        fun notifyFocus(has: Boolean) {
            focusListener?.invoke(has)
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: FrameLayout
    private lateinit var bubbleLabel: TextView
    private lateinit var panel: TextView
    private lateinit var params: WindowManager.LayoutParams

    private var pulse: ObjectAnimator? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("voxkey", MODE_PRIVATE) }

    private var recorder: Recorder? = null

    @Volatile
    private var recording = false

    private var baseText: String? = null
    private var baseCursor = 0
    private var lastPushed: String? = null
    private var insertFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        running = true

        focusListener = { has ->
            main.post { if (!recording) root.visibility = if (has) View.VISIBLE else View.GONE }
        }
        if (VoxkeyAccessibilityService.instance != null) {
            root.visibility = if (VoxkeyAccessibilityService.hasTextField) View.VISIBLE else View.GONE
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        focusListener = null
        running = false
        recording = false
        pulse?.cancel()
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        main.postDelayed({ WhisperEngine.release() }, 1000)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- vues

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildViews() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        panel = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 6
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE6202020.toInt())
            }
        }

        bubbleLabel = TextView(this).apply {
            text = "\uD83C\uDFA4"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        bubble = FrameLayout(this).apply {
            addView(
                bubbleLabel,
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            )
            background = circle(0xFF2F6FED.toInt())
            elevation = dp(6).toFloat()
        }

        root.addView(panel, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        root.addView(bubble, LinearLayout.LayoutParams(dp(56), dp(56)))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = prefs.getInt("x", dp(16))
            y = prefs.getInt("y", dp(260))
        }

        wm.addView(root, params)
        root.visibility = View.GONE
        installTouch()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun installTouch() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        bubble.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY - dy).coerceAtLeast(0)
                        wm.updateViewLayout(root, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    } else {
                        toggleRecording()
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ----------------------------------------------------------- dictée

    private fun toggleRecording() {
        if (recording) stopSession() else startSession()
    }

    private fun startSession() {
        val cap = VoxkeyAccessibilityService.instance?.capture()
        baseText = cap?.first
        baseCursor = cap?.second ?: 0
        lastPushed = null
        insertFailed = false

        startForeground(
            NOTIF_ID, notification("Dictée en cours…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        recorder = Recorder().also { it.start() }
        recording = true

        bubble.background = circle(0xFFE0353A.toInt())
        panel.text = ""
        panel.visibility = View.VISIBLE
        pulse = ObjectAnimator.ofFloat(bubble, "scaleX", 1f, 1.18f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        thread(name = "voxkey-loop") { decodeLoop() }
    }

    private fun decodeLoop() {
        while (recording) {
            val rec = recorder ?: break
            val pcm = rec.snapshot(MAX_WINDOW_SAMPLES)
            if (pcm.size < 8000) { // < 0,5 s
                Thread.sleep(150)
                continue
            }
            val model = partialModelPath()
            if (model != null && WhisperEngine.ensure(model)) {
                val text = WhisperEngine.transcribe(pcm, "fr", 4)
                if (text.isNotBlank()) publish(text)
            }
            Thread.sleep(TICK_MS)
        }
    }

    private fun publish(text: String) {
        main.post {
            panel.text = text
            if (baseText != null && text != lastPushed) {
                val ok = pushText(text)
                if (ok) lastPushed = text else insertFailed = true
            }
        }
    }

    private fun stopSession() {
        recording = false
        pulse?.cancel()
        pulse = null
        bubble.scaleX = 1f
        bubble.scaleY = 1f
        bubble.background = circle(0xFF2F6FED.toInt())

        val rec = recorder
        recorder = null
        val pcm = rec?.stop() ?: FloatArray(0)

        thread(name = "voxkey-final") {
            var finalText = ""
            val model = selectedModelPath()
            if (pcm.size >= 8000 && model != null && WhisperEngine.ensure(model)) {
                finalText = WhisperEngine.transcribe(pcm, "fr", 4)
            }

            main.post {
                if (finalText.isNotBlank()) {
                    panel.text = finalText
                    if (baseText != null) {
                        if (insertFailed || !pushText(finalText)) {
                            VoxkeyAccessibilityService.instance?.paste(finalText)
                        }
                    } else {
                        VoxkeyAccessibilityService.instance?.paste(finalText)
                    }
                }
                overlayStopForeground()
                main.postDelayed({ panel.visibility = View.GONE }, 2500)
                main.postDelayed({ WhisperEngine.release() }, 60_000)
            }
        }
    }

    private fun pushText(text: String): Boolean {
        val base = baseText ?: return false
        val c = baseCursor.coerceIn(0, base.length)
        val full = base.substring(0, c) + text + base.substring(c)
        return VoxkeyAccessibilityService.instance?.applyText(full, c + text.length) ?: false
    }

    // ------------------------------------------------------------ modèles

    private fun modelDir(): File? = getExternalFilesDir(null)

    private fun selectedModelPath(): String? {
        val name = getSharedPreferences("voxkey", MODE_PRIVATE).getString("model", null)
        val dir = modelDir() ?: return null
        val f = if (name != null) File(dir, name) else null
        if (f != null && f.exists()) return f.absolutePath
        return dir.listFiles()?.filter { it.name.endsWith(".bin") }
            ?.minByOrNull { it.length() }?.absolutePath
    }

    /** Les partiels utilisent le plus petit modèle disponible : plus réactif. */
    private fun partialModelPath(): String? {
        val dir = modelDir() ?: return null
        return dir.listFiles()?.filter { it.name.endsWith(".bin") }
            ?.minByOrNull { it.length() }?.absolutePath
    }

    // -------------------------------------------------------- notification

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dictée", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("voxkey")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun overlayStopForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
