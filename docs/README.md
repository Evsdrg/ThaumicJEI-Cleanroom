# Documentation

- [Architecture](architecture.md) — module map and the path from a JEI lookup to the snapshot.
- [Fixed cache](cache.md) — cache lifecycle, commands, configuration and failure behaviour.
- [Building](building.md) — toolchain, build commands, artifacts and the differential variant.
- [Testing](testing.md) — unit tests, differential checks and the real-modpack validation boundary.
- [Upstream and attribution](upstream/README.md) — what comes from Buuz135/Thaumic-JEI and what changed.
- [Upstream changelog](upstream/CHANGELOG.md) — historical releases of the original mod, not this refactor's release notes.

For this refactor's own release notes see [`CHANGELOG.md`](../CHANGELOG.md) in the repository root.

## Screenshots

The images in [`images/`](images/) are cropped from real-client verification captures. They show the
aspect-source category, an infusion recipe and a recipe lookup in a 118-mod pack.

`src/main/resources/assets/thaumicjei/textures/gui/gui.png` is a **runtime game asset**, not
documentation. It must stay at that exact path because Minecraft resolves it as a resource location.
