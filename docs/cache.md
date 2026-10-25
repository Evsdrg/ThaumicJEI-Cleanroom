# Fixed cache

The default policy is **generate once, reuse read-only**. It deliberately trades exact agreement with
every Thaumcraft launch for predictable query cost.

## File and lifecycle

The cache is `config/thaumicjei-cache-v2.json` in the game directory. Schema 2 stores item identities,
aspect amounts and source relations; the document is marked complete only after a full generation.

| Starting state | Behaviour |
|---|---|
| No cache file | Generate automatically if `generateAspectCache` is enabled. |
| Complete schema-2 file | Import it in budgeted slices and publish the snapshot. |
| Incomplete file | Report it and ask for a manual rebuild; do not publish it. |
| Invalid or unsupported file | Report the problem; do not silently overwrite it. |

Loading does **not** compute a startup fingerprint, compare recipe signatures, invalidate changed
recipes, backfill unknown items or rewrite the file automatically.

After adding or removing mods, or changing recipes, run `/thaumicjei rebuild` if you want a new cache.
Until then the existing values remain the displayed values.

## Commands

```text
/thaumicjei status   # current lifecycle state and progress
/thaumicjei pause    # pause generation
/thaumicjei resume   # resume a paused generation
/thaumicjei cancel   # discard the in-progress generation
/thaumicjei rebuild  # capture a new recipe index and start over
```

During rebuild the old snapshot, source pages and disk file remain available. A new snapshot is
published only after all items finish with zero skips. Persistence uses a temporary file, `fsync` and
an atomic same-filesystem rename; a commit lock and generation checks protect the final rename from a
late cancellation.

There are no partial checkpoints. Cancelling the first generation means the next launch starts from
the beginning.

## Configuration

- `generateAspectCache` controls automatic generation **only when no cache exists**. It does not
  disable `/thaumicjei rebuild`.
- `alwaysRecreateAspectFromItemStackFile` is an ignored legacy compatibility field.
- Blacklist and cache-size settings still limit what enters a generated cache; see the generated mod
  configuration for the active defaults.

## Exact-NBT behaviour

Tooltips and source lookups use exact item/NBT identities. A newly created item whose NBT was not in
the generated cache simply has no extra aspect display. It does not trigger a background derivation
and does not borrow values from a similar item.

The mod does not write Thaumcraft's global `objectTags`; cached values are for ThaumicJEI display and
lookup, not a replacement for Thaumcraft mechanics.

## Operational advice

1. Back up the old cache before replacing it.
2. Generate once in a quiet test world, then keep the complete file for normal play.
3. Rebuild deliberately after pack changes, not on every launch.
4. Keep only one mod with modid `thaumicjei`, and do not install JEI and HEI together.

Multiplayer is out of scope: this is a client-only mod with no custom network protocol.
