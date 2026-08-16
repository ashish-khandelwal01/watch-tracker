# watch-tracker-web

A Spring Boot application providing a MongoDB-backed implementation of `PreviousEnrichmentSnapshot`
(the seam defined in `watch-tracker-core`) plus a minimal read-only dashboard over what's stored.

## Status

- **Persistence seam**: implemented. `MongoPreviousEnrichmentSnapshot` (read) and
  `MongoEnrichmentWriter` (write) are functionally complete and mirror `JsonEnrichedShowSnapshot`
  closely, reusing its matching/override-rehydration logic directly rather than re-implementing it.
- **Dashboard**: a single read-only page listing persisted shows and flagging ones whose match
  confidence isn't `EXACT`/`MANUAL_OVERRIDE`. This is a coarser view than `review-queue.json` — it
  has no visibility into missing-progress or no-metadata reasons, only stored confidence.
- **Not yet built**: anything that actually *runs* an enrichment pass. The CLI is still the only
  way to trigger an import/enrichment run; nothing in this module calls `MetadataEnrichmentService`
  yet. Wiring that up (a scheduled job, an upload endpoint, or a shared runner service moved into
  core so both the CLI and this module can call it) is the natural next step.
- **Security**: `SecurityConfig` is an explicit permit-all placeholder, not real auth. It exists
  only so the `spring-boot-starter-security` dependency doesn't block access with Spring's default
  generated-password behavior. Replace it wholesale once a real auth story exists.

## Running locally

Requires a MongoDB instance reachable at the URI in `application.yml` (defaults to
`mongodb://localhost:27017/watch-tracker`; override via `SPRING_DATA_MONGODB_URI`).

```
docker run -d -p 27017:27017 mongo:7
./gradlew :watch-tracker-web:bootRun
```

Then visit `http://localhost:8080/`. Until a run-trigger exists, the collection will be empty
unless something writes to it directly via `MongoEnrichmentWriter` (or you seed it by hand for
testing the dashboard).

## Verification status

None of this has been built or run — see the root `docs/architecture.md` note on sandbox
limitations. Before trusting it: `./gradlew :watch-tracker-web:build` (after regenerating the
module's `gradle.lockfile`, which was deleted since it no longer matches the new dependencies),
then a manual run against a local MongoDB.
