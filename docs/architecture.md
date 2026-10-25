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

`GenerationManager` owns the lifecycle. `AspectEvaluator` can stop between frames, so a large recipe
graph does not have to finish inside one tick. Slow third-party callbacks are not preemptible; the
manager pauses instead of pretending they can be interrupted safely.

The evaluator preserves Thaumcraft's candidate priority, shared history, count scaling, truncation,
container remainders and bonus aspects. It does not write Thaumcraft's global `objectTags`.

## Source sets

- `src/main/java` — production code, Java 25 bytecode without preview features.
- `src/main/resources` — mod metadata, translations and the GUI texture. Resource paths are runtime
  contracts and must not be reorganised like documentation assets.
- `src/test/java` — JUnit tests for derivation, snapshots, persistence and lifecycle control.
- `src/differential/java` — development-only probe comparing the evaluator with Thaumcraft's real
  `getObjectTags`. It is excluded from the normal installable JAR.

## Legacy code

`cache/CacheFingerprint.java` and its test predate the fixed-cache decision. The production generation
flow no longer calls it. It is retained in this revision for reference, not as an active invalidation
mechanism; `docs/cache.md` describes the behaviour that actually ships.
