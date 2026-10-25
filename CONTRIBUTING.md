# Contributing

## Before changing code

Read [the architecture notes](docs/architecture.md) and [fixed-cache policy](docs/cache.md). In
particular, do not reintroduce aspect derivation into JEI query, tooltip or rendering callbacks.

The supported target is Minecraft 1.12.2, Cleanroom **0.6.13-alpha** and Java **25**. Do not bump the
loader to 0.7+ or change the Java target as part of an unrelated change.

## Repository layout

- `src/main/java` — production code.
- `src/main/resources` — runtime assets and metadata; resource paths are contracts, not documentation
  folders.
- `src/test/java` — unit tests, organised by subsystem.
- `src/differential/java` — optional development-only comparison probe.
- `docs/` — user/developer documentation and cropped demonstration screenshots.
- `docs/upstream/` — historical upstream material, clearly separated from this refactor's notes.

Keep generated files, game installations, caches, private-world captures and credentials out of Git.
`build/`, `.gradle/`, `run/` and `logs/` are ignored intentionally.

## Checks before submitting

```bash
JAVA_HOME=/path/to/java25 ./gradlew \
  -Porg.gradle.java.installations.paths=/path/to/jdk8,/path/to/java25 build
```

Add `--rerun-tasks` when validating tests so a cached result is not mistaken for a fresh run.

For changes to the evaluator or cache lifecycle, add or update tests covering the changed behaviour.
If you run the differential variant, rebuild the formal JAR afterwards and confirm it contains no
`/testing/` classes; see [Building](docs/building.md#differential-variant).

## Attribution

This is a refactor of [Buuz135/Thaumic-JEI](https://github.com/Buuz135/Thaumic-JEI), not a clean-room
reimplementation. Preserve the MIT license and upstream attribution. Do not remove or relocate runtime
assets merely to make the repository look tidier.
