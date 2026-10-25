# Testing

## Unit tests

Run the suite with:

```bash
JAVA_HOME=/path/to/java25 ./gradlew \
  -Porg.gradle.java.installations.paths=/path/to/jdk8,/path/to/java25 test
```

Use `--rerun-tasks` when you want execution rather than a Gradle `FROM-CACHE` result. Reports are
written to `build/reports/tests/test/` and machine-readable results to `build/test-results/test/`.

The current suite has **44 tests**. It is organised by subsystem rather than placed in one large test
class:

| Area | Test classes | What they cover |
|---|---|---|
| Derivation | `AspectEvaluatorTest` | Candidate order, scaling, wildcard inputs, cycles/shared history, failures, remainders, empty recipes and plan reuse. |
| Immutable data | `SnapshotDifferentialTest`, `SourceViewTest` | Snapshot comparison and read-only source views. |
| Source pages | `SourcePageSafetyTest` | Page diff direction and safe additions/removals. |
| Lookup | `CachedItemAspectsTest` | Exact item/NBT lookup, empty results, reload generations and snapshot visibility. |
| Recipe index | `OrderedRecipeIndexTest` | Stable ordering, output identity and metadata handling. |
| Persistence | `SnapshotStoreTest` | Round trips, malformed/unknown documents, fixed-cache reads and atomic-write cancellation. |
| Lifecycle | `GenerationControlTest`, `CacheCommandTest` | Pause/cancel/rebuild control, no evaluator on fixed import, and save-before-publish. |
| Research | `ResearchUtilsTest` | Research-key handling used by the client refresh code. |
| Legacy reference | `CacheFingerprintTest` | The old fingerprint experiment only; it is not the active invalidation mechanism. |

Unit tests exercise the derivation and lifecycle logic without claiming to replace an in-game run.

## Differential checks

The optional `src/differential` probe runs inside the same mod id in a test installation. After the
fixed cache is ready it compares selected snapshot values with Thaumcraft 6.1.BETA26's real
`ThaumcraftCraftingManager.getObjectTags`, then restores the global map it temporarily touches.

The latest recorded comparison covered ten targets, including an apple, five backpack tiers, the arcane
assembler, the thaumonomicon and two facades: **10/10 matched**. It is a sampled equivalence check, not
a proof for every item in every modpack. See [Building](building.md#differential-variant) for the build
and artifact-separation requirements.

## Real-client validation

The release candidate was exercised in an independent copy of a 118-mod 1.12.2 pack with Cleanroom
0.6.13-alpha, Java 25 and HEI 4.32.0. The recorded run contained 18,813 deduplicated items, 46 aspects
and 22,702 source relations.

Verified there:

- first generation completed with zero skipped items;
- warm launches imported the fixed cache with `steps=0` and did not start the evaluator;
- R/U recipe lookup and the aspect-source category worked;
- cache hash and modification time stayed unchanged through normal use;
- rebuild followed by cancel retained the previous file and snapshot;
- representative arcane, crucible and infusion pages remained visible after unlocking all research in
  the test world.

The screenshots in `docs/images/` come from those runs and are cropped to the game window.

## Not covered

Do not read the test count as coverage of every modded behaviour. In particular, this revision has not
exhaustively validated partial research unlock/undo combinations, real workbench auto-filling, actual
infusion structure execution, every dynamic third-party recipe, resource reloads or long GPU frame-time
benchmarks. The UI captures used software rendering and are evidence of functionality, not an FPS
claim.
