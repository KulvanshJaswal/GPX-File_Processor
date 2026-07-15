# GPX File Processor

A distributed, asynchronous GPX file processing system. Upload a GPX file from a hike and get back a PDF report with distance, elevation, difficulty, weather, and wildlife data — processed through a Spring Boot + RabbitMQ job pipeline rather than a single blocking request.

Built as a portfolio project to demonstrate distributed systems design, async processing, real file-format parsing, and system design thinking — not just a CRUD app with a database.

## Architecture

**Happy path:**

1. The React frontend uploads a GPX file to the Spring Boot upload endpoint.
2. The upload endpoint validates the file (fail-fast — bad input returns `400` immediately, never touches a queue), saves the raw file to MinIO, creates a `jobs` row in Postgres with status `QUEUED`, and publishes a message to the **Main Topic Exchange** with routing key `job.ingest`. It responds `202 Accepted` with the job ID right away.
3. **Q1 (validation/parsing worker)** consumes `job.ingest`, streams the file through a StAX parser, and extracts trackpoints, coordinates, elevation, and timestamps. On success it sets `validation_complete = true` and republishes to the Main Topic Exchange with routing key `job.service.*`.
4. The Main Topic Exchange fans that message out — via wildcard binding, not a fanout exchange, so Q1 doesn't receive its own output back — to two independent consumers running in parallel:
   - **Q2 (math worker)**: Haversine distance, elevation gain/loss, grade, pace, Shenandoah difficulty score, RDP track simplification.
   - **Q3 (enrichment worker)**: calls OpenWeatherMap, OpenTopoData, and iNaturalist, and writes the result into a `weather_data` JSONB column on the `jobs` row.
5. Q2 and Q3 don't talk to each other directly. Each one finishes with a single atomic statement — `UPDATE jobs SET <its own flag> = true WHERE id = ? RETURNING validation_complete, calculations_complete, enrichment_complete` — and Postgres row-level locking guarantees that exactly one of the two ever sees all three flags come back `true`. Whichever one that is reads `weather_data` off the same row and publishes a lightweight message (just the job ID) to the **Completion Exchange** with routing key `job.generator`.
6. **Q4 (PDF worker)** consumes that message, queries Postgres once for everything — math results plus weather — generates the PDF, saves it to MinIO, stores the PDF path, nulls out `weather_data`, and marks the job `COMPLETE`.
7. The frontend, which has been polling `GET /jobs/{id}`, sees the status change and displays the report.

**Failure path (any stage):** a transient failure (API timeout, dropped DB connection) causes RabbitMQ to route the message to the **Dead Letter Exchange**, which forwards it to that stage's own dead letter queue (`DLQ1`–`DLQ4`). From there it retries with exponential backoff (5s → 30s → 2m → 10m). A successful retry republishes to the Main Topic Exchange and rejoins the normal flow; exhausted retries mark the job `FAILED` and notify the user.

Each worker stage is an independent RabbitMQ consumer — none of them call each other directly. Postgres is the single source of truth for job completion state; the queues themselves hold no state.

## Tech stack

| Layer | Choice |
|---|---|
| Language / framework | Java, Spring Boot |
| Message broker | RabbitMQ (Spring AMQP) |
| Database | PostgreSQL |
| Object storage | MinIO (S3-compatible) |
| GPX parsing | JPX (`io.jenetics:jpx`) with StAX streaming |
| PDF generation | iText / JasperReports |
| Frontend | React |
| Infra | Docker Compose, GitHub Actions, Railway/Render |

## Key design decisions

- **Async processing** — the upload endpoint returns `202 Accepted` immediately; the user never blocks on heavy computation. Mirrors how activity-tracking apps like Strava handle uploads.
- **Topic exchange over fanout** — a fanout exchange would re-deliver messages to Q1 along with Q2/Q3. A topic exchange with wildcard binding keys (`job.service.*`) lets Q2 and Q3 subscribe without looping Q1 back into its own output.
- **Postgres as the completion gatekeeper, not the workers** — Q2 and Q3 run independently and don't know about each other. Whichever one's write shows all three stage flags `true` is the one that triggers Q4. This is done with a single atomic `UPDATE ... RETURNING` statement per worker, so Postgres row-level locking guarantees exactly one worker ever observes all three flags true — closing both a double-fire race condition and a silent-stall race condition that a naive "check, then update" would hit.
- **StAX over DOM for GPX parsing** — DOM loads the whole file into memory; large GPX files with thousands of trackpoints would crash the worker. StAX streams one element at a time with constant memory use regardless of file size.
- **Weather lives in Postgres, briefly** — only Q3 calls the weather API, but either Q2 or Q3 might be the one that finishes last and needs to trigger completion. Rather than trying to pass weather data between two workers that never talk to each other, Q3 writes it to a `weather_data JSONB` column. Q4 reads it back out and clears the column right before responding to the user — late enough that the data survives a crash anywhere upstream, but never persisted longer than it needs to be.
- **A separate dead letter queue per stage** — one shared DLQ would mix validation failures with enrichment failures, making debugging and replay difficult. Each stage's failures land in their own DLQ.
- **Fail-fast vs. retry-worthy failures are handled differently** — malformed input (wrong file type, missing fields) is rejected immediately with a `400`, no retry, never touches a queue. Transient failures (API timeouts, dropped DB connections) go through exponential backoff (5s → 30s → 2m → 10m) before landing in a DLQ for good.

## Project structure

```
gpx-file-processor/
├── src/main/java/.../ingest/       # Q1 — validation & parsing
├── src/main/java/.../math/         # Q2 — distance, elevation, difficulty
├── src/main/java/.../enrichment/   # Q3 — weather, wildlife, conditions
├── src/main/java/.../generator/    # Q4 — PDF generation
├── src/main/java/.../common/       # shared entities, repositories, config
├── docker-compose.yml
└── docs/
    └── architecture.png
```

## Getting started

> In progress — Day 1 of the build.

```bash
git clone https://github.com/KulvanshJaswal/gpx-file-processor.git
cd gpx-file-processor
docker compose up
```

Requires Docker, Java 21+, and Maven.

## Roadmap

- [ ] Core pipeline: upload → Q1 → Q2/Q3 → Q4 → PDF
- [ ] DLX/DLQ error handling with backoff retry
- [ ] React frontend (drag-and-drop upload, polling, results dashboard)
- [ ] Deploy (Railway/Render) + CI/CD
- [ ] Trail library with fingerprint-based duplicate detection (Phase 2)
- [ ] User accounts + upload history (Phase 3)
- [ ] Social features — public trails, condition reports (Phase 4)
- [ ] Real-time progress via WebSockets/SSE (Phase 5)

## License

MIT
