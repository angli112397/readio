package com.example.readio.di

import com.example.readio.data.audio.AndroidTtsEngine
import com.example.readio.data.audio.LocalTtsEngine
import com.example.readio.data.audio.VolcengineEngine
import com.example.readio.domain.tts.CloudTtsEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsEngineModule {

    /** On-device TTS (Android system TextToSpeech). */
    @Binds @Singleton
    abstract fun bindLocalTtsEngine(impl: AndroidTtsEngine): LocalTtsEngine

    /**
     * Volcengine 精品长文本 v1 API.
     * Adding a new cloud provider = implement [CloudTtsEngine] + add one more [@Binds @IntoSet].
     */
    @Binds @Singleton @IntoSet
    abstract fun bindVolcengineEngine(impl: VolcengineEngine): CloudTtsEngine
}
