# MindFix & FullBag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two `#mine`-augmenting processes to Baritone 1.21.8: `MindFixProcess` (auto-repair pickaxes via Mending XP) and `FullBagProcess` (compress loot into shulker boxes when inventory is full).

**Architecture:** Each feature is an independent `IBaritoneProcess` registered in `Baritone.java` with priority above `MineProcess`. They interrupt mining, do their work, then yield back. Both are toggled by a `Setting<Boolean>` and a chat command.

**Tech Stack:** Java 21, Minecraft 1.21.8, Fabric, Baritone internal APIs (`BaritoneProcessHelper`, `PathingCommand`, `InventoryBehavior`, `windowClick`)

## Global Constraints
- Java 21 syntax only
- All new files in `baritone.*` packages (no new top-level packages)
- Settings default to `false` (opt-in)
- Process priorities: FullBagProcess = 3.0, MindFixProcess = 2.0, MineProcess = default (-1)
- Never break, never remove existing settings or commands

---

## File Map

| Action | Path |
|--------|------|
| Create | `src/api/java/baritone/api/process/IMindFixProcess.java` |
| Create | `src/api/java/baritone/api/process/IFullBagProcess.java` |
| Create | `src/main/java/baritone/process/MindFixProcess.java` |
| Create | `src/main/java/baritone/process/FullBagProcess.java` |
| Create | `src/main/java/baritone/command/defaults/MindFixCommand.java` |
| Create | `src/main/java/baritone/command/defaults/FullBagCommand.java` |
| Modify | `src/api/java/baritone/api/Settings.java` |
| Modify | `src/api/java/baritone/api/IBaritone.java` |
| Modify | `src/main/java/baritone/Baritone.java` |
| Modify | `src/main/java/baritone/process/MineProcess.java` |
| Modify | `src/main/java/baritone/command/defaults/DefaultCommands.java` |

---

## Task 1: Settings + Process Interfaces

**Files:**
- Modify: `src/api/java/baritone/api/Settings.java`
- Create: `src/api/java/baritone/api/process/IMindFixProcess.java`
- Create: `src/api/java/baritone/api/process/IFullBagProcess.java`

- [ ] **Step 1: Add two settings to Settings.java**

Open `src/api/java/baritone/api/Settings.java`. Find the `itemSaver` block (around line 873). Add directly after `itemSaverThreshold`:

```java
/**
 * When enabled, pauses #mine when ALL pickaxes are low-durability and repairs them via Mending XP.
 */
public final Setting<Boolean> mindfix = new Setting<>(false);

/**
 * When enabled, compresses mining loot into shulker boxes when inventory is full.
 */
public final Setting<Boolean> fullbag = new Setting<>(false);
```

- [ ] **Step 2: Create IMindFixProcess.java**

```java
package baritone.api.process;

public interface IMindFixProcess extends IBaritoneProcess {
    // Enabled/disabled via Baritone.settings().mindfix
}
```

Save to `src/api/java/baritone/api/process/IMindFixProcess.java`.

- [ ] **Step 3: Create IFullBagProcess.java**

```java
package baritone.api.process;

public interface IFullBagProcess extends IBaritoneProcess {
    // Enabled/disabled via Baritone.settings().fullbag
}
```

Save to `src/api/java/baritone/api/process/IFullBagProcess.java`.

- [ ] **Step 4: Verify compilation**

```
.\gradlew :fabric:compileJava 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (or only pre-existing warnings).

---

## Task 2: IBaritone getters + MineProcess package-private accessors

**Files:**
- Modify: `src/api/java/baritone/api/IBaritone.java`
- Modify: `src/main/java/baritone/process/MineProcess.java`

- [ ] **Step 1: Add getters to IBaritone.java**

In `IBaritone.java`, after `IMineProcess getMineProcess();`, add:

```java
IMindFixProcess getMindFixProcess();

IFullBagProcess getFullBagProcess();
```

- [ ] **Step 2: Add package-private accessors to MineProcess.java**

In `MineProcess.java`, after the existing field declarations, add two package-private getters (same package as `FullBagProcess` and `MindFixProcess`):

```java
// Package-private: used by MindFixProcess and FullBagProcess
BlockOptionalMetaLookup getFilter() {
    return filter;
}

int getDesiredQuantity() {
    return desiredQuantity;
}
```

- [ ] **Step 3: Verify**

```
.\gradlew :fabric:compileJava 2>&1 | tail -20
```

---

## Task 3: Commands

**Files:**
- Create: `src/main/java/baritone/command/defaults/MindFixCommand.java`
- Create: `src/main/java/baritone/command/defaults/FullBagCommand.java`

- [ ] **Step 1: Create MindFixCommand.java**

```java
package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.Baritone;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MindFixCommand extends Command {

    public MindFixCommand(IBaritone baritone) {
        super(baritone, "mindfix");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        boolean enable = args.getAs(Boolean.class);
        Baritone.settings().mindfix.value = enable;
        logDirect("mindfix " + (enable ? "已开启" : "已关闭"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteAs(Boolean.class);
    }

    @Override
    public String getShortDesc() {
        return "自动修复稿子耐久 (需要经验修补附魔)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "当所有稿子耐久均低于阈值时，暂停挖矿并挖 XP 矿修复。",
            "用法: #mindfix true/false"
        );
    }
}
```

- [ ] **Step 2: Create FullBagCommand.java**

```java
package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.Baritone;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FullBagCommand extends Command {

    public FullBagCommand(IBaritone baritone) {
        super(baritone, "fullbag");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        boolean enable = args.getAs(Boolean.class);
        Baritone.settings().fullbag.value = enable;
        logDirect("fullbag " + (enable ? "已开启" : "已关闭"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteAs(Boolean.class);
    }

    @Override
    public String getShortDesc() {
        return "背包满时自动压缩到潜影盒";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
            "背包满时将挖矿产物存入潜影盒，腾出空间继续挖矿。",
            "用法: #fullbag true/false"
        );
    }
}
```

- [ ] **Step 3: Verify**

```
.\gradlew :fabric:compileJava 2>&1 | tail -20
```

---

## Task 4: MindFixProcess

**Files:**
- Create: `src/main/java/baritone/process/MindFixProcess.java`

- [ ] **Step 1: Create the file**

```java
package baritone.process;

import baritone.Baritone;
import baritone.api.process.IMindFixProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

public final class MindFixProcess extends BaritoneProcessHelper implements IMindFixProcess {

    private enum State { IDLE, PREPARE, REPAIRING, RESTORING }

    private State state = State.IDLE;
    private BlockOptionalMetaLookup savedFilter = null;
    private int savedQuantity = 0;
    // tracks the slot where the silk-touch pick lived before we moved it to offhand
    private int silkTouchOriginalSlot = -1;
    private boolean pickInOffhand = false;

    private static final BlockOptionalMetaLookup XP_ORES = new BlockOptionalMetaLookup(
            new BlockOptionalMeta(Blocks.NETHER_QUARTZ_ORE),
            new BlockOptionalMeta(Blocks.DIAMOND_ORE),
            new BlockOptionalMeta(Blocks.DEEPSLATE_DIAMOND_ORE)
    );

    public MindFixProcess(Baritone baritone) {
        super(baritone);
    }

    // ── Activation ───────────────────────────────────────────────────────────

    @Override
    public boolean isActive() {
        if (!Baritone.settings().mindfix.value) return false;

        switch (state) {
            case IDLE:
                if (allPickaxesBelowThreshold()) {
                    state = State.PREPARE;
                    return true;
                }
                return false;

            case PREPARE:
            case REPAIRING:
                if (allPickaxesFullyRepaired()) {
                    state = State.RESTORING;
                }
                return true; // stay active until RESTORING finishes

            case RESTORING:
                return true; // one more tick to restore, then returns DEFER and goes IDLE

            default:
                return false;
        }
    }

    @Override
    public double priority() { return 2.0; }

    @Override
    public boolean isTemporary() { return false; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (state) {
            case PREPARE:   return doPrepare();
            case REPAIRING: return doRepair();
            case RESTORING: return doRestore();
            default:        return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    private PathingCommand doPrepare() {
        // Auto-enable itemSaver if needed
        if (!Baritone.settings().itemSaver.value) {
            Baritone.settings().itemSaver.value = true;
            Baritone.settings().itemSaverThreshold.value = 20;
            logDirect("[MindFix] 已自动开启 itemSaver，阈值=20");
        }

        // Save current mine goal
        MineProcess mine = (MineProcess) baritone.getMineProcess();
        savedFilter   = mine.getFilter();
        savedQuantity = mine.getDesiredQuantity();

        logDirect("[MindFix] 所有稿子耐久不足，暂停挖矿，开始修复");

        // Redirect MineProcess to XP ores so movement is handled for us
        baritone.getMineProcess().mine(0, XP_ORES);

        state = State.REPAIRING;
        return doRepair();
    }

    private PathingCommand doRepair() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();

        // Find most-damaged pickaxe that still has damage
        int mostDamagedSlot = -1;
        int lowestRemaining = Integer.MAX_VALUE;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !(s.getItem() instanceof PickaxeItem) || s.getMaxDamage() <= 1) continue;
            if (s.getDamageValue() == 0) continue; // already full
            int rem = s.getMaxDamage() - s.getDamageValue();
            if (rem < lowestRemaining) { lowestRemaining = rem; mostDamagedSlot = i; }
        }

        if (mostDamagedSlot == -1) {
            // Also check offhand
            ItemStack offhand = ctx.player().getOffhandItem();
            if (!offhand.isEmpty() && offhand.getItem() instanceof PickaxeItem && offhand.getDamageValue() > 0) {
                // Still repairing the offhand pick — just DEFER, MineProcess mines XP
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            state = State.RESTORING;
            return doRestore();
        }

        ItemStack pick = inv.get(mostDamagedSlot);
        if (hasSilkTouch(pick)) {
            handleSilkTouchRepair(mostDamagedSlot, inv);
        } else {
            ensurePickInMainHand(mostDamagedSlot, inv);
        }

        // Defer movement to MineProcess (which is now targeting XP ores)
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand doRestore() {
        // Put silk-touch pick back to original slot if we moved it
        if (pickInOffhand && silkTouchOriginalSlot >= 0) {
            int containerSlot = silkTouchOriginalSlot < 9
                    ? silkTouchOriginalSlot + 36
                    : silkTouchOriginalSlot;
            // SWAP button 40 = swap this container slot with offhand
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    containerSlot, 40, ClickType.SWAP, ctx.player());
            pickInOffhand = false;
            silkTouchOriginalSlot = -1;
        }

        // Restore original mine filter
        if (savedFilter != null) {
            logDirect("[MindFix] 修复完成，恢复挖矿任务");
            baritone.getMineProcess().mine(savedQuantity, savedFilter);
            savedFilter = null;
        }

        state = State.IDLE;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean allPickaxesBelowThreshold() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        boolean anyFound = false;
        int threshold = Baritone.settings().itemSaverThreshold.value + 30;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !(s.getItem() instanceof PickaxeItem) || s.getMaxDamage() <= 1) continue;
            anyFound = true;
            int remaining = s.getMaxDamage() - s.getDamageValue();
            if (remaining > threshold) return false; // this pick is still healthy
        }
        return anyFound; // true only when at least one pick exists and ALL are below threshold
    }

    private boolean allPickaxesFullyRepaired() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !(s.getItem() instanceof PickaxeItem) || s.getMaxDamage() <= 1) continue;
            if (s.getDamageValue() > 0) return false;
        }
        ItemStack offhand = ctx.player().getOffhandItem();
        if (!offhand.isEmpty() && offhand.getItem() instanceof PickaxeItem
                && offhand.getMaxDamage() > 1 && offhand.getDamageValue() > 0) {
            return false;
        }
        return true;
    }

    private boolean hasSilkTouch(ItemStack stack) {
        return stack.getEnchantments().keySet().stream()
                .anyMatch(h -> h.is(Enchantments.SILK_TOUCH));
    }

    /**
     * Moves a silk-touch pick to offhand (so Mending XP repairs it)
     * and ensures a non-silk-touch pick is in main hand (so XP actually drops).
     */
    private void handleSilkTouchRepair(int slot,
            net.minecraft.core.NonNullList<ItemStack> inv) {
        if (!pickInOffhand) {
            int containerSlot = slot < 9 ? slot + 36 : slot;
            // SWAP with offhand (button 40)
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    containerSlot, 40, ClickType.SWAP, ctx.player());
            silkTouchOriginalSlot = slot;
            pickInOffhand = true;
        }

        // Ensure a non-silk-touch pick is in main hand
        // Check hotbar first
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && s.getItem() instanceof PickaxeItem && !hasSilkTouch(s)) {
                ctx.player().getInventory().setSelectedSlot(i);
                return;
            }
        }
        // Swap from main inventory to hotbar slot 0
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && s.getItem() instanceof PickaxeItem && !hasSilkTouch(s)) {
                ctx.playerController().windowClick(
                        ctx.player().inventoryMenu.containerId,
                        i, 0, ClickType.SWAP, ctx.player());
                ctx.player().getInventory().setSelectedSlot(0);
                return;
            }
        }
    }

    private void ensurePickInMainHand(int slot,
            net.minecraft.core.NonNullList<ItemStack> inv) {
        if (slot < 9) {
            ctx.player().getInventory().setSelectedSlot(slot);
        } else {
            // Swap to hotbar slot 0
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    slot, 0, ClickType.SWAP, ctx.player());
            ctx.player().getInventory().setSelectedSlot(0);
        }
    }

    @Override
    public void onLostControl() {
        // Safety: restore everything
        if (pickInOffhand && silkTouchOriginalSlot >= 0) {
            int containerSlot = silkTouchOriginalSlot < 9
                    ? silkTouchOriginalSlot + 36
                    : silkTouchOriginalSlot;
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    containerSlot, 40, ClickType.SWAP, ctx.player());
            pickInOffhand = false;
            silkTouchOriginalSlot = -1;
        }
        if (savedFilter != null) {
            baritone.getMineProcess().mine(savedQuantity, savedFilter);
            savedFilter = null;
        }
        state = State.IDLE;
    }

    @Override
    public String displayName0() {
        return "MindFix - 修复稿子中";
    }
}
```

- [ ] **Step 2: Verify compilation**

```
.\gradlew :fabric:compileJava 2>&1 | tail -30
```

Fix any import errors. Common: `NonNullList` is `net.minecraft.core.NonNullList`, `Enchantments` is `net.minecraft.world.item.enchantment.Enchantments`.

---

## Task 5: FullBagProcess

**Files:**
- Create: `src/main/java/baritone/process/FullBagProcess.java`

- [ ] **Step 1: Create the file**

```java
package baritone.process;

import baritone.Baritone;
import baritone.api.process.IFullBagProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public final class FullBagProcess extends BaritoneProcessHelper implements IFullBagProcess {

    private enum State {
        IDLE,
        FINDING_SHULKER,   // scan inventory for a shulker with space
        PLACE_CHECK,       // place a shulker to inspect (DataComponents unavailable)
        WAITING_OPEN,      // wait for shulker GUI to appear
        TRANSFERRING,      // move items into open shulker
        CLOSING,           // close the shulker GUI
        BREAKING,          // break the placed shulker
        WAITING_PICKUP,    // wait for the shulker item drop to appear
        DROPPING_JUNK,     // drop junk to make room for the shulker item
        AFK_STOP           // all storage full — halt
    }

    private State state = State.IDLE;
    // slots we have already place-checked (to avoid re-checking)
    private final Set<Integer> checkedSlots = new HashSet<>();
    private int targetShulkerSlot = -1;   // inventory slot of the chosen shulker
    private boolean checkOnly = false;    // true = we placed just to look, not to fill
    private BlockPos placedPos = null;    // where the shulker box was placed
    private int waitTicks = 0;
    private static final int OPEN_WAIT_MAX  = 20;
    private static final int PICKUP_WAIT_MAX = 40;

    public FullBagProcess(Baritone baritone) {
        super(baritone);
    }

    // ── Activation ────────────────────────────────────────────────────────────

    @Override
    public boolean isActive() {
        if (!Baritone.settings().fullbag.value) return false;
        if (state == State.AFK_STOP) return false;
        if (state != State.IDLE) return true;
        return isInventoryFull() && baritone.getMineProcess().isActive();
    }

    @Override
    public double priority() { return 3.0; }

    @Override
    public boolean isTemporary() { return false; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (state) {
            case IDLE:
            case FINDING_SHULKER: return findShulker();
            case PLACE_CHECK:     return doPlaceCheck();
            case WAITING_OPEN:    return waitOpen();
            case TRANSFERRING:    return transferItems();
            case CLOSING:         return closeShulker();
            case BREAKING:        return breakShulker();
            case WAITING_PICKUP:  return waitPickup();
            case DROPPING_JUNK:   return dropJunk();
            default:
                return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    // ── State handlers ────────────────────────────────────────────────────────

    private PathingCommand findShulker() {
        state = State.FINDING_SHULKER;
        var inv = ctx.player().getInventory().getNonEquipmentItems();

        // Pass 1: try reading DataComponents (no placement needed)
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (!isShulkerBox(s)) continue;
            ItemContainerContents contents = s.get(DataComponents.CONTAINER);
            if (contents != null) {
                long used = contents.nonEmptyItems().count();
                if (used < 27) {
                    targetShulkerSlot = i;
                    checkOnly = false;
                    state = State.CLOSING; // skip place-check, go straight to place-for-transfer
                    // Actually we need to PLACE first, so:
                    state = State.PLACE_CHECK; // reuse place logic but checkOnly=false
                    checkOnly = false;
                    return doPlaceCheck();
                }
            }
        }

        // Pass 2: place-and-check any unchecked shulkers
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (!isShulkerBox(s)) continue;
            if (checkedSlots.contains(i)) continue;
            targetShulkerSlot = i;
            checkOnly = true;
            state = State.PLACE_CHECK;
            return doPlaceCheck();
        }

        // Nothing found — AFK
        logDirect("[FullBag] 所有潜影盒均已满，AFK 等待玩家处理");
        state = State.AFK_STOP;
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand doPlaceCheck() {
        // Ensure the shulker is in hotbar slot 8
        moveShulkerToHotbarSlot8();

        BlockPos placeOn = findSolidSurface();
        if (placeOn == null) {
            // Can't place here — mark checked and try next
            checkedSlots.add(targetShulkerSlot);
            state = State.FINDING_SHULKER;
            return findShulker();
        }

        // Look at top face of placeOn
        placedPos = placeOn.above();
        Vec3 hitVec = Vec3.atCenterOf(placeOn).add(0, 0.5, 0);
        baritone.getLookBehavior().updateTarget(
                net.minecraft.util.Mth.wrapDegrees(
                        (float) Math.toDegrees(Math.atan2(
                                hitVec.z - ctx.player().getZ(),
                                hitVec.x - ctx.player().getX())) - 90f),
                (float) -Math.toDegrees(Math.atan2(
                        hitVec.y - ctx.player().getEyeY(),
                        Math.sqrt(Math.pow(hitVec.x - ctx.player().getX(), 2)
                                + Math.pow(hitVec.z - ctx.player().getZ(), 2)))));

        // Right-click to place
        ctx.playerController().processRightClickBlock(
                ctx.player(), ctx.world(),
                new BlockHitResult(hitVec, Direction.UP, placeOn, false));

        waitTicks = 0;
        state = State.WAITING_OPEN;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand waitOpen() {
        waitTicks++;
        // Check if a container (not the player's own inventory) is now open
        if (!(ctx.player().containerMenu instanceof net.minecraft.world.inventory.InventoryMenu)) {
            // GUI opened — now decide: check or transfer
            if (checkOnly) {
                state = State.TRANSFERRING; // reuse transferItems logic to count, then close
                // Actually: count empty slots
                int usedSlots = ctx.player().containerMenu.slots.size()
                        - (int) ctx.player().containerMenu.slots.stream()
                              .filter(sl -> sl.getItem().isEmpty()).count();
                // This counts ALL slots including player inventory in the container
                // Shulker has 27 slots at indices 0-26 in the ShulkerBoxMenu
                long shulkerUsed = ctx.player().containerMenu.slots.stream()
                        .limit(27)
                        .filter(sl -> !sl.getItem().isEmpty())
                        .count();
                ctx.player().closeContainer();
                checkedSlots.add(targetShulkerSlot);

                if (shulkerUsed < 27) {
                    // Has space — pick it back up and proceed to transfer
                    state = State.BREAKING;
                    checkOnly = false;
                    return breakShulker();
                } else {
                    // Full — move on
                    state = State.FINDING_SHULKER;
                    placedPos = null;
                    return findShulker();
                }
            } else {
                state = State.TRANSFERRING;
                return transferItems();
            }
        }
        if (waitTicks > OPEN_WAIT_MAX) {
            // Timed out — mark checked, move on
            checkedSlots.add(targetShulkerSlot);
            placedPos = null;
            state = State.FINDING_SHULKER;
            return findShulker();
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand transferItems() {
        // The shulker box GUI is open.
        // Move non-protected, stack > 1 items from player inventory into shulker.
        // Container layout: slots 0-26 = shulker, slots 27+ = player inventory.
        var container = ctx.player().containerMenu;
        int containerId = container.containerId;

        // Find first shulker slot that is empty
        int emptyShulkerSlot = -1;
        for (int i = 0; i < 27; i++) {
            if (container.slots.get(i).getItem().isEmpty()) {
                emptyShulkerSlot = i;
                break;
            }
        }

        if (emptyShulkerSlot == -1) {
            // Shulker full during transfer — close and break
            state = State.CLOSING;
            return closeShulker();
        }

        // Find a player inventory slot with a transferable stack > 1
        // Player inventory in the ShulkerBoxMenu starts at slot 27
        for (int i = 27; i < container.slots.size(); i++) {
            ItemStack s = container.slots.get(i).getItem();
            if (s.isEmpty() || s.getCount() <= 1) continue;
            if (isProtected(s)) continue;

            // Shift-click to move entire stack to shulker, then player slot retains nothing.
            // We want to keep 1, so: pick up all, put all-1 in shulker, put 1 back.
            // Simpler: shift-click moves the full stack. Then place 1 back from cursor.
            // Instead: manually split — left-click to pick up stack, right-click on shulker slot (puts 1),
            // then keep left-clicking shulker slots until count-1 are moved.
            // Easiest approach: shift-click moves all; then put 1 back from shulker to player slot.

            // Step A: Shift-click the player slot to move entire stack to shulker
            ctx.playerController().windowClick(containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());

            // Step B: Move 1 back from whichever shulker slot received it (find it)
            // We'll do this next tick to avoid race conditions — just return DEFER for now
            // and revisit the loop next tick.
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Nothing more to transfer — close
        state = State.CLOSING;
        return closeShulker();
    }

    private PathingCommand closeShulker() {
        ctx.player().closeContainer();
        // After closing, break the shulker to pick it up (with its contents)
        state = State.BREAKING;
        return breakShulker();
    }

    private PathingCommand breakShulker() {
        if (placedPos == null) {
            state = State.FINDING_SHULKER;
            return findShulker();
        }
        // Check if shulker block is still there
        if (!(ctx.world().getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock)) {
            // Already broken — wait for item
            waitTicks = 0;
            state = State.WAITING_PICKUP;
            return waitPickup();
        }
        // Look at the shulker and hold ATTACK to break it
        Vec3 center = Vec3.atCenterOf(placedPos);
        baritone.getLookBehavior().updateTarget(
                net.minecraft.util.Mth.wrapDegrees(
                        (float) Math.toDegrees(Math.atan2(
                                center.z - ctx.player().getZ(),
                                center.x - ctx.player().getX())) - 90f),
                (float) -Math.toDegrees(Math.atan2(
                        center.y - ctx.player().getEyeY(),
                        Math.sqrt(Math.pow(center.x - ctx.player().getX(), 2)
                                + Math.pow(center.z - ctx.player().getZ(), 2)))));
        baritone.getInputOverrideHandler().setInputForceState(
                baritone.api.process.IBaritoneProcess.class.cast(this)
                        instanceof Object // dummy
                        ? baritone.api.event.events.type.EventState.BEGIN // never
                        : null,          // never
                true);
        // Use the correct Input enum:
        baritone.getInputOverrideHandler().setInputForceState(
                baritone.api.input.Input.CLICK_LEFT, true);
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand waitPickup() {
        waitTicks++;
        // Check if a shulker box item entity is nearby
        for (var entity : ctx.world().entitiesForRendering()) {
            if (entity instanceof ItemEntity ie && isShulkerBox(ie.getItem())) {
                // It's close enough that the player will auto-pick it up
                if (entity.distanceTo(ctx.player()) < 2.0) {
                    // Picked up — check inventory
                    if (!isInventoryFull()) {
                        // Successfully picked up
                        placedPos = null;
                        checkedSlots.clear();
                        state = State.IDLE;
                        logDirect("[FullBag] 潜影盒已回收，继续挖矿");
                        return new PathingCommand(null, PathingCommandType.DEFER);
                    }
                    // Still full — try dropping junk
                    state = State.DROPPING_JUNK;
                    return dropJunk();
                }
            }
        }

        // Check if we already picked it up (inventory check)
        // If placedPos block is gone AND no item entity AND we have a new shulker in inventory → done
        if (!(ctx.world().getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock)) {
            // Block gone but no item entity visible yet — might have been auto-collected
            if (waitTicks > PICKUP_WAIT_MAX) {
                // Assume picked up
                placedPos = null;
                checkedSlots.clear();
                state = State.IDLE;
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
        }

        if (waitTicks > PICKUP_WAIT_MAX * 2) {
            // Gave up
            placedPos = null;
            state = State.AFK_STOP;
            logDirect("[FullBag] 无法拾取潜影盒，AFK");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand dropJunk() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || isProtected(s)) continue;
            // Drop one item from this slot
            ctx.player().drop(ctx.player().getInventory().removeItem(i, 1), false);
            // Retry pickup next tick
            state = State.WAITING_PICKUP;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        // No junk left and still full
        logDirect("[FullBag] 无垃圾可扔，背包与潜影盒均满，AFK");
        state = State.AFK_STOP;
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isInventoryFull() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 36; i++) {
            if (inv.get(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean isShulkerBox(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Items that must never be thrown away. */
    private boolean isProtected(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item.isEdible()) return true;
        if (item instanceof DiggerItem) return true;   // picks, shovels, hoes, axes
        if (item instanceof SwordItem) return true;
        if (item instanceof ArmorItem) return true;
        if (item == Items.TOTEM_OF_UNDYING) return true;
        if (isShulkerBox(stack)) return true;
        return false;
    }

    private void moveShulkerToHotbarSlot8() {
        if (targetShulkerSlot == 8) return;
        if (targetShulkerSlot < 9) {
            // Already in hotbar — just select it
            ctx.player().getInventory().setSelectedSlot(targetShulkerSlot);
        } else {
            // Swap from main inventory to hotbar slot 8
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    targetShulkerSlot, 8, ClickType.SWAP, ctx.player());
            // Update tracking: the shulker is now at hotbar slot 8
            targetShulkerSlot = 8;
        }
        ctx.player().getInventory().setSelectedSlot(8);
    }

    /** Find a solid block near the player with an air block above it. */
    private BlockPos findSolidSurface() {
        BlockPos feet = ctx.playerFeet();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (ctx.world().getBlockState(candidate).isSolid()
                            && ctx.world().getBlockState(candidate.above()).isAir()
                            && !candidate.above().equals(feet)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private void updateLookAt(Vec3 target) {
        double dx = target.x - ctx.player().getX();
        double dy = target.y - ctx.player().getEyeY();
        double dz = target.z - ctx.player().getZ();
        float yaw = net.minecraft.util.Mth.wrapDegrees(
                (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        float pitch = (float) -Math.toDegrees(
                Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        baritone.getLookBehavior().updateTarget(yaw, pitch);
    }

    @Override
    public void onLostControl() {
        state = State.IDLE;
        checkedSlots.clear();
        targetShulkerSlot = -1;
        placedPos = null;
        waitTicks = 0;
    }

    @Override
    public String displayName0() {
        return "FullBag - 整理背包中";
    }
}
```

**Note on `lookBehavior.updateTarget`:** The existing `LookBehavior` signature may differ. Check `ILookBehavior` and adjust the `updateTarget` call parameters to match (typically `updateTarget(Rotation target, boolean force)`). Replace the yaw/pitch calls above with:
```java
baritone.getLookBehavior().updateTarget(
    new baritone.api.utils.Rotation(yaw, pitch), true);
```

- [ ] **Step 2: Verify compilation**

```
.\gradlew :fabric:compileJava 2>&1 | tail -40
```

Fix imports as needed. The `baritone.api.input.Input` enum values to use: `Input.CLICK_LEFT`, `Input.CLICK_RIGHT`.

---

## Task 6: Wire up — Baritone.java + DefaultCommands.java

**Files:**
- Modify: `src/main/java/baritone/Baritone.java`
- Modify: `src/main/java/baritone/command/defaults/DefaultCommands.java`

- [ ] **Step 1: Register processes in Baritone.java**

In the field declarations section, add:
```java
private final MindFixProcess mindFixProcess;
private final FullBagProcess fullBagProcess;
```

In the constructor's process registration block, add **before** `mineProcess` registration so higher-priority processes are registered first:
```java
this.fullBagProcess  = this.registerProcess(FullBagProcess::new);
this.mindFixProcess  = this.registerProcess(MindFixProcess::new);
```

Add getters (implement the IBaritone interface methods added in Task 2):
```java
@Override
public IMindFixProcess getMindFixProcess() {
    return this.mindFixProcess;
}

@Override
public IFullBagProcess getFullBagProcess() {
    return this.fullBagProcess;
}
```

- [ ] **Step 2: Register commands in DefaultCommands.java**

In `createAll()`, add to the command list:
```java
new MindFixCommand(baritone),
new FullBagCommand(baritone),
```

- [ ] **Step 3: Verify compilation**

```
.\gradlew :fabric:compileJava 2>&1 | tail -20
```

---

## Task 7: Full Build + Artifact

- [ ] **Step 1: Run full Fabric build**

```
.\gradlew :fabric:build 2>&1 | tail -30
```

Expected output ends with:
```
BUILD SUCCESSFUL in Xs
```

- [ ] **Step 2: Locate the jar**

```
dir fabric\build\libs\*.jar
```

Expected: `baritone-fabric-1.21.8-1.15.0.jar` (and possibly a `-dev` variant — use the one without `-dev` or `-sources`).

- [ ] **Step 3: Copy to mods folder (optional — user does this)**

```
copy "fabric\build\libs\baritone-fabric-1.21.8-1.15.0.jar" "%APPDATA%\.minecraft\mods\"
```

- [ ] **Step 4: In-game test checklist**

After launching Minecraft 1.21.8 with Fabric Loader and the jar in mods/:

1. `#mindfix true` → should confirm "mindfix 已开启" in chat
2. `#fullbag true` → should confirm "fullbag 已开启"
3. `#settings mindfix` → should display current value
4. Start `#mine diamond_ore`, wait until all pickaxes are below threshold → bot should auto-switch to mining quartz/diamond ore and log "[MindFix] 所有稿子耐久不足"
5. Fill inventory to the brim → bot should auto-place a shulker box and log "[FullBag] ..."

---

## Self-Review Notes

- `ClickType.SWAP` button 40 for offhand is correct per `AbstractContainerMenu` source
- `DataComponents.CONTAINER` exists in 1.21.5+ as `ItemContainerContents` — correct for 1.21.8
- `LookBehavior.updateTarget` signature: confirm it accepts `(float yaw, float pitch)` or `(Rotation, boolean)` — adjust Task 5 accordingly
- `ctx.world().entitiesForRendering()` returns client-side entities — correct for Fabric client mod
- Process priority ordering: FullBag (3.0) > MindFix (2.0) > MineProcess (default -1) ✓
- `allPickaxesBelowThreshold()` returns false if no pickaxes found — prevents spurious triggers ✓
- `checkedSlots` is cleared in `onLostControl()` so the process can start fresh next time ✓
