# ThaumicJEI Cleanroom

A Java 25 reimplementation of ThaumicJEI for **Minecraft 1.12.2 + Cleanroom 0.6.13-alpha**.

> ## Attribution
>
> **This project is a code refactor based on [Buuz135/Thaumic-JEI](https://github.com/Buuz135/Thaumic-JEI).**
>
> The original mod, its assets and its language files are by **Buuz135** and contributors, licensed under the MIT License (see `LICENSE`). This repository keeps the upstream Git history as its base, so the ancestry of every change is traceable. It is not affiliated with or endorsed by the original author.
>
> What changed: the aspect-derivation engine was rewritten, the build migrated to Unimined + Cleanroom + a Java 25 toolchain, and the persistent cache was replaced with a fixed, write-once snapshot. The recipe categories, GUI textures and translations are the original work.

Version: `1.12.2-2.0.0-beta.2`

## Why this exists

The original mod re-derives item aspects on demand from Thaumcraft's recipe graph. On large modpacks that derivation is expensive and runs on the client's render/query path, which produces single-core saturation and multi-second stalls when browsing JEI recipes, especially when pressing **R** or **U** on an item.

This refactor keeps the visible behaviour but moves derivation off that path:

- Derivation runs **once**, on a budgeted client-tick queue, never inside a JEI callback.
- Tooltips and recipe lookups read an immutable in-memory snapshot only.
- The result is persisted and reused read-only on every later launch.

## Platform

| Component | Version |
|---|---|
| Minecraft | 1.12.2 |
| Loader | Cleanroom **0.6.13-alpha** (pinned) |
| Java | **25** (class major 69, no preview features) |
| Thaumcraft | 6.1.BETA26 |
| JEI | 4.16.1 / HEI 4.32.0+ |

Not supported: Cleanroom 0.7+, Java 8, ForgeGradle-based Java 8 builds. The build file pins `cleanroom { loader '0.6.13-alpha' }`; do not bump it without re-validating the aspect semantics.

## Cache behaviour: generate once, reuse forever

- With no `config/thaumicjei-cache-v2.json`, a complete cache is generated across a series of client ticks.
- With a complete schema-2 file present, it is loaded directly. **No startup fingerprinting, no recipe comparison, no automatic invalidation, no backfill of unknown items, no automatic file rewrite.**
- Tooltips and lookups are snapshot-only. An exact-NBT miss shows no extra aspects and never triggers computation.
- Displayed values are accepted as fixed even when Thaumcraft's own scan would vary slightly between launches. The mod does not modify Thaumcraft's real decomposition rules and does not write to its global `objectTags`.
- After adding/removing mods or changing recipes, *you* decide whether to run `/thaumicjei rebuild`.
- An existing corrupt, incomplete or unsupported-schema file is reported, not silently overwritten or regenerated.
- Items missing an aspect entry are skipped at load time; the rest of the cache stays usable.

This is a deliberate trade: a stale-but-instant cache instead of a per-launch re-derivation.

## Commands

```text
/thaumicjei status
/thaumicjei pause
/thaumicjei resume
/thaumicjei cancel
/thaumicjei rebuild
```

`rebuild` captures a fresh recipe index and re-derives everything. During generation the old snapshot, the old source pages and the on-disk file all stay in place. Nothing partial is published and no partial checkpoint is written: a new snapshot is published only after every evaluation succeeds with zero skips, and the file is replaced by an atomic same-filesystem rename. Cancelling or failing keeps the previous cache. There is no resume from a cancelled generation.

After a normal launch the evaluator is never constructed. The `generateAspectCache` config flag only controls **first-run automatic generation when no cache exists**; it does not disable manual rebuild. `alwaysRecreateAspectFromItemStackFile` is an ignored legacy field.

## Implementation notes

- Ordered recipe index, an explicit suspendable call stack, per-tick budget and cooldown. No virtual threads and no game callbacks moved to worker threads.
- Preserves Thaumcraft's candidate priority, shared evaluation history, count scaling, truncation, container remainders and bonus aspects.
- A dedicated IO thread only does file reads/writes and pure-data export; the immutable snapshot is published once via a volatile write.
- Source pages update by content diff; unknown items are never cold-derived on the render or query thread.
- Generation guards, pre-write validation, temp-file fsync and a commit lock prevent races between cancellation and the final rename.

## Build

```bash
JAVA_HOME=/path/to/java25 bash gradlew \
  -Porg.gradle.java.installations.paths=/path/to/jdk8,/path/to/java25 build
```

Gradle 9.7.0, Unimined 1.4.36-kappa, MCP stable 39-1.12. The isolated JDK 8 is used **only** by the legacy MCP tooling; it never compiles the mod or runs the game.

The installable artifact is `build/libs/ThaumicJEI-Java25-1.12.2-2.0.0-beta.2.jar`. Do not install the `-dev`, `-sources` or test variants. A plain `build` produces only the formal artifact.

The differential test variant (`copyDifferential`) is a development-only harness that compares this evaluator against Thaumcraft's real `getObjectTags`. Its remapper reuses the same default output path, so after running it you must re-run `remapJar --rerun-tasks` and confirm the release JAR contains no `/testing/` classes before publishing.

## Install and rollback

Exit the game, back up your existing JAR and cache, and keep only one mod with modid `thaumicjei`. Do not run JEI and HEI side by side. Replace the JAR with the remapped release build. An existing complete v2 cache can be kept; the old `thaumicjei_itemstack_aspects.json` is never rewritten.

To roll back, exit the game, remove the new JAR and restore the original or an older build. Keep the original JAR and the old cache file separately.

## Verified behaviour and known limits

Verified on a real 118-mod pack (18,813 deduplicated items, 46 aspects, 22,702 source relations):

- First-run generation completed with 0 skipped items.
- Warm launches load the fixed cache directly with `steps=0` and the evaluator never started.
- R/U lookups and the aspect-source category render correctly; cache hash and mtime unchanged after a full session.
- `/thaumicjei rebuild` followed by `/thaumicjei cancel` leaves the old file byte-identical.
- The evaluator matches Thaumcraft's own `getObjectTags` on 10/10 sampled targets (differential harness).

Known limits, stated plainly:

- Displayed aspects are not guaranteed to match each launch's Thaumcraft scan; this is intentional.
- Unknown or missing items are skipped; there is no fuzzy NBT reuse.
- The first generation without a cache can take several minutes. Slow third-party callbacks are not preemptible and pause the queue.
- Not validated: every partial/unlock-undo research combination, real workbench auto-filling, infusion structure execution, dynamic per-mod recipe changes, and GPU frame-time percentiles. Testing used software rendering.

Multiplayer is out of scope: this is a client-side-only mod with no custom network protocol.

## License

MIT, as the upstream project. Original copyright notice:

```
Copyright (c) 2017 Buuz135
```

See `LICENSE` for the full text. This refactor's modifications are released under the same license.

## 中文说明

本仓库是 [Buuz135/Thaumic-JEI](https://github.com/Buuz135/Thaumic-JEI) 的代码重构版本，保留上游 Git 历史作为基线，与原作者的立场无关。核心变化是把物品要素推导从 JEI 查询/渲染路径移出，改为首次生成、长期只读复用的固定缓存。固定目标是 Cleanroom 0.6.13-alpha + Java 25，不支持 0.7+ 与 Java 8 Forge。菜单命令、构建与回退方式见上文。
