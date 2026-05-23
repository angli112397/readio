# Readio

An Android EPUB audiobook reader with a drum-roller chunk wheel: text and audio stay in sync, sentence by sentence. Tap any chunk to get an instant offline translation.

## What it does

Import an EPUB, press play. The content is split into semantically coherent chunks — respecting sentence boundaries, bracket pairs, and language-specific rules — and read aloud via Volcengine Doubao TTS (async, offline-cached) or the on-device system TTS (streaming, real-time). The chunk wheel scrolls to keep the current chunk centred while audio advances automatically. Tap any centred chunk to translate it instantly via ML Kit offline translation.

## Features

- **EPUB import** — parses OPF spine + NCX for chapter structure; normalises Unicode whitespace and invisible characters before chunking
- **Language-aware chunking** — `chunkSize=150` means ~150 CJK characters or ~37 English words, keeping visual density consistent across languages. Sentence splitting respects paired brackets (`「」`, `""`, `（）`, …) so quoted dialogue is never broken mid-quote
- **Two-pass chunker** — oversized sentences are first expanded into atoms (comma-split → hard-split), then all atoms are greedily bin-packed into chunks. Comma-split tails naturally combine with the following sentence instead of being stranded in their own chunk
- **Chunk wheel** — drum-roller UI with scale/alpha perspective, haptic feedback on each tick, and per-chunk text alignment (Justify for CJK, Start for Latin)
- **Offline translation** — tap the centred chunk; ML Kit translates it on-device. Toggle tap to dismiss. Supports 简体中文, English, 日本語, 한국어 as target languages
- **Dual TTS providers**
  - *Volcengine Doubao seed-tts-2.0* — async cloud synthesis; entire chapter returned as one MP3 with per-sentence timestamps. Audio cached to `filesDir/audio/` and survives app restarts. Voices: Vivi 2.0 (zh-CN) and Tim (en)
  - *System TTS* — real-time on-device synthesis; no API key required; supports zh-CN and en-US
- **Task ID persistence** — Volcengine synthesis is split into two explicit user actions: *Submit task* (costs quota) and *Fetch result* (free query). The task ID is written to disk so a chapter can be recovered after app restarts, network loss, or server-side delays without spending new quota
- **Chapter download manager** — download chapters individually or in bulk from the chapter list. Downloads are app-scoped and survive navigation and screen rotation
- **Chunk sync** — for Volcengine (SingleFile): a 150 ms position-polling coroutine maps `currentPosition` → sentence timestamp → display chunk. For system TTS (PerSentence): `onMediaItemTransition` fires per playlist item. Both use the same `sentenceToChunk` mapping in `ReaderViewModel`
- **Background playback** — `MediaSessionService` keeps audio alive when the app is minimised; lock screen and notification controls work out of the box
- **Reading preferences** — font size, line height, chunk size, background theme (Default / Warm / Sepia / Night), translation target language; all persisted via DataStore

## Setup

### Volcengine Doubao TTS (recommended)

1. Create a [Volcengine account](https://console.volcengine.com) and enable the Speech Synthesis (语音合成) service
2. Obtain your App ID and Access Token from the console
3. In **Settings**, select *火山引擎豆包*, paste your credentials, choose a voice, and press Save
4. On the chapter list screen, tap the download icon on any chapter to submit a synthesis task. Once submitted, tap the hourglass icon to fetch the result. The task ID is saved — you can close the app and fetch later

> First-time synthesis of a 45 000-character chapter takes ~10 minutes on Volcengine's servers. The app polls adaptively and shows a progress indicator. You can also paste a task ID obtained externally to retrieve a completed result without spending new quota.

### System TTS (offline, no account)

Select **系统 TTS（本地）** in Settings — no key required. Synthesis is real-time; audio is not cached to disk.

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
  reader/        ParagraphWheel, ReaderScreen, ReaderViewModel
  chapters/      Chapter list screen + ViewModel, AudioDownloadManager bridge
  settings/      Settings screen + ViewModel, TtsVoiceCatalog

domain/
  model/         EpubBook, Chapter, Chunk, Sentence, Language,
                 TtsConfig, TtsProvider, ChapterAudio, AudioSource,
                 SentenceTimestamp, ChapterAudioStatus, ReadingPreferences, …
  repository/    EpubRepository, AudioRepository (+ TtsTaskResult),
                 SettingsRepository, VocabularyRepository, ReadingProgressRepository
  service/       TextChunker (ChunkedText), PunctuationTable
  manager/       AudioDownloadManager (app-scoped, SupervisorJob)
  usecase/       GetReadingPositionUseCase, PrepareChapterAudioUseCase,
                 DownloadChapterAudioUseCase

data/
  epub/          EpubParser (ZipFile + Jsoup)
  audio/         TtsEngine interface, AndroidTtsEngine, VolcengineEngine,
                 TtsTextCleaner, AudioRepositoryImpl
  db/            Room database, DAOs, entities
  repository/    EpubRepositoryImpl, SettingsRepositoryImpl,
                 VocabularyRepositoryImpl, ReadingProgressRepositoryImpl
```

### Key design decisions

**Sentence-level synthesis atoms** — the chapter is chunked in two layers:
- *Sentence* (`Sentence.indexInChapter`) — the synthesis atom; one audio file or one timestamp range per sentence
- *Chunk* (`Chunk.firstSentenceIndex`) — the display unit; one wheel item, may span multiple sentences

`sentenceToChunk[sentenceIndex]` is the single bridge between audio time and display position. `ReaderViewModel` uses it identically regardless of `AudioSource` type.

**Two audio source shapes**
- `AudioSource.PerSentence` (System TTS) — a list of WAV files in `cacheDir`, synthesised on demand. ExoPlayer playlist = sentence files; `onMediaItemTransition` drives chunk sync
- `AudioSource.SingleFile` (Volcengine) — one MP3 for the entire chapter plus a `List<SentenceTimestamp>`. ExoPlayer has one item; a 150 ms position-polling coroutine maps `currentPosition` → timestamp → chunk

**Cache key = `provider|voice`** — chunk size is explicitly excluded. Changing the display chunk size only rearranges the UI; it never invalidates downloaded audio.

**Volcengine async task flow** — submission and retrieval are two separate, user-triggered actions:
1. `AudioRepository.submitTask()` → saves `task.id` to `filesDir/audio/{chapterId}/task.id`
2. `AudioRepository.fetchTaskResult()` → queries once (no polling loop); returns `TtsTaskResult.Pending / Complete / Failed`
3. `AudioDownloadManager` exposes a `HasTaskId` chapter status so the UI can show a hourglass and let the user retry at will

**Two-pass TextChunker** — `mergeSentences()` runs two passes:
1. *Expand*: sentences > `maxChars` are decomposed via comma-split → hard-split, producing atoms all ≤ `maxChars`
2. *Pack*: greedy bin-packing over atoms — comma-split tails can combine with following sentences, reducing isolated micro-chunks

**Volcengine sentence→chunk mapping** — submission text is the concatenated cleaned chunks; `prepareCleanedChapter()` builds a `charToChunk` integer array in one pass. After the API returns sentence texts, `mapSentencesToChunks()` does a forward `indexOf` scan to assign each sentence to a chunk in O(n) time.

**Text cleaning** — `TtsTextCleaner.cleanForTts()` is shared between both engines: strips visual-only punctuation, collapses CJK inter-character spaces, deduplicates repeated pause marks. Applied before submission (reduces Volcengine billed characters) and before local synthesis.

**Language-aware chunking** — `TextChunker` uses `LATIN_WORD_WEIGHT = 4`: one English word counts as 4 units, one CJK character counts as 1 unit. A single `chunkSize` slider controls both languages at consistent visual density.

**Bracket-respecting sentence splitting** — `mergeBracketSpans` re-joins fragments split inside an unclosed bracket pair. English `.` is a sentence boundary only when followed by whitespace + an uppercase letter or opening quote.

**Pause semantics** — both manual pause (play/pause button) and browse-to-stop (scroll gesture) call `stopAndClearAudio()`. Both result in an identical state: player stopped, synthesis cancelled, position preserved. Re-pressing play always re-synthesises from the current chunk.

**TTS extensibility** — to add a provider that returns audio + timestamps (e.g. MiniMax T2A):
1. Add an entry to `TtsProvider`
2. Add credentials fields to `TtsConfig`
3. Implement the engine (for async: match `VolcengineEngine`'s `submitOnly / queryOnce / downloadResult` surface)
4. Route in `AudioRepositoryImpl`
5. Add voices to `TtsVoiceCatalog`

## Storage

| What | Where | Survives update | Survives clear data |
|---|---|---|---|
| EPUB files | `filesDir/epubs/` | ✓ | ✗ |
| Volcengine audio + metadata | `filesDir/audio/{chapterId}/audio.mp3` + `meta.json` | ✓ | ✗ |
| Volcengine task IDs | `filesDir/audio/{chapterId}/task.id` | ✓ | ✗ |
| System TTS temp files | `cacheDir/tts_{chapterId}_{i}.wav` | ✗ (ephemeral) | ✗ |
| Reading positions | Room database | ✓ | ✗ |
| TTS credentials | DataStore | ✓ | ✗ |
| Reading preferences | DataStore | ✓ | ✗ |
