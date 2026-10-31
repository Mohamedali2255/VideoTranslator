package com.example.videotranslator

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private data class Cue(val start: Long, val end: Long, val text: String)

    private val model = "gemini-2.5-flash"
    private val maxBytes = 14 * 1024 * 1024

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var subtitleView: TextView
    private lateinit var statusView: TextView
    private lateinit var keyInput: EditText
    private var cues: List<Cue> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                play(uri)
                translate(uri)
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            val pos = player?.currentPosition ?: 0L
            val cue = cues.firstOrNull { pos >= it.start && pos <= it.end }
            subtitleView.text = cue?.text ?: ""
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        keyInput = EditText(this).apply {
            hint = "مفتاح Gemini API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }

        val pickButton = Button(this).apply {
            text = "اختر فيديو وترجمه"
            setOnClickListener { pickVideo.launch("video/*") }
        }

        statusView = TextView(this).apply {
            setPadding(24, 8, 24, 8)
        }

        playerView = PlayerView(this)

        subtitleView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }

        val frame = FrameLayout(this)
        frame.addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val subParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        subParams.bottomMargin = 160
        frame.addView(subtitleView, subParams)

        root.addView(keyInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(pickButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    private fun status(s: String) {
        statusView.text = s
    }

    private fun play(uri: Uri) {
        cues = emptyList()
        subtitleView.text = ""
        player?.release()
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            it.playWhenReady = true
        }
    }

    private fun translate(uri: Uri) {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            status("أدخل مفتاح Gemini أولاً ثم اختر الفيديو")
            return
        }
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putString("key", key).apply()
        status("جاري الترجمة، قد يستغرق دقيقة...")

        Thread {
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw Exception("تعذر فتح الفيديو")
                val out = ByteArrayOutputStream()
                input.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) {
                            throw Exception("الفيديو أكبر من 14 ميغابايت، جرّب فيديو أقصر")
                        }
                    }
                }
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val mime = contentResolver.getType(uri) ?: "video/mp4"

                val prompt = "Transcribe the speech in this video and translate it into Arabic. " +
                    "Return ONLY a JSON array. Each item must be an object like " +
                    "{\"start\": 0.0, \"end\": 3.5, \"text\": \"Arabic translation\"} " +
                    "where start and end are in seconds. Keep each segment under 8 seconds."

                val parts = JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", mime).put("data", b64)
                        )
                    )
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put(
                        "generationConfig",
                        JSONObject().put("responseMimeType", "application/json")
                    )

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 30000
                conn.readTimeout = 180000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val response = stream.bufferedReader().use { it.readText() }
                if (code >= 400) {
                    throw Exception("خطأ $code: " + response.take(200))
                }

                var text = JSONObject(response)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text")
                text = text.replace("```json", "").replace("```", "").trim()

                val arr = JSONArray(text)
                val list = ArrayList<Cue>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Cue(
                            (o.getDouble("start") * 1000).toLong(),
                            (o.getDouble("end") * 1000).toLong(),
                            o.getString("text")
                        )
                    )
                }
                runOnUiThread {
                    cues = list
                    status("تمت الترجمة: ${list.size} مقطع")
                }
            } catch (e: Exception) {
                runOnUiThread { status("فشل: ${e.message}") }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
    }
}
