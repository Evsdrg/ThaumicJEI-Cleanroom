# Changelog

Release notes for this refactor. For releases of the original mod, see
[`docs/upstream/CHANGELOG.md`](docs/upstream/CHANGELOG.md).

## 1.12.2-2.0.0-beta.3

### Changed

- Replaced evaluator continuation numbers with named phases while preserving the tested Thaumcraft
  priority, history, scaling, remainder and fallback behaviour.
- Separated cache lifecycle state from the Forge event facade and exposed structured status.
- Split schema-2 cache parsing, validation, writing and atomic commit; complete older v2 files remain readable.
- Replaced untyped recipe lookups with typed candidates and immutable research-recipe publication.
- Removed inactive fingerprint and legacy-format implementations; retained their on-disk schema compatibility.

### Added

- Expanded the suite to 58 tests, including 24 randomized TC differential cases, deep-history limits,
  save/cancel lifecycle paths and old schema-2 cache reads.
- Added a `GenerationStatus` model and testable cache-generation lifecycle.

### Verified

- Full build and differential probe compilation succeed; 58 tests pass.
- Xvfb 1280×720 warm-start smoke test loaded 18,813 cached items, published 650 source pages, and left the
  cache hash/mtime unchanged.
- The attempted R/U UI session did not produce sufficiently targeted evidence; do not interpret beta.2
  screenshots as beta.3 validation. No full cold generation, world-interaction R/U run or pack-level
  differential rerun was completed for this release.
- Java 25 (class major 69), Cleanroom 0.6.13-alpha and the fixed-cache policy are unchanged.

## 1.12.2-2.0.0-beta.2

### Changed

- Rewrote item-aspect derivation around an ordered recipe index, a resumable evaluator and immutable
  snapshots.
- Removed aspect derivation from JEI query and tooltip paths; exact-NBT misses no longer trigger work.
- Adopted a fixed cache policy: generate once, reuse read-only, with no startup fingerprinting,
  automatic invalidation or unknown-item backfill.
- Migrated the build to Unimined with a Java 25 toolchain and a pinned Cleanroom 0.6.13-alpha loader.
- Updated source pages by content diff instead of rebuilding them indiscriminately.

### Added

- `/thaumicjei status`, `pause`, `resume`, `cancel` and `rebuild` controls.
- Schema-2 cache persistence with pre-write validation, temporary-file fsync, atomic rename and
  generation/commit guards.
- A 44-test unit suite covering derivation, snapshots, source pages, persistence and lifecycle control.
- A development-only differential probe for comparing selected values with Thaumcraft's real aspect
  derivation.
- Architecture, cache, build, test and attribution documentation.

### Verified

- Independent 118-mod pack: 18,813 deduplicated items generated with zero skips.
- Warm launches loaded the fixed cache without starting the evaluator.
- R/U lookups and aspect-source pages worked after a normal launch.
- Rebuild followed by cancel retained the previous cache and snapshot.
- Representative arcane, crucible and infusion pages worked after unlocking research in the test world.

See [`docs/testing.md`](docs/testing.md) for the validation boundary and untested behaviour.
