package com.example.readio.di

import com.example.readio.data.audio.AndroidTtsEngine
import com.example.readio.data.audio.SherpaOnnxTtsEngine
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
    // Adding a new engine: implement [RealtimeTtsEngine] + one more [@Binds @IntoSet].

    /** Android system TextToSpeech — always available, no model download required. */
    @Binds @Singleton @IntoSet
    abstract fun bindAndroidTtsEngine(impl: AndroidTtsEngine): RealtimeTtsEngine

    /**
     * Sherpa-ONNX neural TTS — user-importable models (VITS or Kokoro).
     * Import .tar.bz2 archives via Settings before first synthesis.
     * Primary slot handles CJK text; secondary slot handles Latin text (optional).
     */
    @Binds @Singleton @IntoSet
    abstract fun bindSherpaOnnxEngine(impl: SherpaOnnxTtsEngine): RealtimeTtsEngine

    // ── Batch engines ─────────────────────────────────────────────────────────
    // Pre-synthesize entire chapters; write SynthesisManifest to AudioCache.
    // Adding a new engine: implement [BatchTtsEngine] + one more [@Binds @IntoSet].

    /**
     * Volcengine 精品长文本 v1 async API.
     * Internally: submit task → auto-poll → download MP3 → write SynthesisManifest.
     */
    @Binds @Singleton @IntoSet
    abstract fun bindVolcengineEngine(impl: VolcengineEngine): BatchTtsEngine
}
