# GPX File Processor
Live Prod - https://main.dzxeuwy4ihppw.amplifyapp.com/
A distributed, asynchronous GPX file processing system. Upload a GPX file from a hike and get back a PDF report with distance, elevation, difficulty, a 7-day weather forecast, an elevation profile chart, and a grade-colored trail map — processed through a Spring Boot + RabbitMQ job pipeline rather than a single blocking request, and deployed across AWS.

Built as a portfolio project to demonstrate distributed systems design, async processing, real file-format parsing, race-condition-safe coordination between concurrent workers, and system design thinking — not just a CRUD app with a database.

## Architecture

**Happy path:**

1. The React frontend uploads a GPX file to the Spring Boot upload endpoint.
2. The upload endpoint validates the file (fail-fast — bad input returns `400` immediately, never touches a queue), saves the raw file to object storage, creates a `jobs` row in Postgres with status `QUEUED`, and publishes a message to the **Main Topic Exchange** with routing key `job.ingest`. It responds `202 Accepted` with the job ID right away.
3. **Q1 (validation/parsing worker)** consumes `job.ingest`, streams the file through a StAX parser (via the JPX library), and extracts trackpoints, coordinates, elevation, and timestamps. On success it sets `validation_complete = true` and republishes to the Main Topic Exchange with routing key `job.service.*`.
4. The Main Topic Exchange fans that message out — via wildcard binding, not a fanout exchange, so Q1 doesn't receive its own output back — to two independent consumers running concurrently:
   - **Q2 (math worker)**: Haversine distance, elevation gain/loss, grade, pace, Shenandoah difficulty score, and Ramer-Douglas-Peucker track simplification (stored separately on a `routes` table).
   - **Q3 (enrichment worker)**: calls Open-Meteo for a 7-day forecast and OpenTopoData for elevation correction (with its own daily-rate-limit tracking), writing the result into a `weather_data` JSONB column on the `jobs` row.
5. Q2 and Q3 don't talk to each other directly. Each one finishes with a single atomic statement — `UPDATE jobs SET <its own flag> = true WHERE id = ? RETURNING validation_complete, calculations_complete, enrichment_complete` — and Postgres row-level locking guarantees that exactly one of the two ever sees all three flags come back `true`. Whichever one that is publishes a lightweight message (just the job ID) to the **Completion Exchange** with routing key `job.generator`.
6. **Q4 (PDF worker)** consumes that message, queries Postgres once for everything — math results plus weather — generates the PDF (metadata, stats table, weather table, an elevation profile chart, and a grade-colored route overlaid on OpenStreetMap tiles), saves it to object storage, stores the PDF path, nulls out `weather_data`, and marks the job `COMPLETE`.
7. The frontend, which has been polling `GET /jobs/{id}`, sees the status change and displays the report.

**Failure path (any stage):** a retry-worthy failure (API timeout, dropped connection) triggers the worker to read and increment a retry-count header on the message, then publish it to a shared **delayed-message exchange** (via the `rabbitmq_delayed_message_exchange` plugin) with an escalating delay — 5s → 30s → 2m → 10m — before it's automatically redelivered to the same stage's real queue. After the final attempt, the worker publishes directly to that stage's dead letter queue (`DLQ1`–`DLQ4`) and marks the job `FAILED` with an `error_message`. Fail-fast validation errors (malformed input) skip this entirely and return `400` immediately.

Each worker stage is an independent RabbitMQ consumer — none of them call each other directly. Postgres is the single source of truth for job completion state; the queues themselves hold no state.

## Deployed on AWS

| Piece | Service | Notes |
|---|---|---|
| Database | RDS (PostgreSQL, single-AZ) | |
| Message broker | Self-hosted RabbitMQ on a Lightsail VM | Amazon MQ's cheapest RabbitMQ-compatible instance type (`mq.m7g.medium`) runs ~$85/month for a workload this light — self-hosting the same Docker image used locally on a $7/month Lightsail instance was the more cost-conscious call, at the price of managing the broker's own uptime instead of AWS doing it |
| File storage | S3 | swapped in for local MinIO via a `FileStorageService` interface, selected per Spring profile — no code duplication between local and prod |
| Backend | Elastic Beanstalk (single instance, no load balancer) | an EC2 instance profile grants it scoped IAM permissions to the S3 bucket — no long-lived access keys anywhere in the app |
| HTTPS for the backend | CloudFront, in front of Beanstalk | a single-instance Beanstalk environment has no load balancer, so it can't terminate TLS itself; CloudFront sits in front of it purely to add HTTPS (required once the frontend moved to HTTPS-only Amplify hosting — browsers block a secure page from calling an insecure API as "mixed content") without paying for an Application Load Balancer |
| Frontend | Amplify | auto-deploys from the `frontend/` subdirectory on push to `main` |

Each Beanstalk worker's `@RabbitListener` concurrency is tuned to where the actual bottleneck sits, not applied uniformly — Q2 and Q3 do the heaviest work (RDP recursion, paced external API calls) and get more concurrent consumers than Q1 (fast parsing) or Q4 (one message per completed job, not per fan-out).

## Tech stack

| Layer | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 4 |
| Message broker | RabbitMQ (Spring AMQP), self-hosted in prod |
| Database | PostgreSQL (RDS in prod) |
| Object storage | MinIO locally / AWS S3 in prod, behind one interface |
| GPX parsing | JPX (`io.jenetics:jpx`) with StAX streaming |
| PDF generation | iText, with JFreeChart for the elevation profile |
| External APIs | Open-Meteo (weather), OpenTopoData (elevation correction) |
| Frontend | React + Vite, deployed on Amplify |
| Infra | Docker Compose (local), RDS / Lightsail / S3 / Elastic Beanstalk / CloudFront / Amplify (prod) |

## Key design decisions

- **Async processing** — the upload endpoint returns `202 Accepted` immediately; the user never blocks on heavy computation.
- **Topic exchange over fanout** — a fanout exchange would re-deliver messages to Q1 along with Q2/Q3. A topic exchange with wildcard binding keys (`job.service.*`) lets Q2 and Q3 subscribe without looping Q1 back into its own output.
- **Postgres as the completion gatekeeper, not the workers** — Q2 and Q3 run independently and don't know about each other. A single atomic `UPDATE ... RETURNING` statement per worker, protected by Postgres row-level locking, guarantees exactly one worker ever observes all three completion flags true — closing both a double-fire race condition and a silent-stall race condition that a naive "check, then update" would hit.
- **StAX over DOM for GPX parsing** — DOM loads the whole file into memory; large GPX files with thousands of trackpoints would crash the worker. StAX streams one element at a time with constant memory use regardless of file size.
- **Weather lives in Postgres, briefly** — only Q3 calls the weather API, but either Q2 or Q3 might be the one that finishes last and needs to trigger completion. Q3 writes the forecast to a `weather_data` JSONB column; Q4 reads it back out and clears the column right before responding — late enough to survive a crash anywhere upstream, never persisted longer than it needs to be.
- **A dead letter queue per stage, plus a shared delayed-retry exchange** — one shared DLQ would mix validation failures with enrichment failures. A shared plugin-backed delayed exchange handles the escalating backoff for all four stages without needing twelve separate timed holding queues.
- **Fail-fast vs. retry-worthy failures are categorized by exception type, not by stage** — `InvalidGpxFileException` always means bad input: `400`, no retry, never touches a queue. `FileStorageException` always means a transient infrastructure failure: retried with exponential backoff, eventually dead-lettered if it never recovers. Every worker follows the same rule regardless of what it's doing when the failure happens.
- **Every write to a row another worker might be concurrently modifying is a narrow, targeted `UPDATE`, never a whole-object `save()`** — a whole-entity save silently overwrites every column with whatever stale snapshot the calling worker fetched at the start of its own run, clobbering a concurrent writer's real results. This surfaced as a real bug during testing (Q3's elevation correction was overwriting Q2's already-committed math results) and was fixed by replacing it with column-scoped native updates everywhere the same pattern existed.
- **Worker concurrency is scaled to where the bottleneck actually is, not applied uniformly** — giving every stage the same number of concurrent consumers would just move the backlog downstream faster if the slow stages aren't the ones that scale.
- **CloudFront in front of a single-instance Beanstalk environment, instead of switching to a load-balanced environment** — HTTPS was needed once the frontend moved to Amplify (HTTPS-only), but an Application Load Balancer costs ~$16-22/month just to exist. CloudFront adds HTTPS termination in front of the existing plain-HTTP origin for a fraction of the cost, without changing anything about how Beanstalk itself is deployed.

## Project structure

```
gpx-file-processor/
├── src/main/java/.../ingest/        # Q1 — validation & parsing
├── src/main/java/.../math/          # Q2 — distance, elevation, difficulty, RDP
├── src/main/java/.../enrichment/    # Q3 — weather, elevation correction
├── src/main/java/.../generator/     # Q4 — PDF generation
├── src/main/java/.../upload/        # upload validation + storage + job creation
├── src/main/java/.../common/
│   ├── entity/                      # JPA entities, enums
│   ├── repository/                  # incl. atomic native-query methods
│   ├── controller/                  # REST endpoints
│   ├── config/                      # RabbitMQ, MinIO/S3, CORS
│   ├── storage/                     # FileStorageService + Minio/S3 implementations
│   └── exception/                   # InvalidGpxFileException, FileStorageException, etc.
├── frontend/                        # React + Vite, deployed via Amplify
├── docker-compose.yml
└── rabbitmq/Dockerfile              # RabbitMQ + delayed-message-exchange plugin
```

## Getting started (local)

```bash
git clone https://github.com/KulvanshJaswal/GPX-File_Processor.git
cd GPX-File_Processor
docker compose up -d
./mvnw spring-boot:run
```

Requires Docker, Java 21+, and Maven. Copy your own credentials into `application-local.properties` (gitignored) matching the structure in `application.properties`.

For the frontend:
```bash
cd frontend
npm install
npm run dev
```

## Roadmap

- [x] Core pipeline: upload → Q1 → Q2/Q3 → Q4 → PDF
- [x] Atomic, race-condition-safe completion coordination between Q2 and Q3
- [x] DLX/DLQ error handling with escalating backoff retry
- [x] Elevation profile chart, grade-colored map overlay, hiking-themed PDF styling
- [x] React frontend (drag-and-drop upload, polling, results/failure views)
- [x] Deployed on AWS (RDS, self-hosted RabbitMQ on Lightsail, S3, Elastic Beanstalk, CloudFront, Amplify)
- [ ] Custom domain
- [ ] Trail library with fingerprint-based duplicate detection (Phase 2)
- [ ] User accounts + upload history (Phase 3)
- [ ] Social features — public trails, condition reports (Phase 4)
- [ ] Real-time progress via WebSockets/SSE (Phase 5)

## License

MIT
