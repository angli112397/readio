# Readio

An Android EPUB audiobook reader that converts any EPUB to a karaoke-style listening experience — text and audio stay in sync sentence by sentence.

## What it does

Import an EPUB, press play. Each sentence is read aloud via Azure Neural TTS while the sentence wheel scrolls to keep the current sentence centered on screen. Sentence-level sync is handled by ExoPlayer: each sentence is its own audio file and its own playlist item, so the display index always matches the player's current item index.

## Features

- **EPUB import** — parses OPF spine + NCX for chapter structure, splits content into sentences via `BreakIterator`
- **Paragraph wheel** — drum-roller UI with scale/alpha perspective effect and haptic feedback on each scroll tick
- **Azure Neural TTS** — per-sentence synthesis, cached locally; playback speed controlled by ExoPlayer (not baked into audio)
- **Offline caching** — audio files survive app updates; stored in private internal storage (`filesDir/audio/`)
- **Chapter download manager** — download individual chapters or all at once; downloads continue in the background when navigating away
- **Background playback** — `MediaSessionService` keeps audio alive when the app is minimised; lock screen and notification controls work out of the box
- **Multi-provider architecture** — adding a new TTS provider requires one enum entry, one class implementing `TtsEngine`, and one Hilt binding

## Setup

1. Create an [Azure Speech resource](https://portal.azure.com) (F0 free tier: 500k chars/month)
2. Add to `local.properties` (never committed):

```
AZURE_SPEECH_KEY=your_key_here
AZURE_SPEECH_REGION=eastasia
```

3. Build and run. Enter the key and region in **Settings** before pressing play.

> The key in `local.properties` is used only during development. In the app, credentials are stored in DataStore and entered by the user via Settings.

## Tech stack

| Layer | Libraries |
|-------|-----------|
| UI | Jetpack Compose, Material3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Database | Room |
| Preferences | DataStore |
| Media | ExoPlayer (Media3), MediaSessionService |
| EPUB parsing | Jsoup |
| Language | Kotlin, Coroutines, Flow |

## Architecture

Clean Architecture with three layers. Dependency direction: UI → Domain ← Data.

```
ui/
  library/       BookList screen + ViewModel
  reader/        ParagraphWheel + ReaderViewModel + PlaybackService
  chapters/      ChapterList screen + AudioDownloadManager (singleton, app-scoped)
  settings/      Settings screen + ViewModel

domain/
  model/         EpubBook, Chapter, Paragraph, TtsConfig, ChapterAudio, …
  repository/    Interfaces (EpubRepository, AudioRepository, SettingsRepository, …)
  usecase/       GetReadingPositionUseCase, PrepareChapterAudioUseCase,
                 DownloadChapterAudioUseCase, DeleteBookUseCase

data/
  epub/          EpubParser (ZipFile + Jsoup, BreakIterator sentence splitting)
  audio/         TtsEngine interface + AzureTtsEngine, AudioRepositoryImpl
  db/            Room database, DAOs, entities
  repository/    EpubRepositoryImpl, SettingsRepositoryImpl, …
```

### Key design decisions

**Per-sentence audio files** — each sentence is synthesised as an individual MP3. The sentence index equals the ExoPlayer playlist index, so sync is trivially correct: `onMediaItemTransition` updates the display, nothing else needed.

**Streaming playback** — audio starts after 5 sentences are generated; the rest are added to the playlist as they complete. Cache hits skip generation entirely and load all files at once.

**Cache key** — `PROVIDER|voice` (rate excluded). Playback speed is applied locally via `player.setPlaybackSpeed()`, so changing speed never invalidates cached audio.

**Background downloads** — `AudioDownloadManager` uses a `CoroutineScope(SupervisorJob())` tied to the process, not to any ViewModel. Downloads survive navigation and screen rotation.

**TTS provider extensibility** — to add a provider:
1. Add an entry to `TtsProvider` enum
2. Implement `TtsEngine`
3. Add `@Binds @IntoSet` in `TtsEngineModule`
4. Add voices to `TtsVoiceCatalog`

The repository, ViewModels, and Settings UI require no changes.

## Storage

| What | Where | Survives update? | Survives clear data? |
|------|-------|-----------------|---------------------|
| EPUB files | `filesDir/epubs/` | ✓ | ✗ |
| Audio cache | `filesDir/audio/` | ✓ | ✗ |
| Reading positions | Room database | ✓ | ✗ |
| TTS credentials | DataStore | ✓ | ✗ |
