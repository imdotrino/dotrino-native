package com.dotrino.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * EL TRINO: el sonido de los avisos de Dotrino, uno de siete al azar. El mismo en iOS
 * (`DotrinoRing.swift`, y el proxio elige uno en cada aviso de APNs).
 *
 * En Android el sonido de un canal es fijo, así que los canales de Dotrino se crean MUDOS y el
 * trino lo toca [play] al mostrar el aviso. Eso cubre también lo que no pasa por el sistema:
 * con la app abierta un pedido o un mensaje entra directo por la conexión, no llega ningún
 * aviso de Google, y es la app la que tiene que sonar.
 */
object DotrinoRing {
    private val SOUNDS = intArrayOf(
        R.raw.dotrino_ring_1, R.raw.dotrino_ring_2, R.raw.dotrino_ring_3, R.raw.dotrino_ring_4,
        R.raw.dotrino_ring_5, R.raw.dotrino_ring_6, R.raw.dotrino_ring_7,
    )

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Un canal de avisos sin sonido propio (el trino lo pone [play]). El sonido de un canal no
     * se puede cambiar después de crearlo: por eso los canales mudos llevan un id nuevo, y
     * [replaces] borra el viejo que sonaba con el tono del sistema.
     */
    fun channel(ctx: Context, id: String, name: String, replaces: String? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (replaces != null && nm.getNotificationChannel(replaces) != null) nm.deleteNotificationChannel(replaces)
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
        })
    }

    /**
     * Toca un trino al azar, si el teléfono deja sonar: en silencio, en vibración o con «No
     * molestar», no suena (lo mismo que haría el sistema con un aviso).
     */
    fun play(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return
        val mp = MediaPlayer.create(ctx, SOUNDS.random(), attrs, am.generateAudioSessionId()) ?: return
        mp.setOnCompletionListener { it.release() }
        mp.setOnErrorListener { p, _, _ -> p.release(); true }
        mp.start()
    }
}
