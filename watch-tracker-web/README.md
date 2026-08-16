# watch-tracker-web

A Spring Boot application providing a MongoDB-backed implementation of `PreviousEnrichmentSnapshot`
(the seam defined in `watch-tracker-core`), a background-job import flow, and a minimal read-only
dashboard over what's stored.

## Status

- **Persistence seam**: implemented. `MongoPreviousEnrichmentSnapshot` (read) and
  `MongoEnrichmentWriter` (write) are functionally complete and mirror `JsonEnrichedShowSnapshot`
  closely, reusing its matching/override-rehydration logic directly rather than re-implementing it.
  `EnrichedShowDocument.title`/`matchConfidence` are `@Indexed` (with
  `spring.data.mongodb.auto-index-creation: true` in `application.yml` — Spring Data MongoDB
  defaults that to `false`, so the annotations alone wouldn't create anything).
- **Import**: `POST /import` runs the same core pipeline as the CLI (parse → normalize → group →
  optional TMDb/TVMaze enrichment) and writes to MongoDB; the CLI is not invoked and is not
  modified. The run happens on a dedicated single-thread background executor
  (`AsyncConfig#importTaskExecutor`) via `ImportJobRunner`, not on the request thread — enrichment
  can make live network calls, so blocking the HTTP response on it isn't acceptable. `POST /import`
  reserves a job slot through `ImportJobStore` and redirects to `GET /import/status/{jobId}`, which
  the browser polls via meta-refresh until the job finishes. `ImportJobStore` also enforces that
  only one import runs at a time — a second `POST /import` while one is in flight is rejected with
  an error, rather than racing `MongoEnrichmentWriter`'s delete-then-save against itself.
- **Dashboard**: a single read-only page listing persisted shows and flagging ones whose match
  confidence isn't `EXACT`/`MANUAL_OVERRIDE`. This is a coarser view than `review-queue.json` — it
  has no visibility into missing-progress or no-metadata reasons, only stored confidence.
- **Security**: `SecurityConfig` is an explicit permit-all placeholder, not real auth. It exists
  only so the `spring-boot-starter-security` dependency doesn't block access with Spring's default
  generated-password behavior. Replace it wholesale once a real auth story exists.

## Running locally

Requires a MongoDB instance reachable at the URI in `application.yml` (defaults to
`mongodb://localhost:27017/watch-tracker`; override via `SPRING_DATA_MONGODB_URI`).

```
docker compose -f docker-compose.yml up -d
./gradlew :watch-tracker-web:bootRun
```

Then visit `http://localhost:8080/`, use `Import Netflix history` to trigger a run, and watch it
progress on the status page it redirects to.

## Verification status

Confirmed working against real Netflix history data, including a second consecutive run to check
the Mongo-backed snapshot/writer round-trip (previously-resolved shows stay resolved rather than
re-triggering fresh enrichment; the collection-replace write doesn't lose anything across runs).
Match-confidence counts line up with the CLI's `shows.json` output on the same data.

Not yet covered: any real integration test against a live MongoDB (`EnrichedShowMongoMapperTest`
covers the JSON round-trip without one; `WebImportServiceTest` covers orchestration with the
repository mocked). `ImportControllerTest` and `DashboardControllerTest` cover the web layer with
`@WebMvcTest` slices.
