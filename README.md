# Readio

An Android EPUB audiobook reader with a drum-roller chunk wheel: text and audio stay in sync, sentence by sentence. Tap any chunk to get an instant offline translation.

## What it does

Import an EPUB, press play. The content is split into semantically coherent chunks — respecting sentence boundaries, bracket pairs, and language-specific rules — and read aloud via one of three TTS backends. The chunk wheel scrolls to keep the current chunk centred while audio advances automatically. Tap any centred chunk to translate it instantly via ML Kit offline translation.

## Features

- **EPUB import** — parses OPF spine + NCX for chapter structure; normalises Unicode whitespace and invisible characters before chunking
- **Language-aware chunking** — `chunkSize=150` means ~150 CJK characters or ~37 English words. Sentence splitting respects paired brackets (`「」`, `""`, `（）`, …) so quoted dialogue is never broken mid-quote
- **Two-pass chunker** — oversized sentences are first expanded into atoms (comma-split → hard-split), then all atoms are greedily bin-packed into chunks
- **Chunk wheel** — drum-roller UI with scale/alpha perspective, haptic feedback on each tick, and per-chunk text alignment (Justify for CJK, Start for Latin)
- **Offline translation** — tap the centred chunk; ML Kit translates on-device. Supports 简体中文, English, 日本語, 한국어 as target languages
- **Unified player bar** — a single button in the reader handles every state: real-time play/pause for local TTS; download → cache → play → pause for batch TTS. Long-press shows a context-appropriate action (cancel download, delete audio, or clear a failed task)
- **Three TTS providers**
  - *GPT-SoVITS (readio-tts)* — self-hosted GPU inference server; full async job lifecycle with idempotency (POST → GET status → GET audio + manifest → DELETE). Audio cached with per-sentence timestamps for sub-chunk seek precision
  - *Volcengine Doubao seed-tts-2.0* — async cloud synthesis; chapter returned as one MP3 with per-sentence timestamps; cached to `filesDir`
  - *System TTS* — real-time on-device synthesis; no API key required
- **Batch download manager** — `AudioDownloadManager` tracks per-chapter synthesis status as a state machine (`NotDownloaded → Downloading → Downloaded`, with `HasTaskId` and `Error` states). Singleton with `SupervisorJob`; survives navigation and screen rotation
- **Chunk sync** — for SingleFile backends (GPT-SoVITS, Volcengine): a 150 ms position-polling coroutine maps `currentPosition` → sentence timestamp → display chunk. For PerSentence (system TTS): `onMediaItemTransition` fires per playlist item
- **Background playback** — `MediaSessionService` keeps audio alive when the app is minimised; lock screen and notification controls work out of the box
- **Reading preferences** — font size, line height, chunk size, background theme (Default / Warm / Sepia / Night), translation target language; all persisted via DataStore
- **Per-book TTS override** — each book can use a different provider and voice independently of the global setting

## Setup

### GPT-SoVITS (local GPU, recommended for best quality)

Requires the [readio-tts](https://github.com/angli112397/readio-tts) server running on your local machine or LAN.

1. Start `readio-tts` and note its address (e.g. `http://192.168.1.10:8000`)
2. Place your reference audio under `references/gpt/<voice-id>/` on the server
3. In **Settings**, select *GPT-SoVITS*, paste the server URL and your Voice ID
4. Open a chapter in the reader and tap the cloud-download button to begin synthesis. The button transitions: **cloud → hourglass → progress ring → play**
5. Tap the hourglass at any time to re-poll job status; long-press to cancel

> The server uses an idempotency key (the chapter cache path) so the same chapter is never double-submitted. Completed jobs are automatically deleted from the server after Android downloads the audio, freeing the slot for the next chapter.

### Volcengine Doubao TTS

1. Create a [Volcengine account](https://console.volcengine.com) and enable Speech Synthesis (语音合成)
2. Obtain your App ID and Access Token
3. In **Settings**, select *火山引擎豆包*, paste credentials, choose a voice, and press Save
4. Open a chapter in the reader and tap the cloud-download button to start

### System TTS (offline, no account)

Select **系统 TTS（本地）** in Settings. Synthesis is real-time; audio is not cached to disk.

## Player bar state machine

```
[Realtime provider]
  Stopped → Play button → Playing → Pause button → Stopped

[Batch provider]
  NotDownloaded ──tap──▶ Downloading ──progress──▶ HasTaskId ──tap──▶ Downloading
        ▲                                               │ (server done on re-poll)
        │                                               ▼
      Error ◀──(server fails)──────────────────── Downloaded ──tap──▶ Playing
        │
      tap ──▶ auto-clears stale job, re-submits fresh
   long-press ──▶ "清除失败记录" dialog → NotDownloaded
```

Long-press actions by state:

| State | Long-press result |
|---|---|
| `HasTaskId` / `Downloading` | "取消合成" dialog — cancels job and deletes server record |
| `Downloaded` | "删除音频" dialog — removes cached audio |
| `Error` | "清除失败记录" dialog — deletes server job and resets to NotDownloaded |

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
  reader/        ChunkWheel, ReaderScreen, ReaderViewModel
  chapters/      Chapter list screen + ViewModel (navigation only — no audio controls)
  settings/      Settings screen + ViewModel, TtsVoiceCatalog

domain/
  model/         EpubBook, Chapter, Chunk, Sentence, Language,
                 TtsConfig, TtsProvider, ChapterAudio, AudioSource,
                 SentenceTiming, ChapterAudioStatus, ReadingPreferences, …
  repository/    EpubRepository, SettingsRepository,
                 VocabularyRepository, ReadingProgressRepository
  engine/        BatchTtsEngine (interface), RealtimeTtsEngine (interface),
                 SynthesisManifest, SentenceTiming, BatchSynthesisEvent
  manager/       AudioDownloadManager (app-scoped singleton, SupervisorJob)
  usecase/       GetReadingPositionUseCase, PrepareChapterAudioUseCase
  service/       TextChunker (ChunkedText), PunctuationTable

data/
  epub/          EpubParser (ZipFile + Jsoup), EpubRepositoryImpl
  audio/         GptSoVitsEngine, VolcengineEngine, AndroidTtsEngine,
                 TtsTextCleaner, AudioRepositoryImpl
  audio/cache/   AudioCache (interface), AudioCacheImpl
  db/            Room database, DAOs, entities
  repository/    SettingsRepositoryImpl, VocabularyRepositoryImpl,
                 ReadingProgressRepositoryImpl
```

### Key design decisions

**Sentence-level synthesis atoms** — the chapter is chunked in two layers:
- *Sentence* (`Sentence.indexInChapter`) — the synthesis atom; maps to one audio timestamp range per sentence
- *Chunk* (`Chunk.firstSentenceIndex`) — the display unit; one wheel item, may span multiple sentences

`sentenceToChunk[sentenceIndex]` is the single bridge between audio time and display position. `ReaderViewModel` uses it identically regardless of `AudioSource` type.

**Two audio source shapes**
- `AudioSource.PerSentence` (System TTS) — a list of WAV files synthesised on demand. ExoPlayer playlist = sentence files; `onMediaItemTransition` drives chunk sync
- `AudioSource.SingleFile` (GPT-SoVITS, Volcengine) — one audio file for the entire chapter plus a `List<SentenceTiming>`. A 150 ms position-polling coroutine maps `currentPosition` → timestamp → chunk

**Cache key = `provider|voice`** — chunk size is explicitly excluded. Changing the display chunk size only rearranges the UI; it never invalidates downloaded audio.

**GPT-SoVITS full async lifecycle**
1. `POST /v1/jobs` with `Idempotency-Key = cacheDir.absolutePath` → server returns `job_id` (or the existing job for the same key)
2. `GET /v1/jobs/{job_id}` → `{ state: "queued"|"running"|"succeeded"|"failed" }`
3. `GET /v1/jobs/{job_id}/audio` → WAV bytes streamed to `cacheDir/audio.wav`
4. `GET /v1/jobs/{job_id}/manifest` → per-sentence `{ begin_ms, end_ms }` written as local manifest
5. `DELETE /v1/jobs/{job_id}` → frees the server record and idempotency key slot

DELETE is called in two places: after a successful download (step 5), and in `clearChapter(chapterId, config)` before wiping local files — ensuring the next POST for the same chapter creates a genuinely fresh job.

If a re-poll finds a job in `failed`/`cancelled` state, `GptSoVitsEngine` automatically DELETEs it and re-submits, so tapping "retry" always works.

**`AudioDownloadManager` state machine** — singleton that owns all in-flight synthesis coroutines via a `ConcurrentHashMap<chapterId, Job>`. Exposes a `StateFlow<DownloadManagerState>` with a per-chapter `ChapterAudioStatus`. Config changes cancel all jobs and clear statuses so the new provider starts with a clean slate.

**`clearChapter(chapterId, config)` interface method** — `BatchTtsEngine` exposes an optional config-aware overload so engines with server-side state (GPT-SoVITS) can DELETE the associated job before removing local files. The default implementation ignores `config` and delegates to the no-arg `clearChapter(chapterId)`.

**Two-pass TextChunker** — `mergeSentences()` runs two passes:
1. *Expand*: sentences > `maxChars` are decomposed via comma-split → hard-split, producing atoms all ≤ `maxChars`
2. *Pack*: greedy bin-packing over atoms — comma-split tails combine with following sentences, reducing isolated micro-chunks

**Text cleaning** — `TtsTextCleaner.cleanForTts()` is shared between all engines: strips visual-only punctuation, collapses CJK inter-character spaces, deduplicates repeated pause marks. Applied before submission (reduces billed characters) and before local synthesis.

**Language-aware chunking** — `TextChunker` uses `LATIN_WORD_WEIGHT = 4`: one English word counts as 4 units, one CJK character counts as 1 unit. A single `chunkSize` slider controls both languages at consistent visual density.

**TTS extensibility** — to add a batch provider (e.g. MiniMax T2A):
1. Add an entry to `TtsProvider`
2. Add credentials fields to `TtsConfig`
3. Implement `BatchTtsEngine` (model `GptSoVitsEngine` for async submit-poll-download)
4. Bind in `TtsEngineModule` with `@Binds @Singleton @IntoSet`
5. Add voices to `TtsVoiceCatalog`

## Storage

| What | Where | Survives update | Survives clear data |
|---|---|---|---|
| EPUB files | `filesDir/epubs/` | ✓ | ✗ |
| GPT-SoVITS audio | `filesDir/audio_cache/{chapterId}/{cacheKey}/audio.wav` | ✓ | ✗ |
| GPT-SoVITS manifest | `filesDir/audio_cache/{chapterId}/{cacheKey}/manifest.json` | ✓ | ✗ |
| Pending task IDs | `filesDir/audio_cache/{chapterId}/{cacheKey}/task.id` | ✓ | ✗ |
| Volcengine audio | `filesDir/audio_cache/{chapterId}/{cacheKey}/audio.mp3` | ✓ | ✗ |
| System TTS temp files | `cacheDir/tts_{chapterId}_{i}.wav` | ✗ (ephemeral) | ✗ |
| Reading positions | Room database | ✓ | ✗ |
| TTS credentials | DataStore | ✓ | ✗ |
| Reading preferences | DataStore | ✓ | ✗ |
