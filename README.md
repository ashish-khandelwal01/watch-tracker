# Watch Tracker

Watch Tracker imports personal Netflix viewing-history exports into an immutable,
platform-neutral domain model. It deliberately makes no assumptions about whether a
series is complete or whether a title is a film.

## Architecture

`watch-tracker-core` owns parsing, normalization, grouping, and export. The CLI is a
thin adapter over that reusable core. `watch-tracker-testdata` is a resource module for
fixtures and golden files. `watch-tracker-web` is an empty boundary reserved for a
future Spring Boot UI.

## Build and test

Use Java 21 and run `./gradlew build`. Formatting can be checked with
`./gradlew spotlessCheck` and applied with `./gradlew spotlessApply`.

## Parser design

The Netflix parser validates the `Title` and `Date` headers and maps every valid row to
a raw `WatchEvent`. Normalization is intentionally separate: it recognizes generic
episode, part, book, chapter, volume, season, and Roman-numeral patterns without a
catalogue of show names. Grouping then produces one `Show` per canonical title.

## Extension points and roadmap

New platforms can implement an importer that creates `WatchEvent` values, then reuse
the normalizer and show builder. Future work includes PostgreSQL persistence, a Spring
Boot dashboard, account security, recommendations, and additional streaming importers.

## Optional TMDb and TVMaze metadata enrichment

The importer runs entirely offline by default. Metadata enrichment adds genres, trusted
movie/TV type, posters, release dates, and provider identifiers. Select a strategy with
the `watchtracker.metadata.provider` JVM property: `TMDB`, `TVMAZE`, or
`TMDB_WITH_TVMAZE_FALLBACK` (the default). The default uses TMDb first when its token
is available, otherwise TVMaze alone; TVMaze does not require an API key.

To use TMDb, create an API Read Access Token and set it in your shell without committing it:

```bash
export TMDB_API_READ_ACCESS_TOKEN='your-token'
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata"
```

To force TVMaze only:

```bash
./gradlew -Dwatchtracker.metadata.provider=TVMAZE :watch-tracker-cli:run --args="history.csv shows.json --metadata"
```

Successful matches are cached under `.watch-tracker/metadata-cache`. Use
`--cache path/to/cache` to choose another directory. Enriched exports contain a `show`
object for imported history and an optional `metadata` object with provider data.

## Diagnostics

The importer can generate detailed diagnostics showing the processing pipeline for each show.
This is useful for debugging why a show wasn't parsed correctly or why metadata enrichment failed.

Enable diagnostics with the `--diagnostics` flag:

```bash
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --diagnostics"
```

Diagnostics are written to `build/diagnostics/` by default, with one JSON file per show:

```bash
build/diagnostics/
  Money Heist.json
  Dark.json
  The Cleaning Lady.json
```

To specify a custom diagnostics directory:

```bash
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --diagnostics /path/to/diags"
```

Each diagnostics file shows the complete processing journey through the pipeline stages:
- `CSV_PARSER`: Raw title and watch date from Netflix export
- `TITLE_NORMALIZER`: Extracted season/episode from title pattern matching
- `SHOW_BUILDER`: Highest season and episode seen in watch history
- `METADATA_PROVIDER`: External provider matches (TMDb, TVMaze)
- `EPISODE_RESOLVER`: Resolution of episode titles to provider episodes
- `PROGRESS_BUILDER`: Highest resolved episode position
- `EXPORTER`: Export success/failure with file paths

Diagnostics can be combined with other flags (metadata enrichment, cache directory):

```bash
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata --cache .cache --diagnostics"
```

Example diagnostics output for a show:

```json
{
  "show": "Money Heist",
  "stages": [
    {
      "stage": "CSV_PARSER",
      "output": {
        "title": "Money Heist: Part 3: Episode 5",
        "date": "2024-02-02T00:00:00"
      }
    },
    {
      "stage": "TITLE_NORMALIZER",
      "input": "Money Heist: Part 3: Episode 5",
      "output": {
        "season": 3,
        "episode": 5
      }
    },
    {
      "stage": "SHOW_BUILDER",
      "output": {
        "highestSeasonSeen": 3,
        "highestEpisodeSeen": 5
      }
    },
    {
      "stage": "METADATA_PROVIDER",
      "message": "Matched metadata",
      "output": {
        "provider": "TMDB",
        "id": 71446
      }
    },
    {
      "stage": "PROGRESS_BUILDER",
      "message": "Highest numbered episode",
      "output": {
        "season": 3,
        "episode": 5
      }
    }
  ]
}
```
