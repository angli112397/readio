package com.example.readio.domain.repository

import com.example.readio.domain.model.TtsConfig
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    /**
     * 监听 TTS 配置变化，初始值由实现层提供默认值。
     * 设置页和播放器均通过此 Flow 响应配置更新。
     */
    fun observeTtsConfig(): Flow<TtsConfig>

    /** 单次读取当前 TTS 配置，供 use case 在生成音频前使用 */
    suspend fun getTtsConfig(): TtsConfig

    /** 持久化 TTS 配置 */
    suspend fun saveTtsConfig(config: TtsConfig)
}
