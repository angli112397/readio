package com.example.readio.di

import com.example.readio.data.audio.AudioRepositoryImpl
import com.example.readio.data.repository.EpubRepositoryImpl
import com.example.readio.data.repository.ReadingProgressRepositoryImpl
import com.example.readio.data.repository.SettingsRepositoryImpl
import com.example.readio.data.repository.VocabularyRepositoryImpl
import com.example.readio.data.translation.SmartTranslationEngine
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.ReadingProgressRepository
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.repository.VocabularyRepository
import com.example.readio.domain.translation.TranslationEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindEpubRepository(impl: EpubRepositoryImpl): EpubRepository

    @Binds @Singleton
    abstract fun bindReadingProgressRepository(impl: ReadingProgressRepositoryImpl): ReadingProgressRepository

    @Binds @Singleton
    abstract fun bindAudioRepository(impl: AudioRepositoryImpl): AudioRepository

    @Binds @Singleton
    abstract fun bindVocabularyRepository(impl: VocabularyRepositoryImpl): VocabularyRepository

    /**
     * Active translation backend.
     * [SmartTranslationEngine] tries Volcengine when credentials are configured,
     * falls back to ML Kit (on-device, free) otherwise.
     * Adding a new provider: implement [TranslationEngine] + swap/extend [SmartTranslationEngine].
     */
    @Binds @Singleton
    abstract fun bindTranslationEngine(impl: SmartTranslationEngine): TranslationEngine
}
