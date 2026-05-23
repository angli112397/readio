package com.example.readio.di

// AndroidTtsEngine and VolcengineEngine are both @Singleton @Inject constructor classes —
// Hilt injects them directly into AudioRepositoryImpl without needing explicit @Binds here.
// AudioRepository binding lives in RepositoryModule.
