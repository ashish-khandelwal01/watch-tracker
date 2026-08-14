# Watch Tracker documentation

Day-to-day usage (CLI flags, quick start, output files) lives in the root
[README.md](../README.md).

This directory holds **design notes**: why the modules are shaped the way they are,
how importers plug in, and how overrides / review interact with enrichment.

| Document | Topic |
|----------|--------|
| [architecture.md](architecture.md) | Modules, packages, pipeline, design principles |
| [importers.md](importers.md) | `WatchEvent` contract; Netflix CSV and Trakt history |
| [enrichment.md](enrichment.md) | Metadata providers, confidence, progress resolution |
| [overrides-and-review.md](overrides-and-review.md) | `overrides.json`, sticky confirms, revoke, review queue |

When behavior changes, update the root README for operators and these notes for
maintainers.
