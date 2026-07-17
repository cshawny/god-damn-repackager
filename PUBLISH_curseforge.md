<!--
  ════════════════════════════════════════════════════════════════════════
  项目级文档(跨分支共享) — English publish copy for CurseForge/Modrinth
  ─────────────────────────
  This file describes BOTH the Forge 1.20.1 and NeoForge 1.21.1 builds and must
  be IDENTICAL across the `master` (forge) and `neoforge-1.21.1` branches.

  After editing one side, cherry-pick / manually sync to the other. Both builds'
  info must be present (differences go in the "Supported platforms" / "Installation"
  tables below).

  Branch-specific dev docs are AGENTS.md and TECHNICAL.md (independent per branch).
  ════════════════════════════════════════════════════════════════════════
-->

# CurseForge Mod Description (English — copy directly into the Description editor)

> CurseForge's Description field supports Markdown. Paste the content below.

---

## Summary (the one-line summary for the file preview page — paste into the "Summary" field)

> Lets multiple Repackagers sharing one input vault process a single large crafting order in parallel — up to N× faster order fulfillment with N repackagers.

---

## Description (main body — paste into the Description editor)

# God Damn Repackager

> **⚠️ ALPHA**
> This mod is in early testing. It uses a Mixin to modify Create's repackager core logic and
> has not yet undergone large-scale, long-term stability testing. **Back up your world before
> using it.** Bug reports are welcome on the project page.
>
> **ℹ️ If you used 0.2.0:** 0.2.0 could cause "only some repackagers work after placing an order"
> (e.g. 6 of 9) in an existing world, requiring you to re-place the repackagers. **Fixed in 0.2.1** —
> just upgrade, no re-placement needed. See [Known Limitations](#known-limitations).

**God Damn Repackager** makes all repackagers attached to the same input vault share the work.
N repackagers ≈ N× throughput. **Available for both Forge 1.20.1 and NeoForge 1.21.1.**

## Supported platforms & versions

This mod is maintained for two MC / loader combinations with identical functionality. Pick the
jar that matches your setup.

| | Forge build | NeoForge build |
|---|---|---|
| **Minecraft** | 1.20.1 | 1.21.1 |
| **Loader** | Forge 47.x | NeoForge 21.1.x |
| **Create (required dep)** | 6.0.x (tested 6.0.8) | 6.0.x (tested 6.0.10) |
| **Mod version** | `0.5.0-forge` | `0.4.0-neoforge` |
| **Jar filename** | `goddamnrepackager-0.5.0-forge-alpha.jar` | `goddamnrepackager-0.4.0-neoforge-alpha.jar` |

> Choose based on whether your pack runs Forge or NeoForge.
> If you're unsure: NeoForge 1.21.1 is recommended for new/single-player packs (more modern);
> stick with Forge 1.20.1 for existing saves or Forge-based packs.
>
> **Version note:** 0.5.0's new **partial repackaging** (start crafting as soon as any materials
> arrive — see below) is currently available in the Forge build only; the NeoForge port follows.
> The shared-pool parallelism is identical on both builds.

## The Problem

In vanilla Create 6.0+, when you place a large crafting order through the Stockkeeper for your
crafter array (e.g. "craft 1000 iron blocks"), **the entire order is processed by a single
Repackager**. Even if your input vault is surrounded by repackagers, only one of them does any
work — the rest sit idle. A repackager emits only one package per second (20-tick animation
cycle), so large orders take a very long time.

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

**Partial repackaging (new in 0.5.0, Forge build for now):** vanilla repackagers wait until ALL of an
order's material fragments have arrived before doing anything (all-or-nothing). Fragments trickle in
over the transport network, so large orders waste a long "waiting for the last piece" window. 0.5.0
lets repackagers start as soon as the arrived fragments afford at least one craft: available materials
are crafted and shipped immediately, and each newly arrived batch continues the order until it's done.
Materials are conserved end-to-end — unused leftovers are held in the world save and drop in full if
the vault is broken.

> **Note on breaking blocks (0.4.0):** the shared pool is saved with the world, independent of any block.
> Breaking a repackager does **not** drop the packages still in the pool — they're kept safely in the save,
> and placing the repackager back resumes processing (nothing is lost). Only when the **vault itself is
> destroyed (block broken or wrench-removed)** are that vault's pooled packages dropped as item entities.
> Reshaping a vault (adding/removing blocks to change its shape) does **not** drop the pool either — the pool
> migrates to the new shape automatically and repackagers keep processing. Repackagers respect vanilla Create
> redstone: they only work when powered.

## Installation

Installation is identical for both builds — only the jar and Create version differ:

1. Install the matching **Minecraft + loader** combo (see [Supported platforms](#supported-platforms--versions) above)
2. Install **Create 6.0.x** (required dependency; version per the table above)
3. Drop the matching `goddamnrepackager-*.jar` into `.minecraft/mods/`
4. Launch the game

> **Upgrading from 0.2.0?** Just replace the jar. 0.2.0 used to require re-placing repackagers in an
> existing world; **0.2.1 fixed this** — no re-placement needed after upgrade. See
> [Known Limitations](#known-limitations).
>
> **Upgrading from 0.3.x / 0.4.x?** Just replace the jar — no re-placement needed, in-flight orders
> are unaffected. 0.5.0 adds partial repackaging on top of the shared pool (start crafting as soon
> as any materials arrive); you benefit automatically with zero setup.

## Compatibility

- ✅ Tested:
  - MC 1.20.1 + Forge 47.2.0 + Create 6.0.8
  - MC 1.21.1 + NeoForge 21.1.x + Create 6.0.10
- ✅ Tested in modpack environments and on multiplayer servers alongside other mods — no conflicts
- ⚠️ Targets the Create 6.0.x logistics system only; not compatible with Create 0.5.1 and earlier
- ⚠️ Forge 1.20.1 and NeoForge 1.21.1 are maintained independently with identical features; a Fabric
  port may come later

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
  nothing is lost. Only destroying the vault itself (breaking a block or wrench-removing) drops that
  vault's pooled packages. Reshaping a vault (adding/removing blocks) does NOT drop — the pool migrates
  to the new shape and processing continues.
- **An order being progressively crafted needs at least one powered repackager (0.5.0).** Once an
  order is "taken over" (partial repackaging has begun), it needs at least one redstone-powered
  repackager on the vault to keep progressing. Removing all repackagers or cutting their power
  pauses the order — unused materials stay safely in the world save (and drop if the vault is
  broken); place a repackager back and power it to resume. Orders without crafting recipes
  (plain item orders) are never taken over and behave exactly like vanilla.
- Repackagers must be attached to a Create Vault. Other containers (Crates, vanilla chests) are
  theoretically supported but not fully tested.

## License

MIT License — free to use, modify, and distribute. Source code and a full technical writeup
(architecture, dev pitfalls, roadmap) are on the project GitHub.

## Credits

- [Create](https://www.curseforge.com/minecraft/mc-mods/create) and its author simibubi —
  an outstanding mod that this project builds upon.
