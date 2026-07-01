# CurseForge Mod Description (English — copy directly into the Description editor)

> CurseForge's Description field supports Markdown. Paste the content below.

---

## Summary (the one-line summary for the file preview page — paste into the "Summary" field)

> Lets multiple Repackagers sharing one input vault process a single large crafting order in parallel — up to N× faster order fulfillment with N repackagers.

---

## Description (main body — paste into the Description editor)

# God Damn Repackager

> **⚠️ ALPHA (0.2.0)**
> This mod is in early testing. It uses a Mixin to modify Create's repackager core logic and
> has not yet undergone large-scale, long-term stability testing. **Back up your world before
> using it.** Bug reports are welcome on the project page.

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

## Installation

1. Minecraft 1.20.1 + Forge 47.x
2. Install **Create 6.0.x** (required dependency; tested with 6.0.8)
3. Drop the jar into `.minecraft/mods/`

## Compatibility

- ✅ Tested: MC 1.20.1 + Forge 47.2.0 + Create 6.0.8
- ✅ Tested in modpack environments and on multiplayer servers alongside other mods — no conflicts
- ⚠️ Targets the Create 6.0.x logistics system only; not compatible with Create 0.5.1 and earlier
- ⚠️ Forge only (a Fabric port may come later)

## Known Limitations

- The current implementation is "load-balanced snapshot allocation": at the moment a repackager
  assembles an order's packages, it decides who gets what based on current queue depth. This is
  sufficient for the vast majority of real cases; in extreme edge cases a repackager that goes
  idle *after* allocation won't "steal" work from another's queue.
- Repackagers must be attached to a Create Vault. Other containers (Crates, vanilla chests) are
  theoretically supported but not fully tested.

## License

MIT License — free to use, modify, and distribute. Source code and a full technical writeup
(architecture, dev pitfalls, roadmap) are on the project GitHub.

## Credits

- [Create](https://www.curseforge.com/minecraft/mc-mods/create) and its author simibubi —
  an outstanding mod that this project builds upon.
