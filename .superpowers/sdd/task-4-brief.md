# Task 4 Brief: MindFixProcess

## Context
Baritone 1.21.8 Fabric mod. Working directory: C:\Users\jibiyang\Downloads\baritone-1.19.4

Previous tasks complete:
- IMindFixProcess interface exists at src/api/java/baritone/api/process/IMindFixProcess.java
- Settings.mindfix (Boolean, default false) exists in Settings.java
- MineProcess.getFilter() and MineProcess.getDesiredQuantity() are package-private accessors

## Global Constraints
- Java 21 syntax only
- Package: baritone.process
- Process priority: 2.0 (higher than MineProcess default -1, lower than FullBagProcess 3.0)
- All log messages in English (do NOT use Chinese in log messages)

## What To Do

### Step 1: Read key source files first

Read these files to understand the APIs before writing:
1. src/main/java/baritone/process/MineProcess.java — understand how mine() is called, field names
2. src/api/java/baritone/api/process/IBaritoneProcess.java — understand the interface
3. src/main/java/baritone/utils/BaritoneProcessHelper.java — understand base class and ctx usage
4. src/api/java/baritone/api/process/PathingCommand.java and PathingCommandType — understand return types

### Step 2: Create src/main/java/baritone/process/MindFixProcess.java

The process implements auto-repair of pickaxes using Mending XP from mining.

Key logic:
- isActive(): returns true ONLY when mindfix setting is true AND ALL pickaxes in inventory have remaining durability <= (itemSaverThreshold + 30). If no pickaxes exist, return false.
- State machine: IDLE → PREPARE → REPAIRING → RESTORING → IDLE
- PREPARE: auto-enable itemSaver if not enabled (set to true, threshold=20), save current MineProcess filter+quantity, redirect MineProcess to mine XP ores (NETHER_QUARTZ_ORE, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE)
- REPAIRING: each tick, find most-damaged pickaxe. If it has Silk Touch enchantment: move it to offhand (using ClickType.SWAP button=40 on its container slot), find a non-SilkTouch pickaxe for main hand. If no Silk Touch: put it in main hand. Return DEFER to let MineProcess handle movement.
- RESTORING: move silk-touch pick back to original slot if it was moved to offhand, restore MineProcess to original filter

For Silk Touch detection in MC 1.21.8:
```java
stack.getEnchantments().keySet().stream().anyMatch(h -> h.is(Enchantments.SILK_TOUCH))
```

For container slot calculation:
- Hotbar slot i (0-8) → container slot i+36
- Main inventory slot i (9-35) → container slot i
- Offhand → use ClickType.SWAP with button=40

For windowClick to move to offhand:
```java
ctx.playerController().windowClick(
    ctx.player().inventoryMenu.containerId,
    containerSlot, 40, ClickType.SWAP, ctx.player());
```

For checking if all pickaxes fully repaired:
- getDamageValue() == 0 means no damage (full durability)
- Also check offhand item if it's a pickaxe

allPickaxesBelowThreshold() must return false if no pickaxes found.

onLostControl(): restore silk-touch pick to original slot if moved, restore MineProcess filter if saved.

### Step 3: Verify compilation

Run and WAIT for completion:
```
.\gradlew :fabric:compileJava --no-daemon
```

If it fails with actual Java errors (not NEXTHINK kill), fix the errors and retry.
If killed by NEXTHINK, note it as environment issue and proceed to commit anyway.

### Step 4: Commit

"feat: add MindFixProcess"

## Report File
C:\Users\jibiyang\Downloads\baritone-1.19.4\.superpowers\sdd\task-4-report.md

Include: any API corrections made vs the plan, compilation result, commit hash.

End with: DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, or BLOCKED
