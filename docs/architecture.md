# Architecture

The mod is client-side only. It has no custom network protocol and does not change Thaumcraft's
actual decomposition or research rules.

## Package map

| Path | Responsibility |
|---|---|
| `ThaumicJEI` | Mod entry point and shared logging. |
| `ThaumcraftJEIPlugin` | JEI/HEI plugin registration and runtime attachment. |
| `category/` | Arcane workbench, crucible, infusion, aspect-compound and aspect-source categories. |
| `drawable/` | GUI drawables used by those categories. |
| `ingredient/` | Aspect ingredient rendering and the read-only item-aspect lookup. |
| `gui/` | Tooltip handling and advanced GUI hooks. |
| `event/`, `util/` | Client research refresh and research/aspect utilities. |
| `recipe/OrderedRecipeIndex` | Stable, ordered recipe candidates with stable ids. |
| `compute/AspectEvaluator` | Resumable aspect derivation with an explicit frame stack. |
| `data/` | Immutable item/aspect snapshots and compact source relations. |
| `cache/` | Generation scheduling, commands and schema-2 persistence. |

## Lookup path

```text
JEI tooltip or R/U lookup
        |
        v
CachedItemAspects (immutable snapshot)
        |
        +-- exact item + NBT hit  -> return cached aspects
        |
        +-- miss                  -> no aspects, no derivation
```

A lookup never enters the evaluator. This is the central difference from the original mod.

## Generation path

```text
JEI runtime attached
        |
        +-- complete cache exists -> import in budgeted slices -> publish snapshot
        |
        +-- no cache              -> capture recipe index
                                      -> evaluate on a client-tick budget
                                      -> write complete file atomically
                                      -> publish snapshot
```

`GenerationManager` is the Forge event/command facade. A package-private `CacheGeneration` instance owns
one cache lifecycle, its IO tasks and source-page updates. `GenerationStatus` exposes a typed stage and
progress; command text is formatted separately and is never parsed to decide whether the cache is ready.
The lifecycle stages are waiting for runtime, reading, importing, generating, saving, ready, failed and
cancelled. Pause is an independent control flag, so resuming does not need to reconstruct a stage from
an old display string.

The instance receives its IO executor, recipe capture, active-runtime lookup and cache path at construction.
Tests can drive real commands and ticks while explicitly releasing queued IO, without reflection into
private fields. Cancellation and final file replacement still share the same revision lock; publication
still occurs only after successful saving.

`AspectEvaluator` uses named phases and an explicit frame stack. Each `step` performs one continuation;
extracting phase handlers does not make a large graph finish in a single tick. Slow third-party callbacks
are not preemptible, so the lifecycle pauses instead of interrupting them unsafely.

The evaluator preserves Thaumcraft's candidate priority, shared history, count scaling, truncation,
container remainders and bonus aspects. Shared history remains alive across failed siblings and is not
popped with the stack. It does not write Thaumcraft's global `objectTags`.

`OrderedRecipeIndex` exposes typed crafting, crucible and infusion candidates. `AspectSnapshot` keeps its
compact relation arrays and fixed 36-source page layout; the refactor does not replace them with a more
allocation-heavy object graph. Research visibility uses an immutable wrapper/category list published by
the plugin, rather than copying and retrying a shared mutable map.

## Source sets

- `src/main/java` — production code, Java 25 bytecode without preview features.
- `src/main/resources` — mod metadata, translations and the GUI texture. Resource paths are runtime
  contracts and must not be reorganised like documentation assets.
- `src/test/java` — JUnit tests for derivation, snapshots, persistence and lifecycle control.
- `src/differential/java` — development-only probe comparing the evaluator with Thaumcraft's real
  `getObjectTags`. It is excluded from the normal installable JAR.

## On-disk compatibility

The inactive fingerprint engine and the unused legacy-format importer are no longer part of the production
source set. Schema 2 still reads the old `fingerprint` and `recipes` fields so complete existing v2 files
remain usable; those fields do not activate automatic invalidation. JSON parsing, validation, writing and
atomic commit have separate methods in `SnapshotStore`.
