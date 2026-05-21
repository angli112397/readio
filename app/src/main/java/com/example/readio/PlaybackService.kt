package com.example.readio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setMediaNotificationProvider(ThrottledNotificationProvider(DefaultMediaNotificationProvider(this)))
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    // Stop the service when user swipes away the app and nothing is playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }
}

// Debounces Media3 notification posts to avoid Android's rate-limiter (Shedding warnings).
// Each addMediaItem / timeline change fires EVENT_TIMELINE_CHANGED and triggers a notification
// post; batching the initial items helps, but this wrapper is a safety net for edge cases.
@UnstableApi
private class ThrottledNotificationProvider(
    private val delegate: DefaultMediaNotificationProvider,
    private val minIntervalMs: Long = 300L
) : MediaNotification.Provider {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPostMs = 0L
    private var pending: Runnable? = null

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification = delegate.createNotification(
        mediaSession, customLayout, actionFactory
    ) { notification ->
        pending?.let { handler.removeCallbacks(it) }
        val delay = (lastPostMs + minIntervalMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val run = Runnable {
            lastPostMs = SystemClock.elapsedRealtime()
            onNotificationChangedCallback.onNotificationChanged(notification)
        }
        pending = run
        handler.postDelayed(run, delay)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean = delegate.handleCustomCommand(session, action, extras)
}
