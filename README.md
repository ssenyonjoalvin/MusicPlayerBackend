# Music Player

A self-hosted, audio-only music library and streaming API built with Java and Spring Boot.

The service accepts YouTube URLs, downloads the corresponding audio through `yt-dlp`, stores it in a persistent local library, and exposes a REST API for downloading, searching, streaming, and managing tracks.

It is designed for personal deployments and mobile music-player applications where the backend should run as a single, self-contained service without subscription-based infrastructure.

## Features

* YouTube URL and share-link parsing
* Audio extraction through `yt-dlp`
* M4A and MP3 output
* Persistent local music library
* Track metadata extraction
* Download progress tracking
* Download retry and cancellation
* Search by title, artist, and album
* HTTP byte-range streaming
* Artwork caching
* REST API with consistent JSON error responses
* Idempotent download requests
* H2 file-based database
* Automatic recovery of interrupted downloads
* Configurable download concurrency and timeouts
* Security-focused URL and filesystem validation
* No external database server required

## Architecture

The application deliberately avoids implementing YouTube's extraction logic itself.

YouTube's player implementation, signatures, manifests, and delivery mechanisms change frequently. Maintaining a custom extractor would therefore introduce significant maintenance overhead.

Instead, the service delegates media extraction to [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) and provides an application layer around it.

The backend is responsible for:

1. Validating and normalizing incoming URLs.
2. Resolving video metadata.
3. Managing download jobs.
4. Tracking download state and progress.
5. Persisting the music library.
6. Managing downloaded files and artwork.
7. Providing searchable library endpoints.
8. Serving audio through HTTP range requests.
9. Providing consistent API responses and error handling.

This approach keeps the application focused on library management and media delivery while allowing `yt-dlp` to handle the continuously changing YouTube extraction layer.

## Technology Stack

| Component        | Technology          |
| ---------------- | ------------------- |
| Language         | Java 21+            |
| Framework        | Spring Boot         |
| Build Tool       | Maven 3.9+          |
| Database         | H2                  |
| Media Extraction | yt-dlp              |
| Audio Processing | FFmpeg              |
| Persistence      | JPA / Hibernate     |
| API              | REST / JSON         |
| Streaming        | HTTP Range Requests |

## Requirements

| Requirement | Version / Details |
| ----------- | ----------------- |
| JDK         | 21+               |
| Maven       | 3.9+              |
| Python      | 3.9+              |
| yt-dlp      | Required          |
| FFmpeg      | Required for MP3  |
| FFprobe     | Required for MP3  |

The project is built and tested with JDK 25.

M4A downloads do not require FFmpeg because the audio stream can be stored without re-encoding.

MP3 downloads require both `ffmpeg` and `ffprobe` for post-processing.

## Installation

### 1. Install yt-dlp

Using Python:

```bash
pip install -U yt-dlp
```

Windows:

```bash
winget install yt-dlp.yt-dlp
```

### 2. Install FFmpeg

FFmpeg is only required when MP3 output is enabled.

Windows:

```bash
winget install Gyan.FFmpeg
```

or:

```bash
choco install ffmpeg
```

The installation must provide both:

```text
ffmpeg
ffprobe
```

A standalone `ffmpeg` executable without `ffprobe` is not sufficient for MP3 processing.

For example, the `imageio-ffmpeg` Python package does not satisfy this requirement because it provides FFmpeg under a versioned executable name but does not provide `ffprobe`.

### 3. Verify the installation

The application exposes a diagnostic endpoint:

```bash
curl http://localhost:8080/api/v1/system/diagnostics
```

Example response:

```json
{
  "ytDlp": "yt-dlp",
  "hasFfmpeg": true,
  "hasFfprobe": true,
  "mp3Available": true
}
```

## Configuration

The application automatically searches for `yt-dlp` and FFmpeg on the system `PATH`.

If the tools are installed in non-standard locations, configure them explicitly.

Linux/macOS:

```bash
export APP_TOOLS_FFMPEG_PATH=/opt/ffmpeg/bin
export APP_TOOLS_YT_DLP_PATH=/usr/local/bin/yt-dlp
```

The FFmpeg path should point to the directory containing both `ffmpeg` and `ffprobe`.

## Running the Application

### Development

```bash
mvn spring-boot:run
```

### Build

```bash
mvn clean package
```

### Run the packaged JAR

```bash
java -jar target/music-player-1.0.0-SNAPSHOT.jar
```

The API will be available at:

```text
http://localhost:8080
```

Verify that the application is running:

```bash
curl http://localhost:8080/api/v1/system/diagnostics
```

## API

The API uses JSON for requests and responses.

Errors use a consistent response structure:

```json
{
  "code": "video_private",
  "message": "This video is private.",
  "timestamp": "2026-01-01T00:00:00Z",
  "fieldErrors": {}
}
```

The `code` field is stable and should be used by clients for error handling.

Clients should not branch on the human-readable `message` field.

---

## Downloads

### Submit a Download

```http
POST /api/v1/downloads
Content-Type: application/json
```

Example:

```bash
curl -X POST http://localhost:8080/api/v1/downloads \
  -H "Content-Type: application/json" \
  -d '{"url":"https://youtu.be/dQw4w9WgXcQ","format":"m4a"}'
```

Example response:

```json
{
  "id": "bcf40caa-d0ec-43d0-9c3e-00b8b92bf40f",
  "title": "Rick Astley - Never Gonna Give You Up (Official Video)",
  "artist": "Rick Astley",
  "durationSeconds": 213,
  "format": "m4a",
  "status": "QUEUED",
  "progress": {
    "percent": 0,
    "downloadedBytes": null,
    "totalBytes": null,
    "speedBytesPerSecond": null,
    "etaSeconds": null
  },
  "streamUrl": null,
  "fileUrl": null,
  "coverUrl": "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp"
}
```

### Supported URL Formats

The `url` field accepts common YouTube URL and share formats, including:

```text
https://www.youtube.com/watch?v=...
https://youtu.be/...
https://www.youtube.com/shorts/...
https://www.youtube.com/embed/...
https://www.youtube.com/live/...
```

It also accepts a bare YouTube video ID and URLs embedded within share-sheet text.

### Response Status

| HTTP Status    | Meaning                                            |
| -------------- | -------------------------------------------------- |
| `202 Accepted` | A new download was queued                          |
| `200 OK`       | The video already exists or has an active download |

Download submission is idempotent per YouTube video ID.

Submitting the same video multiple times does not create duplicate library entries.

If a previous attempt failed, submitting the same video again automatically retries the download.

## Download Management

| Method | Endpoint                                  | Description               |
| ------ | ----------------------------------------- | ------------------------- |
| `GET`  | `/api/v1/downloads/{id}`                  | Get status and progress   |
| `POST` | `/api/v1/downloads/{id}/retry?format=m4a` | Retry a download          |
| `POST` | `/api/v1/downloads/{id}/cancel`           | Cancel an active download |

The retry endpoint can also be used to change the requested output format.

## Download States

Downloads follow the following lifecycle:

```text
QUEUED
   |
   v
DOWNLOADING
   |
   +-------> READY
   |
   +-------> FAILED
```

If a download is interrupted by an application restart, it is reconciled to `FAILED` during application startup.

This prevents the client from displaying a download that remains permanently stuck in `QUEUED` or `DOWNLOADING`.

## Music Library

| Method   | Endpoint                        | Description                  |
| -------- | ------------------------------- | ---------------------------- |
| `GET`    | `/api/v1/tracks?page=0&size=20` | List tracks                  |
| `GET`    | `/api/v1/tracks?q=never`        | Search tracks                |
| `GET`    | `/api/v1/tracks?status=FAILED`  | Filter tracks                |
| `GET`    | `/api/v1/tracks/{id}`           | Get a track                  |
| `DELETE` | `/api/v1/tracks/{id}`           | Delete a track and its files |

Tracks are returned newest first by default.

Search currently supports:

* Title
* Artist
* Album

## Playback and File Access

| Method | Endpoint                     | Description                     |
| ------ | ---------------------------- | ------------------------------- |
| `GET`  | `/api/v1/tracks/{id}/stream` | Stream audio                    |
| `GET`  | `/api/v1/tracks/{id}/file`   | Download audio as an attachment |
| `GET`  | `/api/v1/tracks/{id}/cover`  | Retrieve artwork                |

### HTTP Range Streaming

The streaming endpoint supports HTTP byte ranges.

Supported behavior includes:

```text
206 Partial Content
Content-Range
Accept-Ranges
416 Range Not Satisfiable
```

This is important for mobile media players because players such as ExoPlayer and AVPlayer commonly request media using ranged HTTP requests.

Range support allows clients to:

* Start playback without downloading the entire file.
* Seek within a track.
* Resume playback efficiently.
* Request only the required portion of a file.

## Storage Layout

The default storage structure is:

```text
data/
├── audio/
│   └── <uuid>.m4a
├── covers/
│   └── <uuid>.jpg
├── work/
│   └── <uuid>/
│       └── temporary files
└── library.mv.db
```

### Storage Design

Only relative paths are stored in the database.

This allows the entire library to be moved or mounted at a different filesystem location without rewriting database records.

Paths retrieved from the database are resolved and validated against the configured library root before filesystem access.

This provides an additional defense against path traversal and unintended file disclosure.

## Configuration Reference

Configuration can be supplied through `application.yml` or environment variables.

| Configuration                 | Default          | Description                                            |
| ----------------------------- | ---------------- | ------------------------------------------------------ |
| `app.storage.root`            | `data`           | Root directory for audio, artwork, and temporary files |
| `app.storage.database`        | `./data/library` | H2 database location                                   |
| `app.download.max-concurrent` | `2`              | Maximum simultaneous yt-dlp processes                  |
| `app.download.timeout`        | `30m`            | Maximum duration of an individual download             |
| `app.download.retries`        | `3`              | Number of network retries                              |
| `app.cors.allowed-origins`    | `*`              | Allowed CORS origins                                   |

For production deployments, replace:

```text
*
```

with the specific origins that are allowed to access the API.

## Engineering Design

### In-Memory Download Progress

Download progress is maintained in memory rather than being persisted to the database.

`yt-dlp` can emit progress updates several times per second. Persisting every update w
