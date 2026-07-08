/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.process.IFullBagProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FullBagProcess: automatically compresses inventory into shulker boxes when mining fills the bag.
 * Priority 3.0 (highest), higher than MindFix 2.0.
 */
public final class FullBagProcess extends BaritoneProcessHelper implements IFullBagProcess {

    private enum State {
        IDLE,
        FINDING_SHULKER,
        PLACE_CHECK,
        PLACE_SHULKER,
        WAITING_OPEN,
        TRANSFERRING,
        CLOSING,
        BREAKING,
        WAITING_PICKUP,
        DROPPING_JUNK,
        AFK_STOP
    }

    private State state = State.IDLE;

    // Position where shulker was placed
    private BlockPos placedPos = null;

    // Slot index in getNonEquipmentItems() of the shulker we are working with
    private int activeShulkerSlot = -1;

    // Slots whose data components have been read (no DataComponents = needs place check)
    private final Set<Integer> checkedSlots = new HashSet<>();

    // Slots that were found to need place-checking (no DataComponents available)
    private final List<Integer> needsPlaceCheck = new ArrayList<>();

    // Whether current WAITING_OPEN is for check-only (PLACE_CHECK path)
    private boolean checkOnly = false;

    // Tick counter for timeouts
    private int waitTicks = 0;

    public FullBagProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (!Baritone.settings().fullbag.value) {
            return false;
        }
        if (state == State.AFK_STOP) {
            return false;
        }
        if (state != State.IDLE) {
            return true;
        }
        // IDLE: only active if inventory full AND mine process is active
        return isInventoryFull() && baritone.getMineProcess().isActive();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (state) {
            case IDLE: {
                if (isInventoryFull() && baritone.getMineProcess().isActive()) {
                    logDirect("FullBag: inventory full, searching for shulker box with space");
                    checkedSlots.clear();
                    needsPlaceCheck.clear();
                    state = State.FINDING_SHULKER;
                    return onTick(calcFailed, isSafeToCancel);
                }
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case FINDING_SHULKER: {
                NonNullList<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();
                int foundSlot = -1;
                boolean allFull = true;

                for (int i = 0; i < items.size(); i++) {
                    ItemStack stack = items.get(i);
                    if (!isShulkerBox(stack)) continue;
                    if (checkedSlots.contains(i)) continue;

                    checkedSlots.add(i);

                    // Try DataComponents
                    ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
                    if (contents == null) {
                        // No data = needs place check to inspect
                        needsPlaceCheck.add(i);
                        continue;
                    }

                    long usedSlots = contents.nonEmptyItems().count();
                    if (usedSlots < 27) {
                        // This shulker has space
                        foundSlot = i;
                        allFull = false;
                        break;
                    }
                    // This shulker is full, keep looking
                }

                if (foundSlot >= 0) {
                    logDirect("FullBag: found shulker with space at slot " + foundSlot);
                    activeShulkerSlot = foundSlot;
                    checkOnly = false;
                    state = State.PLACE_SHULKER;
                    return onTick(calcFailed, isSafeToCancel);
                }

                // Check if there are slots that need place-check
                if (!needsPlaceCheck.isEmpty()) {
                    activeShulkerSlot = needsPlaceCheck.remove(0);
                    logDirect("FullBag: shulker at slot " + activeShulkerSlot + " needs place-check");
                    checkOnly = true;
                    state = State.PLACE_CHECK;
                    return onTick(calcFailed, isSafeToCancel);
                }

                // All shulkers checked and all are full
                logDirect("FullBag: all shulker boxes are full, stopping");
                state = State.AFK_STOP;
                return onTick(calcFailed, isSafeToCancel);
            }

            case PLACE_CHECK: {
                // Place shulker just to check its contents
                state = State.PLACE_SHULKER;
                return onTick(calcFailed, isSafeToCancel);
            }

            case PLACE_SHULKER: {
                // Move shulker to hotbar and place it
                moveShulkerToHotbar(activeShulkerSlot);

                BlockPos surface = findSolidSurface();
                if (surface == null) {
                    logDirect("FullBag: no solid surface found to place shulker");
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                placedPos = surface.above();

                // Look at the surface top face and right-click to place
                Optional<Rotation> rot = RotationUtils.reachable(ctx, surface);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    waitTicks = 0;
                    state = State.WAITING_OPEN;
                    logDirect("FullBag: placing shulker at " + placedPos);
                } else {
                    logDirect("FullBag: surface not reachable for placement");
                }
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case WAITING_OPEN: {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

                // Check if a container menu opened (not the player's inventory menu)
                if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                    logDirect("FullBag: shulker opened");
                    waitTicks = 0;
                    if (checkOnly) {
                        // We opened it just to check; now close and break it
                        // Check if the shulker has space
                        int containerId = ctx.player().containerMenu.containerId;
                        // Container slots 0-26 are shulker; count empty ones
                        long used = 0;
                        for (int s = 0; s < 27; s++) {
                            if (!ctx.player().containerMenu.getSlot(s).getItem().isEmpty()) {
                                used++;
                            }
                        }
                        ctx.playerController().windowClick(containerId, -999, 0, ClickType.PICKUP, ctx.player());
                        // Close the screen
                        ctx.minecraft().setScreen(null);
                        if (used < 27) {
                            logDirect("FullBag: shulker has space (" + used + "/27 used), switching to transfer mode");
                            checkOnly = false;
                            // Re-open for transferring — transition to PLACE_SHULKER to re-place the same slot
                            // Actually we need to re-open it; it's still at placedPos after we close
                            // Let's go back to PLACE_SHULKER to right-click it again
                            state = State.PLACE_SHULKER;
                        } else {
                            logDirect("FullBag: shulker is full, breaking and continuing search");
                            state = State.BREAKING;
                        }
                    } else {
                        state = State.TRANSFERRING;
                    }
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                waitTicks++;
                if (waitTicks > 60) {
                    logDirect("FullBag: timed out waiting for shulker to open, retrying placement");
                    waitTicks = 0;
                    state = State.PLACE_SHULKER;
                }
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case TRANSFERRING: {
                // Shulker is open; player inventory slots are 27+ in the container
                if (ctx.player().containerMenu instanceof InventoryMenu) {
                    // Container closed unexpectedly
                    logDirect("FullBag: container closed during transfer, moving to breaking");
                    state = State.BREAKING;
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                int containerId = ctx.player().containerMenu.containerId;
                // Shulker slots are 0-26, player inventory starts at 27
                // Check if shulker is full
                long shulkerUsed = 0;
                for (int s = 0; s < 27; s++) {
                    if (!ctx.player().containerMenu.getSlot(s).getItem().isEmpty()) {
                        shulkerUsed++;
                    }
                }

                if (shulkerUsed >= 27) {
                    logDirect("FullBag: shulker is now full, closing");
                    ctx.minecraft().setScreen(null);
                    state = State.BREAKING;
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                // Find a player slot to transfer (container slot 27 onward = player inventory)
                // Container slots: 27-53 = player main inv (9-35 in inv), 54-62 = hotbar (0-8 in inv)
                // Actually layout varies by shulker but typically: 0-26 shulker, 27-53 player inv, 54-62 hotbar
                for (int containerSlot = 27; containerSlot < ctx.player().containerMenu.slots.size(); containerSlot++) {
                    ItemStack stack = ctx.player().containerMenu.getSlot(containerSlot).getItem();
                    if (stack.isEmpty()) continue;
                    if (isProtected(stack)) continue;

                    // QUICK_MOVE (shift-click) to move entire stack to shulker
                    ctx.playerController().windowClick(containerId, containerSlot, 0, ClickType.QUICK_MOVE, ctx.player());
                    logDirect("FullBag: transferred stack from container slot " + containerSlot);
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                // Nothing left to transfer
                logDirect("FullBag: transfer complete, closing shulker");
                ctx.minecraft().setScreen(null);
                state = State.BREAKING;
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case CLOSING: {
                // Legacy state — forward to BREAKING
                state = State.BREAKING;
                return onTick(calcFailed, isSafeToCancel);
            }

            case BREAKING: {
                if (placedPos == null) {
                    state = State.WAITING_PICKUP;
                    return onTick(calcFailed, isSafeToCancel);
                }

                BlockState bs = ctx.world().getBlockState(placedPos);
                if (!(bs.getBlock() instanceof ShulkerBoxBlock)) {
                    logDirect("FullBag: shulker broken, waiting for pickup");
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    state = State.WAITING_PICKUP;
                    return onTick(calcFailed, isSafeToCancel);
                }

                // Look at the placed shulker and left-click to break
                Optional<Rotation> rot = RotationUtils.reachable(ctx, placedPos);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                } else {
                    logDirect("FullBag: placed shulker not reachable for breaking");
                }
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case WAITING_PICKUP: {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

                // Check if there are item entities nearby that are shulker boxes
                boolean shulkerEntityNearby = false;
                for (Entity entity : ((net.minecraft.client.multiplayer.ClientLevel) ctx.world()).entitiesForRendering()) {
                    if (entity instanceof ItemEntity ie) {
                        double dist = entity.distanceTo(ctx.player());
                        if (dist <= 5.0 && isShulkerBox(ie.getItem())) {
                            shulkerEntityNearby = true;
                            break;
                        }
                    }
                }

                if (!shulkerEntityNearby) {
                    // Shulker was picked up or doesn't exist anymore
                    if (isInventoryFull()) {
                        logDirect("FullBag: inventory still full after pickup, dropping junk");
                        state = State.DROPPING_JUNK;
                        return onTick(calcFailed, isSafeToCancel);
                    }
                    // Inventory has space — go back to searching
                    placedPos = null;
                    activeShulkerSlot = -1;
                    state = State.FINDING_SHULKER;
                    return onTick(calcFailed, isSafeToCancel);
                }

                waitTicks++;
                if (waitTicks > 100) {
                    // Been waiting too long
                    logDirect("FullBag: timed out waiting for shulker pickup");
                    waitTicks = 0;
                    placedPos = null;
                    state = State.IDLE;
                }
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case DROPPING_JUNK: {
                NonNullList<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();
                boolean droppedAny = false;

                for (int i = 0; i < items.size(); i++) {
                    ItemStack stack = items.get(i);
                    if (stack.isEmpty()) continue;
                    if (isProtected(stack)) continue;

                    // Drop this item stack
                    int containerSlot = slotToContainerSlot(i);
                    ctx.playerController().windowClick(
                            ctx.player().inventoryMenu.containerId,
                            containerSlot, 1, ClickType.THROW, ctx.player()
                    );
                    logDirect("FullBag: dropped junk from slot " + i);
                    droppedAny = true;
                    break; // one per tick
                }

                if (!droppedAny) {
                    logDirect("FullBag: nothing left to drop, stopping");
                    state = State.AFK_STOP;
                    return onTick(calcFailed, isSafeToCancel);
                }

                state = State.WAITING_PICKUP;
                return new PathingCommand(null, PathingCommandType.DEFER);
            }

            case AFK_STOP: {
                baritone.getPathingBehavior().cancelEverything();
                logDirect("FullBag: all shulkers full and no junk to drop. Stopping.");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            default:
                return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    @Override
    public void onLostControl() {
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        state = State.IDLE;
        placedPos = null;
        activeShulkerSlot = -1;
        checkedSlots.clear();
        needsPlaceCheck.clear();
        waitTicks = 0;
        checkOnly = false;
    }

    @Override
    public double priority() {
        return 3.0;
    }

    @Override
    public String displayName0() {
        return "FullBag [" + state + "]";
    }

    // ---- Helper methods ----

    private boolean isShulkerBox(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isInventoryFull() {
        NonNullList<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) return false;
        }
        return true;
    }

    private boolean isProtected(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var item = stack.getItem();
        if (stack.has(DataComponents.FOOD)) return true;
        if (item instanceof DiggerItem) return true;
        if (item instanceof SwordItem) return true;
        if (item instanceof ArmorItem) return true;
        if (item == Items.TOTEM_OF_UNDYING) return true;
        if (isShulkerBox(stack)) return true;
        return false;
    }

    /**
     * Moves the shulker from the given getNonEquipmentItems() slot index to hotbar slot 8,
     * then selects hotbar slot 8.
     */
    private void moveShulkerToHotbar(int slot) {
        if (slot >= 9) {
            // Swap with hotbar slot 8 (container slot 44)
            int containerSlot = slot; // slots 9-35 map directly to container slots 9-35
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    containerSlot, 8, ClickType.SWAP, ctx.player()
            );
        }
        // Select hotbar slot 8
        ctx.player().getInventory().setSelectedSlot(8);
    }

    /**
     * Finds a solid surface near the player where the block is solid and the block above is air.
     * Returns the position of the solid block (shulker will be placed on top).
     */
    private BlockPos findSolidSurface() {
        BlockPos feet = new BlockPos(
                (int) Math.floor(ctx.player().position().x),
                (int) Math.floor(ctx.player().position().y),
                (int) Math.floor(ctx.player().position().z)
        );
        // Check adjacent blocks to player feet and slightly around
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    BlockState bs = ctx.world().getBlockState(candidate);
                    BlockState above = ctx.world().getBlockState(candidate.above());
                    BlockState above2 = ctx.world().getBlockState(candidate.above().above());

                    if (bs.isSolid() && above.isAir() && above2.isAir()) {
                        // Make sure this isn't where the player is standing (avoid blocking)
                        BlockPos abovePos = candidate.above();
                        if (!abovePos.equals(feet) && !abovePos.equals(feet.above())) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Converts a getNonEquipmentItems() slot index to a player inventory container slot index.
     * Hotbar slots 0-8 → container slots 36-44
     * Main inventory slots 9-35 → container slots 9-35
     */
    private int slotToContainerSlot(int invSlot) {
        if (invSlot < 9) {
            return invSlot + 36;
        } else {
            return invSlot;
        }
    }
}
