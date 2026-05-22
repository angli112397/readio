# Readio

An Android EPUB audiobook reader with a drum-roller chunk wheel: text and audio stay in sync, chunk by chunk. Tap any chunk to get an instant offline translation.

## What it does

Import an EPUB, press play. The content is split into semantically coherent chunks — respecting sentence boundaries, bracket pairs, and language-specific rules — and read aloud via Azure Neural TTS (or the on-device system TTS for offline use). The chunk wheel scrolls to keep the current chunk centred while audio advances automatically. Tap any centred chunk to translate it instantly via ML Kit offline translation.

## Features

- **EPUB import** — parses OPF spine + NCX for chapter structure; normalises Unicode whitespace and invisible characters before chunking
- **Language-aware chunking** — `chunkSize=150` means ~150 CJK characters or ~37 English words, keeping visual density consistent across languages. Sentence splitting respects paired brackets (`「」`, `""`, `（）`, …) so quoted dialogue is never broken mid-quote
- **Chunk wheel** — drum-roller UI with scale/alpha perspective, haptic feedback on each tick, and per-chunk text alignment (Justify for CJK, Start for Latin)
- **Offline translation** — tap the centred chunk; ML Kit translates it on-device. Toggle tap to dismiss. Supports 简体中文, English, 日本語, 한국어 as target languages
- **Dual TTS providers** — Azure Neural TTS for cloud quality; System TTS for zero-config offline use. Both use the same caching pipeline
- **Offline caching** — audio cached to `filesDir/audio/` keyed by `provider|voice|chunkSize`; cache invalidated automatically when TTS config or chunk size changes
- **Streaming playback** — audio starts after the first buffer of chunks is generated; the rest are added to the ExoPlayer playlist as synthesis completes
- **Chapter download manager** — download chapters individually or in bulk; downloads are app-scoped and survive navigation and screen rotation
- **Background playback** — `MediaSessionService` keeps audio alive when the app is minimised; lock screen and notification controls work out of the box
- **Reading preferences** — font size, line height, chunk size, background theme (Default / Warm / Sepia / Night), translation target language; all persisted via DataStore

## Setup

1. Create an [Azure Speech resource](https://portal.azure.com) (F0 free tier: 500 k chars/month)
2. Build and run. In **Settings**, select *Microsoft Azure*, paste your API key, choose a region and voice, then press Save.

> For offline testing without an Azure account, select **系统 TTS（本地）** in Settings — no key required.

## Tech stack

| Layer | Libraries |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Database | Room |
| Preferences | DataStore |
| Media | ExoPlayer (Media3), MediaSessionService |
| EPUB parsing | Jsoup |
| Translation | ML Kit Translate (offline) |
| Language | Kotlin, Coroutines, Flow |

## Architecture

Clean Architecture. Dependency direction: UI → Domain ← Data.

```
ui/
  library/       Book list screen + ViewModel
  reader/        ChunkWheel, ReaderScreen, ReaderViewModel, PlaybackService
  chapters/      Chapter list screen + ViewModel
  settings/      Settings screen + ViewModel, TtsVoiceCatalog

domain/
  model/         EpubBook, Chapter, Chunk, Language, TtsConfig, ChapterAudio,
                 ReadingPreferences, ReadingTheme, TranslationLanguage, …
  repository/    EpubRepository, AudioRepository, SettingsRepository,
                 VocabularyRepository, ReadingProgressRepository
  service/       TextChunker, PunctuationTable
  manager/       AudioDownloadManager (app-scoped, SupervisorJob)
  usecase/       GetReadingPositionUseCase, PrepareChapterAudioUseCase,
                 DownloadChapterAudioUseCase

data/
  epub/          EpubParser (ZipFile + Jsoup)
  audio/         TtsEngine interface, AzureTtsEngine, AndroidTtsEngine,
                 AudioRepositoryImpl
  db/            Room database, DAOs, entities
  repository/    EpubRepositoryImpl, SettingsRepositoryImpl,
                 VocabularyRepositoryImpl, ReadingProgressRepositoryImpl
```

### Key design decisions

**Per-chunk audio files** — each chunk is synthesised as an individual MP3. The chunk index equals the ExoPlayer playlist index, so sync is trivially correct: `onMediaItemTransition` updates the display, nothing else needed.

**Streaming playback** — audio starts after the first buffer of chunks is ready (startIndex + 5); remaining chunks are appended to the playlist as synthesis completes. Cache hits skip synthesis and serve all files immediately.

**Cache key** — `provider|voice|chunkSize`. Rate is excluded (applied locally via `setPlaybackSpeed`). Changing chunk size or voice invalidates the cache; AudioDownloadManager detects this and refreshes chapter statuses automatically.

**Language-aware chunking** — `TextChunker` uses `LATIN_WORD_WEIGHT = 4`: one English word counts as 4 units, one CJK character counts as 1 unit. A single `chunkSize` slider in Settings controls both languages with consistent visual density.

**Bracket-respecting sentence splitting** — `TextChunker.mergeBracketSpans` re-joins sentence fragments that were split inside an unclosed bracket pair, keeping dialogue and parenthetical content intact. English `.` is recognised as a sentence boundary only when followed by whitespace + an uppercase letter or opening quote.

**Translation** — `VocabularyRepositoryImpl` wraps ML Kit's `Translator`. Language is auto-detected per chunk from character ranges. Model download (≈15 MB per language pair) is triggered on first use with a 30 s timeout. A `Mutex` serialises `setLanguage` + synthesis calls to prevent concurrent-access races.

**Background downloads** — `AudioDownloadManager` lives in a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` bound to the process. Downloads survive ViewModel destruction and navigation.

**TTS extensibility** — to add a provider:
1. Add an entry to `TtsProvider`
2. Implement `TtsEngine`
3. Add `@Binds @IntoSet` in `TtsEngineModule`
4. Add voices to `TtsVoiceCatalog`

## Storage

| What | Where | Survives update | Survives clear data |
|---|---|---|---|
| EPUB files | `filesDir/epubs/` | ✓ | ✗ |
| Audio cache | `filesDir/audio/` | ✓ | ✗ |
| Reading positions | Room database | ✓ | ✗ |
| TTS credentials | DataStore | ✓ | ✗ |
| Reading preferences | DataStore | ✓ | ✗ |
