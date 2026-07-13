/*
 * This file is part of Baritone.
 *
 * Baritone is free software; see LICENSE for details.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.process.IMindFixProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;

/**
 * MindFixProcess — repairs Mending-enchanted pickaxes during #mine.
 *
 * Phase 1 (any Mending pick ≤ threshold+30):
 *   Elevate itemSaverThreshold so Baritone naturally skips low picks via getBestSlot().
 *   Mining continues uninterrupted with a healthier pick.
 *
 * Phase 2 (ALL Mending picks ≤ threshold+30):
 *   Redirect MineProcess to XP ores (diamond/quartz) to trigger Mending repairs.
 *   Manage which pick is in offhand vs main hand explicitly.
 *
 * Done: when no Mending pick has remaining ≤ threshold+30 → restore everything.
 */
public final class MindFixProcess extends BaritoneProcessHelper implements IMindFixProcess {

    private enum State { IDLE, REPAIRING, RESTORING }

    private State state = State.IDLE;

    // MineProcess state saved at Phase 2 entry, restored on exit
    private BlockOptionalMetaLookup savedFilter = null;
    private int savedDesiredQuantity = 0;

    // All Baritone settings saved once on first activation, restored on cleanup()
    private boolean settingsSaved = false;
    private boolean savedItemSaver;
    private int savedItemSaverThreshold;
    private boolean savedAutoTool;
    private boolean savedMineScanDroppedItems;

    // Silk touch pick in offhand (moves there so non-silk pick can mine XP ores)
    private boolean silkTouchInOffhand = false;
    private int silkTouchOriginalSlot = -1;

    // Low-durability pick in offhand (remaining ≤ savedItemSaverThreshold — can't mine)
    private boolean lowDurabilityInOffhand = false;
    private int lowDurabilityOriginalSlot = -1;

    // The non-silk, Mending, above-saver-threshold pick currently being held for XP repair
    private int currentRepairSlot = -1;

    private static final BlockOptionalMetaLookup XP_ORES = new BlockOptionalMetaLookup(
            Blocks.NETHER_QUARTZ_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE
    );

    public MindFixProcess(Baritone baritone) {
        super(baritone);
    }

    // ── Activation ────────────────────────────────────────────────────────────

    @Override
    public boolean isActive() {
        if (ctx.player() == null) return false;
        if (!Baritone.settings().mindfixEnabled.value) {
            if (settingsSaved) cleanup();
            return false;
        }
        if (state == State.REPAIRING || state == State.RESTORING) return true;
        // IDLE: only active when a Mending pick needs attention and #mine is running
        return anyMendingPickBelowThreshold()
                && baritone.getMineProcess().isActive()
                && !baritone.getFullBagProcess().isActive();
    }

    @Override
    public double priority() { return 2.0; }

    @Override
    public boolean isTemporary() { return false; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (state) {
            case IDLE:      return tickIdle();
            case REPAIRING: return tickRepairing();
            case RESTORING: return tickRestoring();
            default:        return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    private PathingCommand tickIdle() {
        // Save all settings exactly once when we first become active
        if (!settingsSaved) {
            savedItemSaver            = Baritone.settings().itemSaver.value;
            savedItemSaverThreshold   = Baritone.settings().itemSaverThreshold.value;
            savedAutoTool             = Baritone.settings().autoTool.value;
            savedMineScanDroppedItems = Baritone.settings().mineScanDroppedItems.value;
            settingsSaved = true;
        }

        int triggerThreshold = savedItemSaverThreshold + 30;

        // Phase 1: Raise itemSaverThreshold so Baritone's getBestSlot() skips low picks
        Baritone.settings().itemSaver.value          = true;
        Baritone.settings().itemSaverThreshold.value = triggerThreshold;

        if (allMendingPicksBelowThreshold(triggerThreshold)) {
            // Phase 2: ALL Mending picks are low — redirect to XP ore mining
            // Restore threshold to user's original so picks at 21–50 can still mine XP ores
            Baritone.settings().itemSaverThreshold.value  = savedItemSaverThreshold;
            Baritone.settings().autoTool.value            = false;
            Baritone.settings().mineScanDroppedItems.value = false;

            MineProcess mine = (MineProcess) baritone.getMineProcess();
            savedFilter          = mine.getFilter();
            savedDesiredQuantity = mine.getDesiredQuantity();
            mine.mine(0, XP_ORES);

            logDirect("MindFix: all Mending picks low, redirecting to XP ore mining");
            state = State.REPAIRING;
            return tickRepairing();
        }

        // Phase 1 only: itemSaver handles pick avoidance automatically — just defer
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand tickRepairing() {
        // Safety: if MineProcess stopped (path failure, #stop), exit gracefully
        if (!baritone.getMineProcess().isActive()) {
            logDirect("MindFix: MineProcess no longer active, stopping repair");
            state = State.RESTORING;
            return tickRestoring();
        }

        int triggerThreshold = savedItemSaverThreshold + 30;
        if (!anyMendingPickBelowThreshold(triggerThreshold)) {
            logDirect("MindFix: all Mending picks repaired, restoring");
            state = State.RESTORING;
            return tickRestoring();
        }

        manageSilkTouchPick();
        manageLowDurabilityPick();
        ensureRepairPickInMainHand();

        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand tickRestoring() {
        // Return silk touch pick to its original slot
        if (silkTouchInOffhand) {
            swapOffhandWithSlot(silkTouchOriginalSlot);
            silkTouchInOffhand    = false;
            silkTouchOriginalSlot = -1;
        }
        // Return low-durability pick to its original slot
        if (lowDurabilityInOffhand) {
            swapOffhandWithSlot(lowDurabilityOriginalSlot);
            lowDurabilityInOffhand    = false;
            lowDurabilityOriginalSlot = -1;
        }
        // Restore MineProcess to original mining target
        if (savedFilter != null) {
            ((MineProcess) baritone.getMineProcess()).mine(savedDesiredQuantity, savedFilter);
            savedFilter = null;
        }
        cleanup();
        logDirect("MindFix: done, resuming original mining");
        state             = State.IDLE;
        currentRepairSlot = -1;
        savedDesiredQuantity = 0;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    @Override
    public void onLostControl() {
        // Return any picks moved to offhand
        if (silkTouchInOffhand) {
            swapOffhandWithSlot(silkTouchOriginalSlot);
            silkTouchInOffhand    = false;
            silkTouchOriginalSlot = -1;
        }
        if (lowDurabilityInOffhand) {
            swapOffhandWithSlot(lowDurabilityOriginalSlot);
            lowDurabilityInOffhand    = false;
            lowDurabilityOriginalSlot = -1;
        }
        // Always restore MineProcess so FullBag (or other higher-priority processes) see
        // the correct mine filter when they take control from us
        if (savedFilter != null) {
            ((MineProcess) baritone.getMineProcess()).mine(savedDesiredQuantity, savedFilter);
            savedFilter = null;
        }
        cleanup();
        state             = State.IDLE;
        currentRepairSlot = -1;
        savedDesiredQuantity = 0;
    }

    @Override
    public String displayName0() {
        return "MindFix [" + state + "]";
    }

    // ── Pick management ───────────────────────────────────────────────────────

    /** If the currently held pick has Silk Touch, move it to offhand so a non-silk pick can mine. */
    private void manageSilkTouchPick() {
        if (silkTouchInOffhand) return;
        int selected = ctx.player().getInventory().getSelectedSlot();
        ItemStack current = ctx.player().getInventory().getNonEquipmentItems().get(selected);
        if (isPickaxe(current) && hasSilkTouch(current)) {
            swapSlotWithOffhand(selected);
            silkTouchOriginalSlot = selected;
            silkTouchInOffhand    = true;
            logDirect("MindFix: silk touch pick moved to offhand");
        }
    }

    /** If the current repair pick can't mine (remaining ≤ savedItemSaverThreshold), move it to offhand. */
    private void manageLowDurabilityPick() {
        if (lowDurabilityInOffhand || silkTouchInOffhand || currentRepairSlot < 0) return;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        if (currentRepairSlot >= inv.size()) { currentRepairSlot = -1; return; }
        ItemStack pick = inv.get(currentRepairSlot);
        if (!isPickaxe(pick) || hasSilkTouch(pick)) { currentRepairSlot = -1; return; }
        int remaining = pick.getMaxDamage() - pick.getDamageValue();
        if (remaining <= savedItemSaverThreshold) {
            swapSlotWithOffhand(currentRepairSlot);
            lowDurabilityOriginalSlot = currentRepairSlot;
            lowDurabilityInOffhand    = true;
            currentRepairSlot         = -1;
            logDirect("MindFix: low-durability pick moved to offhand for Mending");
        }
    }

    /** Put the most damaged (but still mineable) Mending pick in the main hand for XP repair. */
    private void ensureRepairPickInMainHand() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();

        // Stick with the current slot until it's fully repaired or drops below saver threshold
        if (currentRepairSlot >= 0 && currentRepairSlot < inv.size()) {
            ItemStack pick = inv.get(currentRepairSlot);
            if (isPickaxe(pick) && !hasSilkTouch(pick) && pick.getDamageValue() > 0) {
                int remaining = pick.getMaxDamage() - pick.getDamageValue();
                if (remaining > savedItemSaverThreshold) {
                    putSlotInMainHand(currentRepairSlot);
                    return;
                }
                // Dropped below saver threshold — manageLowDurabilityPick will handle offhand
                return;
            }
            currentRepairSlot = -1; // pick repaired or gone
        }

        // Find the most damaged Mending, non-silk, above-threshold pick
        int bestSlot = -1, maxDamage = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!isPickaxe(s) || hasSilkTouch(s) || !hasMending(s)) continue;
            int remaining = s.getMaxDamage() - s.getDamageValue();
            if (remaining > savedItemSaverThreshold && s.getDamageValue() > maxDamage) {
                maxDamage = s.getDamageValue();
                bestSlot  = i;
            }
        }
        if (bestSlot < 0) return;
        currentRepairSlot = bestSlot;
        putSlotInMainHand(bestSlot);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Restore all Baritone settings to values saved at activation time. No-op if not saved. */
    private void cleanup() {
        if (!settingsSaved) return;
        Baritone.settings().itemSaver.value            = savedItemSaver;
        Baritone.settings().itemSaverThreshold.value   = savedItemSaverThreshold;
        Baritone.settings().autoTool.value             = savedAutoTool;
        Baritone.settings().mineScanDroppedItems.value = savedMineScanDroppedItems;
        settingsSaved = false;
    }

    /** Returns true if ANY Mending pick has remaining ≤ (savedItemSaverThreshold + 30). */
    private boolean anyMendingPickBelowThreshold() {
        int t = settingsSaved ? savedItemSaverThreshold + 30
                              : Baritone.settings().itemSaverThreshold.value + 30;
        return anyMendingPickBelowThreshold(t);
    }

    private boolean anyMendingPickBelowThreshold(int threshold) {
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (isPickaxe(s) && hasMending(s) && (s.getMaxDamage() - s.getDamageValue()) <= threshold) return true;
        }
        ItemStack oh = ctx.player().getOffhandItem();
        return isPickaxe(oh) && hasMending(oh) && (oh.getMaxDamage() - oh.getDamageValue()) <= threshold;
    }

    /** Returns true if ALL Mending picks have remaining ≤ threshold (and at least one exists). */
    private boolean allMendingPicksBelowThreshold(int threshold) {
        boolean foundAny = false;
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (!isPickaxe(s) || !hasMending(s)) continue;
            foundAny = true;
            if ((s.getMaxDamage() - s.getDamageValue()) > threshold) return false;
        }
        ItemStack oh = ctx.player().getOffhandItem();
        if (isPickaxe(oh) && hasMending(oh)) {
            foundAny = true;
            if ((oh.getMaxDamage() - oh.getDamageValue()) > threshold) return false;
        }
        return foundAny;
    }

    /** Swap the item at inventory slot [slot] with the offhand slot using SWAP button 40. */
    private void swapSlotWithOffhand(int slot) {
        int containerSlot = slot < 9 ? slot + 36 : slot;
        ctx.playerController().windowClick(
                ctx.player().inventoryMenu.containerId,
                containerSlot, 40, ClickType.SWAP, ctx.player());
    }

    /** Same as swapSlotWithOffhand — restores offhand item back to slot. */
    private void swapOffhandWithSlot(int slot) {
        swapSlotWithOffhand(slot);
    }

    private void putSlotInMainHand(int slot) {
        if (slot < 9) {
            ctx.player().getInventory().setSelectedSlot(slot);
        } else {
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    slot, 0, ClickType.SWAP, ctx.player());
            ctx.player().getInventory().setSelectedSlot(0);
            currentRepairSlot = 0;
        }
    }

    private boolean isPickaxe(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(ItemTags.PICKAXES);
    }

    private boolean hasSilkTouch(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Holder<Enchantment> e : stack.getEnchantments().keySet()) {
            if (e.is(Enchantments.SILK_TOUCH)) return true;
        }
        return false;
    }

    private boolean hasMending(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Holder<Enchantment> e : stack.getEnchantments().keySet()) {
            if (e.is(Enchantments.MENDING)) return true;
        }
        return false;
    }
}
