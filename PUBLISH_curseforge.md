# CurseForge Mod Description (English — copy directly into the Description editor)

> CurseForge's Description field supports Markdown. Paste the content below.

---

## Summary (the one-line summary for the file preview page — paste into the "Summary" field)

> Lets multiple Repackagers sharing one input vault process a single large crafting order in parallel — up to N× faster order fulfillment with N repackagers.

---

## Description (main body — paste into the Description editor)

# God Damn Repackager

> **⚠️ ALPHA (0.4.0)**
> This mod is in early testing. It uses a Mixin to modify Create's repackager core logic and
> has not yet undergone large-scale, long-term stability testing. **Back up your world before
> using it.** Bug reports are welcome on the project page.
>
> **ℹ️ If you used 0.2.0:** 0.2.0 could cause "only some repackagers work after placing an order"
> (e.g. 6 of 9) in an existing world, requiring you to re-place the repackagers. **Fixed in 0.2.1** —
> just upgrade, no re-placement needed. See [Known Limitations](#known-limitations).

## The Problem

In vanilla Create 6.0+, when you place a large crafting order through the Stockkeeper for your
crafter array (e.g. "craft 1000 iron blocks"), **the entire order is processed by a single
Repackager**. Even if your input vault is surrounded by repackagers, only one of them does any
work — the rest sit idle. A repackager emits only one package per second (20-tick animation
cycle), so large orders take a very long time.

**God Damn Repackager** makes all repackagers attached to the same input vault share the work.
N repackagers ≈ N× throughput.

## Speed Comparison

| Scenario | Vanilla | With this mod |
|---|---|---|
| 1000 crafts, 1 repackager | ~1000s | ~1000s (unchanged) |
| 1000 crafts, 3 repackagers | ~1000s (2 idle) | ~333s |
| 1000 crafts, 9 repackagers | ~1000s (8 idle) | ~111s |

## Usage

**No configuration required — works out of the box.** Build your crafter array as usual:

```
Stockkeeper ──order──> Frogport ships materials ──> Input Vault (holds material packages)
                                                          ↓
                                            Multiple Repackagers (redstone block = always on)
                                                          ↓
                                            Packager (unwraps) → Mechanical Crafter → Output
```

As long as multiple repackagers are attached to the same input vault, this mod automatically
parallelizes them. Repackagers must be placed against a Create **Vault**.

## How it works

Instead of each repackager hoarding an entire order in its own send queue, **0.4.0 uses a per-vault shared
package pool** (stored in the world save):

- **Deposit** — when a repackager finishes assembling an order's packages, the whole batch goes into the
  shared pool keyed by the vault it serves, rather than into its own private queue.
- **Poll on demand** — every tick, each *idle* repackager pulls one package out of the pool into its own
  queue, then ships it as normal. N repackagers genuinely ship N packages/second.
- **Inherently dynamic** — because each repackager pulls work on demand, a stalled repackager (its
  downstream clogged) simply stops polling and its work is naturally picked up by idle siblings. No
  separate rebalance layer is needed.

> **Note on breaking blocks (0.4.0):** the shared pool is saved with the world, independent of any block.
> Breaking a repackager does **not** drop the packages still in the pool — they're kept safely in the save,
> and placing the repackager back resumes processing (nothing is lost). Only when the **vault itself is
> destroyed (block broken or wrench-removed)** are that vault's pooled packages dropped as item entities.
> Reshaping a vault (adding/removing blocks to change its shape) does **not** drop the pool either — the pool
> migrates to the new shape automatically and repackagers keep processing. Repackagers respect vanilla Create
> redstone: they only work when powered.

## Installation

1. Minecraft 1.20.1 + Forge 47.x
2. Install **Create 6.0.x** (required dependency; tested with 6.0.8)
3. Drop the jar into `.minecraft/mods/`

> **Upgrading from 0.2.0?** Just replace the jar. 0.2.0 used to require re-placing repackagers in an
> existing world; **0.2.1 fixed this** — no re-placement needed after upgrade. See
> [Known Limitations](#known-limitations).

## Compatibility

- ✅ Tested: MC 1.20.1 + Forge 47.2.0 + Create 6.0.8
- ✅ Tested in modpack environments and on multiplayer servers alongside other mods — no conflicts
- ⚠️ Targets the Create 6.0.x logistics system only; not compatible with Create 0.5.1 and earlier
- ⚠️ Forge only (a Fabric port may come later)

## Known Limitations

- ~~**Re-place repackagers after installing into an existing world.**~~ **(Fixed in 0.2.1)** 0.2.0 could
  cause "only some repackagers work after placing an order" (e.g. 6 of 9) in a world that already existed —
  far more often on multiplayer servers than in fresh single-player worlds. Cause: 0.2.0 identified sibling
  repackagers by the identity (`==`) of the Forge capability instance they cached, which is rebuilt whenever
  the vault's capability is invalidated, so repackagers placed before the mod existed could hold caches
  pointing at different generations and fail the check. **0.2.1 fix:** siblings are now matched by Create's
  `InventoryIdentifier` value equality (for vaults: a `Bounds(BoundingBox)` record comparing only the
  multiblock's corner coordinates), which is stable across capability rebuilds. **Upgrading to 0.2.1 resolves
  this — no re-placement needed.** (Technical detail in TECHNICAL.md §3.7.)
- ~~The current implementation is "load-balanced snapshot allocation"...~~ **(0.3.0 added dynamic
  rebalancing on top; 0.4.0 replaced both with a shared package pool)** — 0.4.0 deposits each
  assembled batch into a per-vault shared pool that idle repackagers poll from on demand, giving the
  same parallel/dynamic-balancing effect with simpler logic.
- **Breaking a repackager does NOT drop the shared pool (0.4.0).** The pool is saved with the world,
  not tied to the block. Breaking a repackager only drops the single package it was mid-shipping
  (heldBox); packages still in the pool stay in the save and resume when the repackager is replaced —
  nothing is lost. Only destroying/reshaping the vault itself drops that vault's pooled packages.
- Repackagers must be attached to a Create Vault. Other containers (Crates, vanilla chests) are
  theoretically supported but not fully tested.

## License

MIT License — free to use, modify, and distribute. Source code and a full technical writeup
(architecture, dev pitfalls, roadmap) are on the project GitHub.

## Credits

- [Create](https://www.curseforge.com/minecraft/mc-mods/create) and its author simibubi —
  an outstanding mod that this project builds upon.
