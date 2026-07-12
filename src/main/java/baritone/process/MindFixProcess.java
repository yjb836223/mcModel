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
import baritone.api.process.IMindFixProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;

/**
 * MindFixProcess: automatically repairs pickaxes using Mending XP from mining.
 * When all pickaxes in inventory are low on durability, redirects MineProcess
 * to mine XP-rich ores (quartz, diamonds) until pickaxes are fully repaired.
 */
public final class MindFixProcess extends BaritoneProcessHelper implements IMindFixProcess {

    private enum State {
        IDLE, PREPARE, REPAIRING, RESTORING
    }

    private State state = State.IDLE;

    // Saved MineProcess state for restoration
    private BlockOptionalMetaLookup savedFilter;
    private int savedDesiredQuantity;

    // Track if silk-touch pick was moved to offhand
    private int silkTouchOriginalSlot = -1; // -1 means not moved
    private boolean silkTouchMovedToOffhand = false;

    // Saved mineScanDroppedItems — disabled during repair to avoid picking up ore drops
    private boolean savedMineScanDroppedItems = true;
    private boolean mineScanOverridden = false;

    public MindFixProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null) return false;
        if (!Baritone.settings().mindfixEnabled.value) {
            return false;
        }
        if (state == State.REPAIRING || state == State.RESTORING || state == State.PREPARE) {
            return true;
        }
        // IDLE: only trigger when #mine is running AND FullBag is not active
        return allPickaxesBelowThreshold()
                && baritone.getMineProcess().isActive()
                && !baritone.getFullBagProcess().isActive();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        MineProcess mineProcess = baritone.getMineProcess();

        if (state == State.IDLE) {
            if (allPickaxesBelowThreshold()) {
                logDirect("MindFix: all pickaxes are low durability, starting repair");
                state = State.PREPARE;
            } else {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
        }

        if (state == State.PREPARE) {
            // Enable itemSaver if not already enabled
            if (!Baritone.settings().itemSaver.value) {
                Baritone.settings().itemSaver.value = true;
                logDirect("MindFix: enabled itemSaver");
            }
            // Ensure threshold is at least 20
            if (Baritone.settings().itemSaverThreshold.value < 20) {
                Baritone.settings().itemSaverThreshold.value = 20;
                logDirect("MindFix: set itemSaverThreshold to 20");
            }

            // Save MineProcess filter and quantity
            savedFilter = mineProcess.getFilter();
            savedDesiredQuantity = mineProcess.getDesiredQuantity();

            // Disable drop scanning so bot doesn't chase ore drops while repairing
            if (!mineScanOverridden) {
                savedMineScanDroppedItems = Baritone.settings().mineScanDroppedItems.value;
                Baritone.settings().mineScanDroppedItems.value = false;
                mineScanOverridden = true;
            }

            // Redirect MineProcess to mine XP ores
            mineProcess.mine(0, new BlockOptionalMetaLookup(
                    Blocks.NETHER_QUARTZ_ORE,
                    Blocks.DIAMOND_ORE,
                    Blocks.DEEPSLATE_DIAMOND_ORE
            ));
            logDirect("MindFix: redirecting mine to XP ores for Mending repair");

            state = State.REPAIRING;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        if (state == State.REPAIRING) {
            // Check if all pickaxes are fully repaired
            if (allPickaxesFullyRepaired()) {
                logDirect("MindFix: all pickaxes fully repaired, restoring");
                state = State.RESTORING;
                return onTick(calcFailed, isSafeToCancel);
            }

            // Manage silk-touch pickaxe (move to offhand)
            manageSilkTouchPickaxe();

            // Also ensure the most-damaged non-silk-touch pick is in main hand
            // so Mending XP goes to it
            ensureMostDamagedPickInMainHand();

            // Defer to MineProcess for movement
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        if (state == State.RESTORING) {
            // Restore silk-touch pick to original slot if it was moved to offhand
            if (silkTouchMovedToOffhand) {
                restoreSilkTouchFromOffhand();
                silkTouchMovedToOffhand = false;
                silkTouchOriginalSlot = -1;
            }

            // Restore mineScanDroppedItems
            if (mineScanOverridden) {
                Baritone.settings().mineScanDroppedItems.value = savedMineScanDroppedItems;
                mineScanOverridden = false;
            }

            // Restore MineProcess to original filter
            mineProcess.mine(savedDesiredQuantity, savedFilter);
            savedFilter = null;
            savedDesiredQuantity = 0;

            logDirect("MindFix: restoration complete, resuming original mining");
            state = State.IDLE;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    @Override
    public void onLostControl() {
        // Restore silk-touch pick if it was moved to offhand
        if (silkTouchMovedToOffhand) {
            restoreSilkTouchFromOffhand();
            silkTouchMovedToOffhand = false;
            silkTouchOriginalSlot = -1;
        }

        // Only restore MineProcess if we finished naturally (RESTORING state)
        // If user did #stop, state won't be RESTORING, so we don't restart mining
        if (savedFilter != null && state == State.RESTORING) {
            MineProcess mineProcess = baritone.getMineProcess();
            mineProcess.mine(savedDesiredQuantity, savedFilter);
        }
        savedFilter = null;
        savedDesiredQuantity = 0;
        if (mineScanOverridden) {
            Baritone.settings().mineScanDroppedItems.value = savedMineScanDroppedItems;
            mineScanOverridden = false;
        }

        state = State.IDLE;
    }

    @Override
    public double priority() {
        return 2.0;
    }

    @Override
    public String displayName0() {
        return "MindFix [" + state + "]";
    }

    /**
     * Returns true if ALL pickaxes in inventory have remaining durability <= (itemSaverThreshold + 30).
     * Returns false if no pickaxes exist.
     */
    private boolean allPickaxesBelowThreshold() {
        int threshold = Baritone.settings().itemSaverThreshold.value + 30;
        NonNullList<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();

        boolean foundAny = false;
        for (ItemStack stack : items) {
            if (isPickaxe(stack)) {
                foundAny = true;
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                if (remaining > threshold) {
                    return false; // at least one pickaxe is fine
                }
            }
        }
        // Also check offhand
        ItemStack offhand = ctx.player().getOffhandItem();
        if (isPickaxe(offhand)) {
            foundAny = true;
            int remaining = offhand.getMaxDamage() - offhand.getDamageValue();
            if (remaining > threshold) {
                return false;
            }
        }

        return foundAny;
    }

    /**
     * Returns true if all pickaxes in inventory have getDamageValue() == 0 (fully repaired).
     */
    private boolean allPickaxesFullyRepaired() {
        NonNullList<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();
        for (ItemStack stack : items) {
            if (isPickaxe(stack) && stack.getDamageValue() > 0) {
                return false;
            }
        }
        ItemStack offhand = ctx.player().getOffhandItem();
        if (isPickaxe(offhand) && offhand.getDamageValue() > 0) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if the item stack has the Silk Touch enchantment.
     */
    private boolean hasSilkTouch(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the item stack is a pickaxe (has TOOL component and is a pickaxe type).
     */
    private boolean isPickaxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // Check by item tag or by name - use the item's registered name
        return stack.getItem().getDescriptionId().contains("pickaxe");
    }

    /**
     * Puts the most-damaged non-silk-touch pickaxe into the main hand
     * so Mending XP repairs it first.
     */
    private void ensureMostDamagedPickInMainHand() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int mostDamagedSlot = -1;
        int highestDamage = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!isPickaxe(s) || hasSilkTouch(s) || s.getDamageValue() == 0) continue;
            if (s.getDamageValue() > highestDamage) {
                highestDamage = s.getDamageValue();
                mostDamagedSlot = i;
            }
        }
        if (mostDamagedSlot == -1) return;
        if (mostDamagedSlot < 9) {
            ctx.player().getInventory().setSelectedSlot(mostDamagedSlot);
        } else {
            ctx.playerController().windowClick(
                    ctx.player().inventoryMenu.containerId,
                    mostDamagedSlot, 0, ClickType.SWAP, ctx.player());
            ctx.player().getInventory().setSelectedSlot(0);
        }
    }

    /**
     * Manages silk-touch pickaxe placement during REPAIRING state.
     * Moves silk-touch pick to offhand so a non-silk-touch pick is used for mining.
     */
    private void manageSilkTouchPickaxe() {
        NonNullList<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();

        // Check if main hand item has silk touch
        int selectedSlot = ctx.player().getInventory().getSelectedSlot();
        ItemStack mainHandItem = items.get(selectedSlot);

        if (isPickaxe(mainHandItem) && hasSilkTouch(mainHandItem)) {
            // Need to move silk touch to offhand and find a non-silk-touch pickaxe for main hand
            if (!silkTouchMovedToOffhand) {
                // Move silk touch pickaxe to offhand
                int containerSlot = slotToContainerSlot(selectedSlot);
                ctx.playerController().windowClick(
                        ctx.player().inventoryMenu.containerId,
                        containerSlot, 40, ClickType.SWAP, ctx.player()
                );
                silkTouchOriginalSlot = selectedSlot;
                silkTouchMovedToOffhand = true;
                logDirect("MindFix: moved silk-touch pickaxe to offhand");
            }

            // Find a non-silk-touch pickaxe for main hand
            for (int i = 0; i < 9; i++) {
                ItemStack stack = items.get(i);
                if (isPickaxe(stack) && !hasSilkTouch(stack)) {
                    ctx.player().getInventory().setSelectedSlot(i);
                    break;
                }
            }
        }
    }

    /**
     * Restores the silk-touch pickaxe from the offhand back to its original slot.
     */
    private void restoreSilkTouchFromOffhand() {
        if (silkTouchOriginalSlot < 0) return;

        // Button 40 with SWAP moves offhand to the targeted slot, and targeted to offhand
        // To move offhand item back, we click on the original container slot with button=40
        int containerSlot = slotToContainerSlot(silkTouchOriginalSlot);
        ctx.playerController().windowClick(
                ctx.player().inventoryMenu.containerId,
                containerSlot, 40, ClickType.SWAP, ctx.player()
        );
        logDirect("MindFix: restored silk-touch pickaxe to slot " + silkTouchOriginalSlot);
    }

    /**
     * Converts an inventory slot index (from getNonEquipmentItems()) to a container slot index.
     * Hotbar slots 0-8 map to container slots 36-44.
     * Main inventory slots 9-35 map to container slots 9-35.
     */
    private int slotToContainerSlot(int invSlot) {
        if (invSlot < 9) {
            // Hotbar
            return invSlot + 36;
        } else {
            // Main inventory
            return invSlot;
        }
    }
}
