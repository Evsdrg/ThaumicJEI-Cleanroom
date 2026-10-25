# Upstream and attribution

This repository is a code refactor based on **[Buuz135/Thaumic-JEI](https://github.com/Buuz135/Thaumic-JEI)**.

The upstream project is MIT-licensed. Its copyright notice and license text are preserved in the
repository-root `LICENSE`.

## Traceable ancestry

The Git history begins with the upstream commits and tags, with the refactor applied on top. The local
baseline used for this repository was upstream commit:

```text
53a1201 Increased version
```

Keeping that history makes it possible to inspect exactly which files came from upstream and which
were changed by this refactor. It does not imply endorsement by, or affiliation with, the original
author.

## Original work retained

- The mod identity and JEI integration purpose.
- Recipe categories and their overall presentation.
- GUI texture assets under `src/main/resources/assets/thaumicjei/`.
- Language files, including the original translations.
- The historical changelog in [`CHANGELOG.md`](CHANGELOG.md).

## Main changes in this refactor

- The aspect-derivation engine was rewritten around an ordered recipe index, a resumable evaluator and
  immutable snapshots.
- Aspect queries no longer derive values on the JEI query/render path.
- The persistent cache is now a fixed, write-once snapshot with explicit manual rebuilds.
- The build uses Unimined, a Java 25 toolchain and a pinned Cleanroom 0.6.13-alpha loader.
- Tests and a development-only differential probe were added.

The original `HEADER` template file was removed from the working tree during repository organisation.
It was not referenced by the build and still exists in the preserved upstream history.
