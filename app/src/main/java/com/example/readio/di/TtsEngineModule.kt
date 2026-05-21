package com.example.readio.di

import com.example.readio.data.audio.AndroidTtsEngine
import com.example.readio.data.audio.AzureTtsEngine
import com.example.readio.data.audio.TtsEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsEngineModule {

    @Binds @IntoSet
    abstract fun bindAzureEngine(impl: AzureTtsEngine): TtsEngine

    @Binds @IntoSet
    abstract fun bindAndroidEngine(impl: AndroidTtsEngine): TtsEngine
}
