package appeng.api.inventories;

import appeng.api.config.FuzzyMode;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.function.Predicate;

/**
 * Wraps an inventory implementing the platforms standard inventory interface (i.e. IItemHandler on Forge) such that it
 * can be used as an {@link InternalInventory}.
 */
class PlatformInventoryWrapper implements ItemTransfer {
    private final Storage<ItemVariant> storage;

    public PlatformInventoryWrapper(Storage<ItemVariant> storage) {
        this.storage = storage;
    }

    private static Transaction openOrJoinTx() {
        return Transaction.openOuter();
    }

    @Override
    public boolean mayAllowTransfer() {
        return this.storage.supportsInsertion() || this.storage.supportsExtraction();
    }

    @Override
    public ItemStack removeItems(int amount, ItemStack filter, Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = openOrJoinTx()) {
            result = innerRemoveItems(amount, filter, destination, tx);
            tx.commit();
        }
        return result;
    }

    @Override
    public ItemStack simulateRemove(int amount, ItemStack filter, Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = openOrJoinTx()) {
            result = innerRemoveItems(amount, filter, destination, tx);
        }
        return result;
    }

    private ItemStack innerRemoveItems(int amount, ItemStack filter, Predicate<ItemStack> destination, Transaction tx) {
        ItemVariant rv = ItemVariant.blank();
        long extractedAmount = 0;

        var it = this.storage.iterator(tx);
        while (it.hasNext() && extractedAmount < amount) {
            var view = it.next();

            var is = view.getResource();
            if (is.isBlank()) {
                continue;
            }

            // Haven't decided what to extract yet
            if (rv.isBlank()) {
                if (!filter.isEmpty() && !is.matches(filter)) {
                    continue; // Doesn't match ItemStack template
                }

                if (destination != null && !destination.test(is.toStack())) {
                    continue; // Doesn't match filter
                }

                long actualAmount = view.extract(is, amount - extractedAmount, tx);
                if (actualAmount <= 0) {
                    continue; // Apparently not extractable
                }

                rv = is; // we've decided what to extract
                extractedAmount += actualAmount;
            } else {
                if (!rv.equals(is)) {
                    continue; // Once we've decided what to extract, we need to stick to it
                }

                extractedAmount += view.extract(is, amount, tx);
            }
        }

        // If any of the slots returned more than what we requested, it'll be voided here
        if (extractedAmount > amount) {
            // TODO
//            AELog.warn(
//                    "An inventory returned more (%d) than we requested (%d) during extraction. Excess will be voided.",
//                    extractedAmount, amount);
        }
        return rv.toStack((int) Math.min(amount, extractedAmount));
    }

    /**
     * For fuzzy extract, we will only ever extract one slot, since we're afraid of merging two item stacks with
     * different damage values.
     */
    @Override
    public ItemStack removeSimilarItems(int amount, ItemStack filter, FuzzyMode fuzzyMode,
                                        Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = openOrJoinTx()) {
            result = innerRemoveSimilarItems(amount, filter, fuzzyMode, destination, tx);
            tx.commit();
        }
        return result;
    }

    @Override
    public ItemStack simulateSimilarRemove(int amount, ItemStack filter, FuzzyMode fuzzyMode,
                                           Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = openOrJoinTx()) {
            result = innerRemoveSimilarItems(amount, filter, fuzzyMode, destination, tx);
        }
        return result;
    }

    private ItemStack innerRemoveSimilarItems(int amount, ItemStack filter, FuzzyMode fuzzyMode,
                                              Predicate<ItemStack> destination, Transaction tx) {

        for (var view : this.storage.iterable(tx)) {
            var is = view.getResource();
            if (is.isBlank()) {
                continue;
            }

            if (!filter.isEmpty() && !isFuzzyEqualItem(is.toStack(), filter, fuzzyMode)) {
                continue; // Doesn't match ItemStack template
            }

            if (destination != null && !destination.test(is.toStack())) {
                continue; // Doesn't match filter
            }

            long actualAmount = view.extract(is, amount, tx);
            if (actualAmount <= 0) {
                continue; // Apparently not extractable
            }

            // If any of the slots returned more than what we requested, it'll be voided here
            if (actualAmount > amount) {
                // TODO AELog.warn(
                //        "An inventory returned more (%d) than we requested (%d) during extraction. Excess will be voided.",
//                        actualAmount, amount);
                actualAmount = amount;
            }

            return is.toStack((int) actualAmount);
        }

        return ItemStack.EMPTY;
    }

    @NotNull
    @Override
    public  ItemStack addItems(ItemStack itemsToAdd, boolean simulate) {
        if (itemsToAdd.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try (var tx = openOrJoinTx()) {
            ItemStack remainder = itemsToAdd.copy();

            var inserted = storage.insert(ItemVariant.of(itemsToAdd), itemsToAdd.getCount(), tx);

            if (!simulate) {
                tx.commit();
            }

            remainder.shrink((int) inserted);
            return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
        }
    }

    // TODO: Move back to platform
    /**
     * Similar to {@link ItemStack#isSameItemSameTags}, but it can further check, if both are equal considering a
     * {@link FuzzyMode}.
     *
     * @param mode how to compare the two {@link ItemStack}s
     * @return true, if both are matching the mode
     */
    private boolean isFuzzyEqualItem(ItemStack a, ItemStack b, FuzzyMode mode) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }

        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }

        // test damageable items..
        if (a.getItem() == b.getItem() && a.getItem().canBeDepleted()) {
            if (mode == FuzzyMode.IGNORE_ALL) {
                return true;
            } else if (mode == FuzzyMode.PERCENT_99) {
                return a.getDamageValue() > 1 == b.getDamageValue() > 1;
            } else {
                final float percentDamagedOfA = (float) a.getDamageValue() / a.getMaxDamage();
                final float percentDamagedOfB = (float) b.getDamageValue() / b.getMaxDamage();

                return percentDamagedOfA > mode.breakPoint == percentDamagedOfB > mode.breakPoint;
            }
        }

        return a.sameItem(b);
    }

}
