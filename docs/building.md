# Building

## Toolchain

| Component | Version |
|---|---|
| JDK used to compile and run | Java 25 |
| Gradle wrapper | 9.7.0 |
| Unimined | 1.4.36-kappa |
| Minecraft mappings | MCP stable 39-1.12 |
| Cleanroom loader | **0.6.13-alpha**, pinned in `build.gradle` |
| Legacy JDK 8 | Only for old MCP merge tooling |

Java 8 is not a supported target for the mod or the game. Cleanroom 0.7+ is not supported either.

## Build

On a machine with JDK 25 and an isolated JDK 8:

```bash
JAVA_HOME=/path/to/java25 ./gradlew \
  -Porg.gradle.java.installations.paths=/path/to/jdk8,/path/to/java25 build
```

The `org.gradle.java.installations.paths` property is needed because the legacy MCP tooling cannot use
the Java 25 runtime. It does not change the mod's compilation target.

For an offline build with a populated Gradle cache, add `--offline`.

## Artifacts

The installable file is:

```text
build/libs/ThaumicJEI-Java25-1.12.2-2.0.0-beta.3.jar
```

Do not install `-dev`, `-sources` or differential variants. The release JAR must not contain
`com/buuz135/thaumicjei/testing/` classes.

A quick local check:

```bash
unzip -l build/libs/ThaumicJEI-Java25-1.12.2-2.0.0-beta.3.jar | grep '/testing/' || true
```

No output is the expected result.

## Differential variant

`src/differential/java` contains an optional development probe. Build and copy it explicitly:

```bash
./gradlew copyDifferential
```

It is enabled only with `-Dthaumicjei.differential=true` and writes its report to the absolute path in
`-Dthaumicjei.differential.output=...`.

The differential remapper shares the default unclassified output path with the normal remapper. After
running it, rebuild the formal artifact before publishing:

```bash
./gradlew remapJar --rerun-tasks
```

Then verify again that the release JAR contains no `/testing/` classes. This variant is a development
tool, not an install recommendation.

## Repository hygiene

Do not commit `build/`, `.gradle/`, `run/`, `logs/`, generated caches, game installations, screenshots
of private worlds or credentials. Documentation screenshots belong in `docs/images/`; the GUI texture
under `src/main/resources` must remain at its runtime resource path.
