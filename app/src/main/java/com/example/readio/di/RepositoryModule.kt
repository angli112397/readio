package com.example.readio.di

import com.example.readio.data.repository.EpubRepositoryImpl
import com.example.readio.data.repository.ReadingProgressRepositoryImpl
import com.example.readio.data.repository.SettingsRepositoryImpl
import com.example.readio.data.audio.AudioRepositoryImpl
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.ReadingProgressRepository
import com.example.readio.domain.repository.SettingsRepository
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
}
