package com.example.readio.di

import com.example.readio.data.audio.AndroidTtsEngine
import com.example.readio.data.audio.FishSpeechEngine
import com.example.readio.data.audio.VolcengineEngine
import com.example.readio.domain.engine.BatchTtsEngine
import com.example.readio.domain.engine.RealtimeTtsEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsEngineModule {

    // ── Realtime engines ──────────────────────────────────────────────────────
    // Synthesize one sentence at a time on device; no persistent cache.

    /** Android system TextToSpeech — always available, no configuration required. */
    @Binds @Singleton @IntoSet
    abstract fun bindAndroidTtsEngine(impl: AndroidTtsEngine): RealtimeTtsEngine

    // ── Batch engines ─────────────────────────────────────────────────────────
    // Pre-synthesize entire chapters; write SynthesisManifest to AudioCache.

    /**
     * Volcengine 精品长文本 v1 async API — cloud only.
     * Submit text blob → query → download MP3.
     */
    @Binds @Singleton @IntoSet
    abstract fun bindVolcengineEngine(impl: VolcengineEngine): BatchTtsEngine

    /**
     * Fish Speech local GPU inference server — Volcengine-compatible API, no auth.
     * Submit sentences array → query → download WAV.
     */
    @Binds @Singleton @IntoSet
    abstract fun bindFishSpeechEngine(impl: FishSpeechEngine): BatchTtsEngine
}
