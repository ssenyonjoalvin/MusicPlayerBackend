# music-player

A self-hosted **audio-only download API**. Paste a YouTube share link, get back a
playable audio file and a persistent library your mobile app can stream, search
and cache.

Built for one job: replace a music subscription with your own library, without
paying per month and without managing servers beyond a single process.

---

## How it works, and why

YouTube's internals (player JS, cipher signatures, format manifests) change
constantly. Reimplementing them is a losing battle. So this service does not try.
It **wraps [`yt-dlp`](https://github.com/yt-dlp/yt-dlp)** — the community tool
that stays current — and puts a proper application around it:

- tolerant link parsing (real share-sheet input, not just clean URLs)
- metadata resolution before the response returns
- live progress reporting
- a persistent, searchable library
- HTTP byte-range streaming that mobile players actually accept

The trade-off is honest: you depend on an external binary. In exchange you get
software that keeps working when YouTube changes something.

---

## Requirements

| | |
|---|---|
| JDK | 21+ (built and tested on 25) |
| Maven | 3.9+ |
| Python | 3.9+ (only to provide `yt-dlp`) |
| ffmpeg **and** ffprobe | only for MP3; **not needed for M4A** |

### Install the tools

```bash
# yt-dlp (required)
pip install -U yt-dlp
# or: winget install yt-dlp.yt-dlp

# ffmpeg + ffprobe (optional, needed only for MP3)
winget install Gyan.FFmpeg
# or: choco install ffmpeg
```

Both are **auto-detected** at startup. If you keep them somewhere unusual, point
the app at them:

```bash
export APP_TOOLS_FFMPEG_PATH=/opt/ffmpeg/bin   # the directory holding both executables
export APP_TOOLS_YT_DLP_PATH=/usr/local/bin/yt-dlp
```

> **MP3 needs `ffprobe` too.** yt-dlp uses it for post-processing, so a lone
> `ffmpeg` binary is not enough. The bundled Python wheel `imageio-ffmpeg` ships
> `ffmpeg` under a versioned name and no `ffprobe`, so it does **not** satisfy
> the MP3 path. `/api/v1/system/diagnostics` tells you the truth:

```json
{ "ytDlp": "yt-dlp", "hasFfmpeg": true, "hasFfprobe": false, "mp3Available": false }
```

---

## Run

```bash
mvn spring-boot:run
# or build a single self-contained jar
mvn clean package
java -jar target/music-player-1.0.0-SNAPSHOT.jar
```

API on `http://localhost:8080`. Check it came up:

```bash
curl localhost:8080/api/v1/system/diagnostics
```

---

## API

All responses are JSON. Errors always use the same envelope, so a client needs
exactly one error parser:

```json
{ "code": "video_private", "message": "This video is private.",
  "timestamp": "2026-01-01T00:00:00Z", "fieldErrors": {} }
```

`code` is stable — branch on it, never on `message`.

### Submit a download

```bash
curl -X POST localhost:8080/api/v1/downloads \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://youtu.be/dQw4w9WgXcQ","format":"m4a"}'
```

```json
{
  "id": "bcf40caa-d0ec-43d0-9c3e-00b8b92bf40f",
  "title": "Rick Astley - Never Gonna Give You Up (Official Video)",
  "artist": "Rick Astley",
  "durationSeconds": 213,
  "format": "m4a",
  "status": "QUEUED",
  "progress": { "percent": 0, "downloadedBytes": null, "totalBytes": null,
                "speedBytesPerSecond": null, "etaSeconds": null },
  "streamUrl": null, "fileUrl": null,
  "coverUrl": "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp"
}
```

`url` accepts anything a share sheet produces: `watch?v=`, `youtu.be/`,
`/shorts/`, `/embed/`, `/live/`, a bare 11-character id, or any of those wrapped
in sharing text.

- `202 Accepted` — newly queued
- `200 OK` — already known (queued, downloading, or in the library)

**Submission is idempotent per video id.** Re-pasting a link never creates a
duplicate, which matters because share sheets fire repeatedly and users
double-tap. Re-pasting a *failed* link silently retries it.

### Poll, retry, cancel

| | |
|---|---|
| `GET /api/v1/downloads/{id}` | status + live progress |
| `POST /api/v1/downloads/{id}/retry?format=m4a` | re-queue; may switch format |
| `POST /api/v1/downloads/{id}/cancel` | kill the in-flight download |

### Library

| | |
|---|---|
| `GET /api/v1/tracks?page=0&size=20` | newest first |
| `GET /api/v1/tracks?q=never` | search title / artist / album |
| `GET /api/v1/tracks?status=FAILED` | filter by status |
| `GET /api/v1/tracks/{id}` | one track |
| `DELETE /api/v1/tracks/{id}` | remove track **and** its files |

### Playback and download

| | |
|---|---|
| `GET /api/v1/tracks/{id}/stream` | inline audio, supports `Range` |
| `GET /api/v1/tracks/{id}/file` | same bytes as an attachment |
| `GET /api/v1/tracks/{id}/cover` | artwork, 302 to YouTube if not cached |

`stream` implements `Range` properly — `206`, `Content-Range`, `Accept-Ranges`,
and `416` past EOF. This is not optional: **ExoPlayer and AVPlayer both open
media with a ranged request**, and without it they fail or refuse to seek.

### Status values

`QUEUED` → `DOWNLOADING` → `READY` | `FAILED`

A download interrupted by a server restart is reconciled to `FAILED` on the next
boot, so the UI never shows a bar that will never move.

---

## Design notes

**Progress lives in memory, not the database.** yt-dlp emits progress several
times a second. Persisting each tick is pure write amplification for data that
is worthless a second later, so live progress sits in a `ConcurrentHashMap` and
is merged into the status response. Only state transitions are persisted.

**The download starts after the transaction commits.** The work is dispatched via
an `AFTER_COMMIT` event, not a direct call. Otherwise the async worker can look
for a row that is not committed yet, fail to find it, and silently drop the job.

**Long operations hold no transaction.** Each state change is its own
`REQUIRES_NEW` transaction in a separate bean, so a 30-minute download never
holds a database transaction open.

**Only YouTube hosts are accepted.** The URL is handed to a subprocess, so an
open redirector or a `file://` URL must never reach it. Host allow-listing is a
security boundary, not validation polish.

**No JSON parsing of yt-dlp output.** Metadata is read from `--print` templates
emitting `KEY=value` lines. The full info JSON is tens of kilobytes with a schema
that shifts between releases; this contract is a handful of stable fields and
needs no parser.

**M4A is the default format.** It is a byte-for-byte copy of YouTube's native
audio stream: no re-encode, no quality loss, no ffmpeg. MP3 re-encodes for
legacy hardware compatibility.

---

## Configuration

Override anything with environment variables or `application.yml`.

| Key | Default | Notes |
|---|---|---|
| `app.storage.root` | `data` | audio, covers, scratch space |
| `app.storage.database` | `./data/library` | H2 file (leading `./` required) |
| `app.download.max-concurrent` | `2` | parallel yt-dlp processes |
| `app.download.timeout` | `30m` | per-download ceiling |
| `app.download.retries` | `3` | network retries |
| `app.cors.allowed-origins` | `*` | **pin this in production** |

---

## Layout

```
data/
  audio/   <uuid>.m4a        finished, servable
  covers/  <uuid>.jpg        cached artwork
  work/    <uuid>/           per-attempt scratch, incl. .part files
  library.mv.db              H2 index
```

Only *relative* paths are stored, so the library can be moved or mounted
elsewhere without rewriting rows. Every path resolved from the database is
re-validated against the library root, so a traversal bug cannot become a
file-disclosure bug.

## Tests

```bash
mvn test
```

69 tests covering the parts where correctness is not obvious: share-link shapes,
`NA` handling in yt-dlp's progress protocol, path-traversal defence, and
filesystem-safe filename generation — plus two integration suites that run the
real HTTP stack for byte-range delivery and download dispatch.

Two of these exist because they caught actual bugs during development, and are
worth keeping green:

- `TrackStreamingIntegrationTest` — ranged requests used to return **500**,
  because `ResourceRegionHttpMessageConverter` only advertises
  `application/octet-stream` and rejects a preset `audio/mp4`.
- `DownloadDispatchIntegrationTest` — a track once sat in `QUEUED` forever with
  no error, because `submit` was made non-transactional and the
  `AFTER_COMMIT` listener stopped firing.

---

## Legal note

This tool downloads content from YouTube. Whether that is permitted depends on
your jurisdiction, your account, and the content itself — and redistributing or
commercially using downloaded tracks is a different question from personal
listening. Downloading material you do not own or have permission to use may
breach YouTube's Terms of Service and copyright law. Check your own situation
before deploying this anywhere other than your own machine.
#   M u s i c P l a y e r B a c k e n d  
 