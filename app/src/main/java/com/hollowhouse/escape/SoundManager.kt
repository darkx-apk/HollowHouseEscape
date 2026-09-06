package com.hollowhouse.escape

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

class SoundManager(private val context: Context) {

    private var menuMusic: MediaPlayer? = null
    private var stepSound: MediaPlayer? = null
    private var grannySound: MediaPlayer? = null
    private var grannySound2: MediaPlayer? = null

    @Volatile
    var isMuted = false
        private set

    private val audibleRange = 9f
    private val closeRange = 2.6f

    fun init() {
        menuMusic = makeLoopingPlayer("sounds/menu_music.mp3", 0.55f)
        stepSound = makeLoopingPlayer("sounds/stepsound.mp3", 0.85f)
        grannySound = makeLoopingPlayer("sounds/grannysound.mp3", 0.5f)
        grannySound2 = makeLoopingPlayer("sounds/grannysound2.mp3", 0.9f)
    }

    private fun makeLoopingPlayer(
        assetPath: String,
        volume: Float
    ): MediaPlayer? {
        return try {
            val afd = context.assets.openFd(assetPath)

            val mp = MediaPlayer()

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )

            mp.setDataSource(
                afd.fileDescriptor,
                afd.startOffset,
                afd.length
            )

            afd.close()

            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.prepare()

            mp
        } catch (e: Exception) {
            null
        }
    }

    fun setMuted(m: Boolean) {
        isMuted = m

        if (m) {
            pauseAll()
        }
    }

    fun playMenuMusic() {
        if (isMuted) return
        safeStart(menuMusic)
    }

    fun stopMenuMusic() {
        safePause(menuMusic)

        try {
            menuMusic?.seekTo(0)
        } catch (e: Exception) {
        }
    }

    fun setRunningFootsteps(running: Boolean) {
        if (isMuted || !running) {
            safePause(stepSound)
            return
        }

        safeStart(stepSound)
    }

    fun updateGrannySound(distance: Float) {
        if (isMuted) {
            safePause(grannySound)
            safePause(grannySound2)
            return
        }

        when {
            distance < closeRange -> {
                safePause(grannySound)

                safeStart(grannySound2)

                grannySound2?.setVolume(1f, 1f)
            }

            distance < audibleRange -> {
                safePause(grannySound2)

                safeStart(grannySound)

                val t = (
                    (audibleRange - distance) /
                    (audibleRange - closeRange)
                ).coerceIn(0f, 1f)

                val vol = 0.15f + t * 0.65f

                grannySound?.setVolume(vol, vol)
            }

            else -> {
                safePause(grannySound)
                safePause(grannySound2)
            }
        }
    }

    fun pauseAll() {
        safePause(menuMusic)
        safePause(stepSound)
        safePause(grannySound)
        safePause(grannySound2)
    }

    fun release() {
        try {
            menuMusic?.release()
        } catch (e: Exception) {
        }

        try {
            stepSound?.release()
        } catch (e: Exception) {
        }

        try {
            grannySound?.release()
        } catch (e: Exception) {
        }

        try {
            grannySound2?.release()
        } catch (e: Exception) {
        }

        menuMusic = null
        stepSound = null
        grannySound = null
        grannySound2 = null
    }

    private fun safeStart(mp: MediaPlayer?) {
        try {
            mp?.let {
                if (!it.isPlaying) {
                    it.start()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun safePause(mp: MediaPlayer?) {
        try {
            mp?.let {
                if (it.isPlaying) {
                    it.pause()
                }
            }
        } catch (e: Exception) {
        }
    }
}
