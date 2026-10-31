package com.example.videotranslator

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) play(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val pickButton = Button(this).apply {
            text = "اختر فيديو"
            setOnClickListener { pickVideo.launch("video/*") }
        }

        playerView = PlayerView(this)

        root.addView(pickButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(playerView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun play(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            it.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
