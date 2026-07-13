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
import baritone.api.utils.BlockOptionalMetaLookup;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;

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
        OPENING_SHULKER,  // placed, now right-click the shulker block to open it
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

    // Multi-click transfer state: 0=pick up all, 1=put 1 back, 2=place rest in shulker
    private int transferPhase = 0;
    private int transferSourceSlot = -1;

    // Ticks spent aiming at placement surface before clicking
    private int aimTicks = 0;
    private static final int AIM_TICKS_REQUIRED = 3;

    // Saved allowPlace value — restored after shulker placement
    private boolean savedAllowPlace = true;
    private boolean allowPlaceOverridden = false;

    // Ticks spent trying to find a surface — triggers digging if too long
    private int noSurfaceTicks = 0;
    private static final int NO_SURFACE_DIG_THRESHOLD = 40;

    // Total placement retries — prevents infinite OPENING_SHULKER ↔ PLACE_SHULKER loop
    private int placeRetries = 0;
    private static final int MAX_PLACE_RETRIES = 5;

    // Tick counter for BREAKING timeout (prevent permanent stuck if shulker unreachable)
    private int breakingTicks = 0;
    private static final int BREAKING_TIMEOUT = 80;

    public FullBagProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null) return false;
        if (!Baritone.settings().fullbagEnabled.value) return false;
        // AFK_STOP: check restart condition (no side effects — onTick handles state change)
        if (state == State.AFK_STOP) {
            return !isInventoryFull() || hasShulkerBoxes();
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
                // Handle AFK_STOP → IDLE restart (side-effect-free isActive requires this here)
                if (!isInventoryFull() || hasShulkerBoxes()) {
                    checkedSlots.clear();
                    needsPlaceCheck.clear();
                    placeRetries = 0;
                }
                if (isInventoryFull() && baritone.getMineProcess().isActive()) {
                    logDirect("FullBag: inventory full, searching for shulker box with space");
                    checkedSlots.clear();
                    needsPlaceCheck.clear();
                    placeRetries = 0;
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

                    long usedSlots = com.google.common.collect.Iterables.size(contents.nonEmptyItems());
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
                // Move shulker to hotbar
                moveShulkerToHotbar(activeShulkerSlot);

                // Verify the held item is actually a shulker box before placing
                int heldSlot = ctx.player().getInventory().getSelectedSlot();
                ItemStack heldItem = ctx.player().getInventory().getNonEquipmentItems().get(heldSlot);
                if (!isShulkerBox(heldItem)) {
                    logDirect("FullBag: held item is not a shulker box, re-finding");
                    checkedSlots.clear();
                    needsPlaceCheck.clear();
                    state = State.FINDING_SHULKER;
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }

                BlockPos surface = findSolidSurface();
                if (surface == null) {
                    noSurfaceTicks++;
                    if (noSurfaceTicks >= NO_SURFACE_DIG_THRESHOLD) {
                        // No surface after waiting — try to dig a block to make space
                        BlockPos digTarget = findDiggableBlock();
                        if (digTarget != null) {
                            logDirect("FullBag: no surface, digging block at " + digTarget + " to make space");
                            noSurfaceTicks = 0;
                            Optional<Rotation> digRot = RotationUtils.reachable(ctx, digTarget);
                            if (digRot.isPresent()) {
                                baritone.getLookBehavior().updateTarget(digRot.get(), true);
                                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                            }
                        } else {
                            logDirect("FullBag: cannot find space to place shulker, stopping");
                            state = State.AFK_STOP;
                            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                        }
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                noSurfaceTicks = 0;
                placedPos = surface.above();

                // Override allowPlace so Baritone doesn't block placement
                if (!allowPlaceOverridden) {
                    savedAllowPlace = Baritone.settings().allowPlace.value;
                    Baritone.settings().allowPlace.value = true;
                    allowPlaceOverridden = true;
                }

                // Use processRightClickBlock directly — bypasses camera rotation requirement
                net.minecraft.world.phys.BlockHitResult hitResult =
                    new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(surface)
                            .add(0, 0.5, 0),
                        net.minecraft.core.Direction.UP,
                        surface,
                        false
                    );
                ctx.playerController().processRightClickBlock(
                    ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hitResult);
                waitTicks = 0;
                aimTicks = 0;
                state = State.OPENING_SHULKER;
                logDirect("FullBag: placed shulker at " + placedPos + ", waiting for block to appear");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case OPENING_SHULKER: {
                // Ensure left-click is not held (would break the shulker instead of opening it)
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

                // GUI already opened — skip ahead
                if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                    waitTicks = 0;
                    state = State.WAITING_OPEN;
                    return onTick(calcFailed, isSafeToCancel);
                }
                if (placedPos != null && ctx.world().getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock) {
                    // Keep right-clicking the placed shulker every tick until GUI opens
                    net.minecraft.world.phys.BlockHitResult openHit =
                        new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(placedPos),
                            net.minecraft.core.Direction.UP,
                            placedPos,
                            false
                        );
                    ctx.playerController().processRightClickBlock(
                        ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, openHit);
                    waitTicks++;
                    if (waitTicks > 40) {
                        waitTicks = 0;
                        placeRetries++;
                        if (placeRetries >= MAX_PLACE_RETRIES) {
                            logDirect("FullBag: shulker won't open after " + MAX_PLACE_RETRIES + " retries, stopping");
                            state = State.AFK_STOP;
                        } else {
                            logDirect("FullBag: shulker not opening, retry " + placeRetries + "/" + MAX_PLACE_RETRIES);
                            state = State.PLACE_SHULKER;
                        }
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > 20) {
                        waitTicks = 0;
                        placeRetries++;
                        if (placeRetries >= MAX_PLACE_RETRIES) {
                            logDirect("FullBag: shulker block never appeared after " + MAX_PLACE_RETRIES + " retries, stopping");
                            state = State.AFK_STOP;
                        } else {
                            logDirect("FullBag: shulker block not found, retry " + placeRetries + "/" + MAX_PLACE_RETRIES);
                            state = State.PLACE_SHULKER;
                        }
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case WAITING_OPEN: {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                // Restore allowPlace now that placement attempt is done
                if (allowPlaceOverridden) {
                    Baritone.settings().allowPlace.value = savedAllowPlace;
                    allowPlaceOverridden = false;
                }

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
                            // Shulker still in world at placedPos — right-click again to open it
                            state = State.OPENING_SHULKER;
                        } else {
                            logDirect("FullBag: shulker is full, breaking and continuing search");
                            state = State.BREAKING;
                        }
                    } else {
                        state = State.TRANSFERRING;
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                waitTicks++;
                if (waitTicks > 60) {
                    logDirect("FullBag: timed out waiting for shulker to open, retrying right-click");
                    waitTicks = 0;
                    state = State.OPENING_SHULKER; // retry open, not re-place
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case TRANSFERRING: {
                // Shulker is open; player inventory slots are 27+ in the container
                if (ctx.player().containerMenu instanceof InventoryMenu) {
                    // Container closed unexpectedly
                    logDirect("FullBag: container closed during transfer, moving to breaking");
                    state = State.BREAKING;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                BlockOptionalMetaLookup mineFilter = ((MineProcess) baritone.getMineProcess()).getFilter();
                // Check if ANY non-protected item in inventory matches the filter
                // If mineFilter is valid but nothing matches, it means the items in inventory
                // don't match what we're mining (e.g. filter was redirected). Fall back to null-filter behavior.
                if (mineFilter != null) {
                    boolean anyMatch = false;
                    for (int ci = 27; ci < ctx.player().containerMenu.slots.size(); ci++) {
                        ItemStack cs = ctx.player().containerMenu.getSlot(ci).getItem();
                        if (!cs.isEmpty() && !isProtected(cs) && mineFilter.has(cs)) { anyMatch = true; break; }
                    }
                    if (!anyMatch) {
                        logDirect("FullBag: filter matched no inventory items, closing");
                        ctx.minecraft().setScreen(null);
                        state = State.BREAKING;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
                if (mineFilter == null) {
                    logDirect("FullBag: no mine filter active, closing shulker");
                    ctx.minecraft().setScreen(null);
                    state = State.BREAKING;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // 3-phase: pick up → put 1 back → deposit rest in shulker
                // Phase 1: put 1 back to source player slot (right-click)
                if (transferPhase == 1 && transferSourceSlot >= 0) {
                    ctx.playerController().windowClick(containerId, transferSourceSlot, 1, ClickType.PICKUP, ctx.player());
                    transferPhase = 2;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Phase 2: deposit remaining cursor items into best shulker slot
                if (transferPhase == 2 && transferSourceSlot >= 0) {
                    // Figure out what item we're holding by looking at source slot (now has 1)
                    ItemStack sourceItem = ctx.player().containerMenu.getSlot(transferSourceSlot).getItem();
                    int targetShulkerSlot = -1;
                    // Prefer a partial shulker slot with the same item that can accept the cursor
                    for (int s = 0; s < 27; s++) {
                        ItemStack shulkerSlot = ctx.player().containerMenu.getSlot(s).getItem();
                        if (!shulkerSlot.isEmpty()
                                && ItemStack.isSameItemSameComponents(shulkerSlot, sourceItem)
                                && shulkerSlot.getCount() < shulkerSlot.getMaxStackSize()) {
                            targetShulkerSlot = s;
                            break;
                        }
                    }
                    // Fall back to first empty shulker slot
                    if (targetShulkerSlot < 0) {
                        for (int s = 0; s < 27; s++) {
                            if (ctx.player().containerMenu.getSlot(s).getItem().isEmpty()) {
                                targetShulkerSlot = s;
                                break;
                            }
                        }
                    }
                    if (targetShulkerSlot >= 0) {
                        ctx.playerController().windowClick(containerId, targetShulkerSlot, 0, ClickType.PICKUP, ctx.player());
                    }
                    transferPhase = 0;
                    transferSourceSlot = -1;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Phase 0: find next mine-target player slot to transfer
                for (int containerSlot = 27; containerSlot < ctx.player().containerMenu.slots.size(); containerSlot++) {
                    ItemStack stack = ctx.player().containerMenu.getSlot(containerSlot).getItem();
                    if (stack.isEmpty()) continue;
                    if (isProtected(stack)) continue;
                    if (!mineFilter.has(stack)) continue;

                    if (stack.getCount() > 1) {
                        // Multi-item stack: use 3-phase to keep 1 in player inventory
                        ctx.playerController().windowClick(containerId, containerSlot, 0, ClickType.PICKUP, ctx.player());
                        transferSourceSlot = containerSlot;
                        transferPhase = 1;
                    } else {
                        // Single item: QUICK_MOVE directly (can't leave 1, just transfer all)
                        ctx.playerController().windowClick(containerId, containerSlot, 0, ClickType.QUICK_MOVE, ctx.player());
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Nothing left to transfer
                logDirect("FullBag: transfer complete, closing shulker");
                transferPhase = 0;
                transferSourceSlot = -1;
                ctx.minecraft().setScreen(null);
                state = State.BREAKING;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
                    breakingTicks = 0;
                } else {
                    breakingTicks++;
                    if (breakingTicks >= BREAKING_TIMEOUT) {
                        logDirect("FullBag: shulker unreachable for breaking (" + BREAKING_TIMEOUT + " ticks), giving up");
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                        placedPos = null;
                        breakingTicks = 0;
                        state = State.IDLE;
                        return new PathingCommand(null, PathingCommandType.DEFER);
                    }
                    logDirect("FullBag: placed shulker not reachable (" + breakingTicks + "/" + BREAKING_TIMEOUT + ")");
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case WAITING_PICKUP: {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

                // Find the dropped shulker box ItemEntity
                BlockPos dropPos = null;
                for (Entity entity : ((net.minecraft.client.multiplayer.ClientLevel) ctx.world()).entitiesForRendering()) {
                    if (entity instanceof ItemEntity ie && isShulkerBox(ie.getItem())) {
                        double dist = entity.distanceTo(ctx.player());
                        if (dist <= 8.0) {
                            // Use actual entity coords (not block position) to avoid being blocked by walls
                            dropPos = new BlockPos(
                                (int) Math.floor(ie.getX()),
                                (int) Math.floor(ie.getY()),
                                (int) Math.floor(ie.getZ())
                            );
                            break;
                        }
                    }
                }

                if (dropPos == null) {
                    // No shulker ItemEntity visible — check if block is also gone
                    boolean blockGone = placedPos == null ||
                        !(ctx.world().getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock);
                    if (blockGone) {
                        // Shulker was picked up (by bot or player)
                        logDirect("FullBag: shulker picked up, resuming mine");
                        placedPos = null;
                        activeShulkerSlot = -1;
                        checkedSlots.clear();
                        needsPlaceCheck.clear();
                        waitTicks = 0;
                        state = State.IDLE;
                        return new PathingCommand(null, PathingCommandType.DEFER);
                    }
                    waitTicks++;
                    if (waitTicks > 40) {
                        logDirect("FullBag: timed out waiting for shulker pickup");
                        waitTicks = 0;
                        placedPos = null;
                        state = State.IDLE;
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Navigate to block center (GoalBlock = x.0000, z.0000) for reliable pickup
                waitTicks = 0;
                return new PathingCommand(
                    new baritone.api.pathing.goals.GoalBlock(dropPos),
                    PathingCommandType.SET_GOAL_AND_PATH
                );
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
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
        // Don't reset AFK_STOP — prevents immediate re-trigger when inventory is still full
        if (state != State.AFK_STOP) {
            state = State.IDLE;
        }
        placedPos = null;
        activeShulkerSlot = -1;
        checkedSlots.clear();
        needsPlaceCheck.clear();
        waitTicks = 0;
        aimTicks = 0;
        noSurfaceTicks = 0;
        breakingTicks = 0;
        placeRetries = 0;
        checkOnly = false;
        transferPhase = 0;
        transferSourceSlot = -1;
        if (allowPlaceOverridden) {
            Baritone.settings().allowPlace.value = savedAllowPlace;
            allowPlaceOverridden = false;
        }
    }

    private boolean hasShulkerBoxes() {
        var inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            if (isShulkerBox(inv.get(i))) return true;
        }
        return false;
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
        BlockOptionalMetaLookup mineFilter = ((MineProcess) baritone.getMineProcess()).getFilter();

        // Single pass: empty slot or a partial stack that can accept more mine drops → not full
        for (int i = 0; i < 36; i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty()) return false;
            if (mineFilter != null && slot.getCount() < slot.getMaxStackSize() && mineFilter.has(slot)) {
                return false;
            }
        }
        return true;
    }

    private boolean isProtected(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var item = stack.getItem();
        if (stack.has(DataComponents.FOOD)) return true;
        if (stack.has(DataComponents.TOOL)) return true;   // picks, shovels, hoes, axes (component-based, 1.21+)
        if (stack.is(ItemTags.SWORDS)) return true;
        if (stack.has(DataComponents.EQUIPPABLE)) return true;  // armor
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
            // Swap main-inventory slot with hotbar slot 8
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    slot, 8, ClickType.SWAP, ctx.player()
            );
            activeShulkerSlot = 8; // update tracking: shulker is now at hotbar slot 8
            ctx.player().getInventory().setSelectedSlot(8);
        } else {
            // Already in hotbar — select it directly
            ctx.player().getInventory().setSelectedSlot(slot);
        }
    }

    /**
     * Finds a solid surface near the player where the block is solid and the block above is air.
     * Returns the position of the solid block (shulker will be placed on top).
     */
    private BlockPos findSolidSurface() {
        BlockPos feet = ctx.playerFeet();
        // Check adjacent blocks to player feet and slightly around
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    BlockState bs = ctx.world().getBlockState(candidate);
                    BlockState above = ctx.world().getBlockState(candidate.above());
                    BlockState above2 = ctx.world().getBlockState(candidate.above().above());

                    if (bs.isFaceSturdy(ctx.world(), candidate, net.minecraft.core.Direction.UP)
                            && above.isAir() && above2.isAir()) {
                        BlockPos abovePos = candidate.above();
                        if (!abovePos.equals(feet) && !abovePos.equals(feet.above())) {
                            // Only return if the player can actually reach this surface (not through walls)
                            if (RotationUtils.reachable(ctx, candidate).isPresent()) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds a breakable non-air block near the player to create space for shulker placement.
     */
    private BlockPos findDiggableBlock() {
        BlockPos feet = ctx.playerFeet();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = feet.offset(dx, 0, dz);
                BlockState bs = ctx.world().getBlockState(candidate);
                // Only dig blocks that are solid and not bedrock
                if (bs.isSolid() && !bs.isAir()) {
                    return candidate;
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
