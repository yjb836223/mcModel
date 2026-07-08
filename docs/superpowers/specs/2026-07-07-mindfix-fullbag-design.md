# Design: MindFix & FullBag — Baritone 1.21.8 Fabric

## Overview

Two new optional features extending Baritone's `#mine` command, each controlled by a dedicated setting and chat command.

---

## Feature 1: MindFix (`#mindfix true/false`)

### Purpose
During `#mine`, automatically pause and repair all pickaxes via Mending XP before they hit the `itemSaver` threshold. Prevents the bot from being left with no usable pickaxes mid-session.

### Trigger Condition
- Setting `mindfix` must be `true`
- **All** pickaxes in inventory must have remaining durability ≤ `itemSaverThreshold + 30`
- If any single pickaxe is still healthy, do NOT trigger
- If `itemSaver` is not enabled when triggered: auto-enable it and set `itemSaverThreshold = 20`

### Repair Flow (state machine)

```
IDLE
  └─ [all picks low] → PREPARE
       └─ save current MineProcess goal
       └─ auto-enable itemSaver if needed
       └─ for each pickaxe (sorted by durability, lowest first):
            REPAIR_PICK
              ├─ has Silk Touch? → move to off-hand (record original slot index)
              │                    find non-SilkTouch pick → place in main hand
              └─ no Silk Touch?  → place in main hand
            MINING_XP
              └─ mine NETHER_QUARTZ_ORE / DIAMOND_ORE / DEEPSLATE_DIAMOND_ORE
              └─ skip drop-collection logic (repairMode flag)
              └─ monitor durability each tick until getDamageValue() == 0
            RESTORE_SLOT
              └─ move repaired pick back to its recorded original slot
  └─ [all picks full] → DONE → restore original MineProcess goal → IDLE
```

### Silk Touch Handling
- Detect with `EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0`
- Silk Touch pickaxe goes to **off-hand** (Mending repairs off-hand items from XP)
- A non-Silk-Touch pickaxe is used in **main hand** to mine ores (so XP actually drops)
- After repair: `InventoryBehavior` slot-swap to return pick to its recorded slot index

### Drop Collection
- Internally reuses MineProcess ore-seeking logic with a boolean flag `repairMode = true`
- When `repairMode` is true, the drop-seeking pathfinding step is skipped entirely

### Files
| Action | File |
|--------|------|
| New | `src/main/java/baritone/process/MindFixProcess.java` |
| New | `src/main/java/baritone/command/defaults/MindFixCommand.java` |
| Modify | `src/api/java/baritone/api/Settings.java` — add `mindfix: Setting<Boolean>(false)` |
| Modify | `src/api/java/baritone/api/IBaritone.java` — add `getMindFixProcess()` |
| Modify | `src/main/java/baritone/Baritone.java` — register process, priority between MineProcess and FullBagProcess |
| Modify | `src/main/java/baritone/command/defaults/DefaultCommands.java` — register command |

---

## Feature 2: FullBag (`#fullbag true/false`)

### Purpose
During `#mine`, when inventory is completely full, compress mining loot into shulker boxes to free space and continue. If no space can be found anywhere, AFK.

### Priority
**FullBagProcess has the highest priority** — if both `fullbag` and `mindfix` trigger simultaneously, `fullbag` runs first.

### Trigger Condition
- Setting `fullbag` must be `true`
- All 36 inventory slots are occupied
- MineProcess is currently running

### Execution Flow (state machine)

```
FIND_SHULKER
  └─ Try reading DataComponents.CONTAINER from each shulker box stack
  └─ If unreadable → place each shulker box one at a time:
       track checked slots with Set<Integer> checkedSlots (no repeats)
       place → open → count empty slots → close → pick back up → next
  └─ No shulker box has space → AFK_STOP

PLACE_SHULKER
  └─ Place the chosen shulker box on a nearby solid surface

OPEN_AND_TRANSFER
  └─ Right-click open shulker box
  └─ Identify target ore items (from MineProcess saved BlockOptionalMeta → drop type)
  └─ For each target ore stack: keep 1, shift-click remainder into shulker box
  └─ Close container

BREAK_SHULKER
  └─ Use Baritone block-breaking logic to mine the shulker box

PICKUP
  └─ Path to dropped shulker box item and pick up
  └─ Inventory full? → DROP_JUNK

DROP_JUNK
  └─ Loop inventory, drop one item at a time (player.drop())
  └─ Never drop:
       - Food          (item.isEdible())
       - Tools         (item instanceof DiggerItem)
       - Swords        (item instanceof SwordItem)
       - Axes          (item instanceof AxeItem)
       - Armor         (item instanceof ArmorItem)
       - Shulker boxes (block instanceof ShulkerBoxBlock)
       - Totem         (item == Items.TOTEM_OF_UNDYING)
  └─ After each drop: retry picking up shulker box
  └─ No junk left but still full → AFK_STOP

RESUME
  └─ All shulker box picked up → restore MineProcess goal → IDLE

AFK_STOP
  └─ All shulker boxes full + no junk to drop
  └─ logDirect("背包与潜影盒均已满，请手动处理")
  └─ isActive() returns false, all processes stop
```

### Shulker Box Content Reading
- Primary: `stack.get(DataComponents.CONTAINER)` → `ItemContainerContents` → count occupied slots vs 27
- Fallback: place-and-open loop with `Set<Integer> checkedSlots` to prevent re-checking

### Item Transfer Details
- Target ore type resolved from `MineProcess.desiredBlocks` → map to expected drop item type
- Per stack: use existing `ContainerClick` / `InventoryBehavior` slot operations
- Leave exactly 1 item per stack slot in main inventory

### Files
| Action | File |
|--------|------|
| New | `src/main/java/baritone/process/FullBagProcess.java` |
| New | `src/main/java/baritone/command/defaults/FullBagCommand.java` |
| Modify | `src/api/java/baritone/api/Settings.java` — add `fullbag: Setting<Boolean>(false)` |
| Modify | `src/api/java/baritone/api/IBaritone.java` — add `getFullBagProcess()` |
| Modify | `src/main/java/baritone/Baritone.java` — register with highest priority |
| Modify | `src/main/java/baritone/command/defaults/DefaultCommands.java` — register command |

---

## Process Priority Order (high → low)
1. `FullBagProcess`
2. `MindFixProcess`
3. `MineProcess` (existing)
4. other existing processes...

## Build & Test
- Build: `.\gradlew :fabric:build`
- Output: `fabric\build\libs\baritone-fabric-1.21.8-1.15.0.jar`
- Drop into `.minecraft\mods\` with Fabric Loader for 1.21.8
