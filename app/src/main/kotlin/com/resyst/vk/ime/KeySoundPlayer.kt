package com.resyst.vk.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.resyst.vk.core.KeySynth
import com.resyst.vk.core.SoundKind
import com.resyst.vk.core.SoundPack
import java.io.File
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * Plays the synthesized key sounds. Each pack is rendered once to WAV in the cache dir
 * (KeySynth, no shipped audio) and loaded into a low-latency SoundPool.
 */
class KeySoundPlayer(private val context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var ids: Map<SoundKind, Int> = emptyMap()
    private var loadedPack: SoundPack? = null

    fun configure(enabled: Boolean, pack: SoundPack) {
        if (!enabled || pack == loadedPack) return
        loadedPack = pack
        io.execute {
            val old = ids
            val fresh = EnumMap<SoundKind, Int>(SoundKind::class.java)
            for (kind in SoundKind.values()) {
                val f = File(context.cacheDir, "keysnd-v1-${pack.name.lowercase()}-${kind.name.lowercase()}.wav")
                if (!f.exists() || f.length() < 64) {
                    f.writeBytes(KeySynth.wav(KeySynth.render(pack, kind, seed = kind.ordinal + 1)))
                }
                fresh[kind] = pool.load(f.path, 1)
            }
            ids = fresh
            old.values.forEach { pool.unload(it) }
        }
    }

    fun play(kind: SoundKind, volume: Float) {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[kind] ?: return
        val v = volume.coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun release() {
        io.shutdownNow()
        pool.release()
    }
}
