# Task 5 Brief: FullBagProcess

## Context
Baritone 1.21.8 Fabric mod. Working directory: C:\Users\jibiyang\Downloads\baritone-1.19.4

Previous tasks complete:
- IFullBagProcess interface exists at src/api/java/baritone/api/process/IFullBagProcess.java
- Settings.fullbag (Boolean, default false) exists
- Baritone.java has getFullBagProcess() returning null (stub — must be replaced)
- MindFixProcess registered at priority 2.0

## Global Constraints
- Java 21 syntax only
- Package: baritone.process
- Process priority: 3.0 (highest — higher than MindFix 2.0)
- All log messages in English only

## Read First
Before writing, read these files:
1. src/main/java/baritone/process/MindFixProcess.java — understand the established pattern
2. src/main/java/baritone/utils/BaritoneProcessHelper.java — understand ctx
3. src/api/java/baritone/api/process/PathingCommand.java
4. src/main/java/baritone/Baritone.java — understand current state (getFullBagProcess stub)

## What To Do

### Step 1: Create src/main/java/baritone/process/FullBagProcess.java

Implement the shulker box compression process. Full state machine:

**isActive():** returns true when:
- Settings.fullbag.value == true
- State != AFK_STOP
- State != IDLE → always active if in a non-idle state
- State == IDLE → only if inventory is full (all 36 slots occupied) AND MineProcess is active

**States:**
```
IDLE → FINDING_SHULKER → [found with DataComponents] → PLACE_SHULKER → WAITING_OPEN → TRANSFERRING → CLOSING → BREAKING → WAITING_PICKUP → IDLE
                       → [need place-check] → PLACE_CHECK → WAITING_OPEN(checkOnly) → BREAKING → WAITING_PICKUP(checkOnly) → FINDING_SHULKER
                       → [all full] → AFK_STOP
WAITING_PICKUP → [inventory still full after pickup] → DROPPING_JUNK → WAITING_PICKUP
DROPPING_JUNK → [no junk to drop] → AFK_STOP
```

**Key implementation details:**

1. isShulkerBox(ItemStack): `stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock`

2. isInventoryFull(): check all 36 slots of getNonEquipmentItems() are non-empty

3. Finding shulker with space:
   - Try DataComponents first: `stack.get(DataComponents.CONTAINER)` returns ItemContainerContents
   - Count used: `contents.nonEmptyItems().count()` < 27 means has space
   - If DataComponents null for a shulker, add to "needs place check" list
   - Track checked slots in Set<Integer> checkedSlots to avoid re-checking

4. Placing shulker:
   - moveShulkerToHotbar(slot): if slot >= 9, use windowClick SWAP to hotbar slot 8; then setSelectedSlot(8)
   - findSolidSurface(): find BlockPos near player where block is solid and block above is air
   - Look at the surface and right-click to place (use Input.CLICK_RIGHT via getInputOverrideHandler)
   - Store placedPos

5. Detecting open container: `!(ctx.player().containerMenu instanceof InventoryMenu)`

6. Transferring items (shulker open, slots 0-26 are shulker, 27+ are player inventory):
   - For each player inventory slot (container index 27+):
     - If item is not protected AND stack count > 1:
       - QUICK_MOVE (shift-click) to move all but 1: do a pick-up then split
       - Actually simpler: use pickup click to get all, then right-click in shulker slot (puts 1 back in cursor → but that leaves cursor)
       - SIMPLEST: shift-click moves entire stack to shulker. Then use PICKUP on the shulker slot to get all, PICKUP on player slot to put all-1 back... complex.
       - BEST APPROACH: left-click player slot (picks up all), then right-click shulker empty slot (puts 1 in shulker), then left-click player slot again (puts remainder back). This keeps 1 in shulker, rest in player.
       - But this needs 3 clicks spread across ticks. Use a sub-state.
       - ALTERNATIVE: just use QUICK_MOVE (shift-click) which moves whole stack. It's fine — the point is to free inventory space. The "keep 1" is nice-to-have.
       - USE QUICK_MOVE for simplicity. Do one QUICK_MOVE per tick, return DEFER.

7. Breaking shulker:
   - Look at placedPos using getLookBehavior().updateTarget(new Rotation(yaw, pitch), true)
   - Check ILookBehavior signature before using
   - Use getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true)
   - Each tick, check if block is no longer ShulkerBoxBlock → transition to WAITING_PICKUP

8. Waiting for pickup:
   - Look for ItemEntity near player that is a shulker box
   - If player walks over it, it auto-picks up
   - If inventory was full when dropped, need to drop junk first

9. isProtected(ItemStack) — NEVER throw these:
   - item.isEdible()
   - item instanceof DiggerItem (covers picks, shovels, axes, hoes)
   - item instanceof SwordItem
   - item instanceof ArmorItem
   - item == Items.TOTEM_OF_UNDYING
   - isShulkerBox(stack)

10. AFK_STOP: just cancel pathing and log message, isActive returns false

**Important API notes:**
- ILookBehavior.updateTarget may take Rotation object: check src/api/java/baritone/api/behavior/ILookBehavior.java
- Input enum is at baritone.api.input.Input
- InventoryMenu is net.minecraft.world.inventory.InventoryMenu
- ItemEntity is net.minecraft.world.entity.item.ItemEntity
- DataComponents is net.minecraft.core.component.DataComponents
- ItemContainerContents is net.minecraft.world.item.component.ItemContainerContents

### Step 2: Update Baritone.java

Replace the null stub for getFullBagProcess():
1. Add field: `private final FullBagProcess fullBagProcess;`
2. In constructor, register BEFORE mindFixProcess: `this.fullBagProcess = this.registerProcess(FullBagProcess::new);`
3. Replace the stub getter with: `return this.fullBagProcess;`

The registration order should be: fullBagProcess, mindFixProcess, mineProcess, ... (highest priority first)

### Step 3: Verify compilation
Run: `.\gradlew :fabric:compileJava --no-daemon`
Wait for completion. Fix actual Java errors. NEXTHINK kill = environment issue, proceed anyway.

### Step 4: Commit
"feat: add FullBagProcess and wire up in Baritone.java"

## Report File
C:\Users\jibiyang\Downloads\baritone-1.19.4\.superpowers\sdd\task-5-report.md

Include: API corrections made, compilation result, commit hash.

End with: DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, or BLOCKED
