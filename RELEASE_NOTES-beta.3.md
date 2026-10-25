# 1.12.2-2.0.0-beta.3

This release is a readability and maintainability refactor of the beta.2 codebase. It does not change the
supported platform or fixed-cache policy.

## Platform

Minecraft 1.12.2, Cleanroom **0.6.13-alpha**, Java **25**, Thaumcraft 6.1.BETA26, and JEI 4.16.1 or
HEI 4.32.0+. Cleanroom 0.7+ and Java 8 are not supported.

## Install

1. Exit the game and back up the existing ThaumicJEI JAR and cache.
2. Install only `ThaumicJEI-Java25-1.12.2-2.0.0-beta.3.jar`; do not install `-dev`, `-sources` or
   differential variants.
3. Keep only one mod with modid `thaumicjei`; do not install JEI and HEI together.
4. An existing complete schema-2 cache remains readable. Normal startup loads it read-only; an absent
   cache is generated across client ticks.

## Code-quality changes

- Replaced numeric evaluator continuation states with named phases and focused handlers. The explicit
  resumable stack and tested Thaumcraft priority, shared history, rounding, remainders and fallback
  behaviour are preserved.
- Separated the cache lifecycle from the Forge event facade and added structured generation status.
- Split schema-2 cache parsing, validation, writing and atomic commit. Complete older v2 documents remain
  compatible, including their legacy fingerprint/recipe fields; automatic invalidation remains disabled.
- Replaced untyped recipe lookups with typed crafting, crucible and infusion candidates.
- Published research wrappers as an immutable list and simplified aspect-source wrappers to the active
  snapshot-backed form.
- Removed inactive fingerprint/legacy-import implementations and their four tests; expanded lifecycle,
  persistence and differential coverage to 58 tests.

## Verification

- Full build and differential probe compilation succeeded; **58 tests passed, 0 failed, 0 skipped**.
- All packaged classes are Java 25 bytecode (major 69). The installable JAR contains no tests or
  differential probe.
- In an independent 118-mod test-client copy, the 1280×720 candidate startup loaded the existing cache:
  18,813 items, 22,702 relations and 650 source pages. The evaluator stayed idle (`steps=0`), and the
  cache hash and modification time were unchanged.
- The original test JAR and display settings were restored after that run.

## Validation boundary

This release's actual world session did **not** produce a reliable, target-confirmed R/U result. One R
press opened a Thaumcraft catalyst page, but the screenshot did not establish that the cursor was on the
backpack. Later UI interaction was interrupted by the isolated X server/GLFW restart issue. Therefore the
beta.2 R/U screenshots and 10-target differential report are historical beta.2 evidence, not fresh beta.3
acceptance. No full cold-generation, world R/U, research-page, or package-level differential run was
completed for beta.3.

The test used Mesa llvmpipe software rendering. RSS samples were approximately 6.8 GB at the menu and
8.1–8.4 GB after world/chunk loading while Java heap usage was approximately 2.7–3.5 GB. This is a single
observed run, not a memory or performance benchmark.

Full automated test coverage and the Xvfb test record are in `VERIFICATION-zh.md` and
`docs/testing.md`.
