/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.util.inv;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.FuzzyMode;
import appeng.core.AELog;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;

public class AdaptorItemStorage extends InventoryAdaptor {
    protected final Storage<ItemVariant> storage;

    public AdaptorItemStorage(Storage<ItemVariant> storage) {
        this.storage = storage;
    }

    @Override
    public boolean hasSlots() {
        try (var tx = Transaction.openOuter()) {
            return this.storage.iterator(tx).hasNext();
        }
    }

    @Override
    public ItemStack removeItems(int amount, ItemStack filter, Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = Transaction.openOuter()) {
            result = innerRemoveItems(amount, filter, destination, tx);
            tx.commit();
        }
        return result;
    }

    @Override
    public ItemStack simulateRemove(int amount, ItemStack filter, Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = Transaction.openOuter()) {
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
            AELog.warn(
                    "An inventory returned more (%d) than we requested (%d) during extraction. Excess will be voided.",
                    extractedAmount, amount);
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
        try (var tx = Transaction.openOuter()) {
            result = innerRemoveSimilarItems(amount, filter, fuzzyMode, destination, tx);
            tx.commit();
        }
        return result;
    }

    @Override
    public ItemStack simulateSimilarRemove(int amount, ItemStack filter, FuzzyMode fuzzyMode,
            Predicate<ItemStack> destination) {
        ItemStack result;
        try (var tx = Transaction.openOuter()) {
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

            if (!filter.isEmpty() && !Platform.itemComparisons().isFuzzyEqualItem(is, filter, fuzzyMode)) {
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
                AELog.warn(
                        "An inventory returned more (%d) than we requested (%d) during extraction. Excess will be voided.",
                        actualAmount, amount);
                actualAmount = amount;
            }

            return is.toStack((int) actualAmount);
        }

        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack addItems(ItemStack toBeAdded) {
        return this.addItems(toBeAdded, false);
    }

    @Override
    public ItemStack simulateAdd(ItemStack toBeSimulated) {
        return this.addItems(toBeSimulated, true);
    }

    protected ItemStack addItems(ItemStack itemsToAdd, boolean simulate) {
        if (itemsToAdd.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try (var tx = Transaction.openOuter()) {
            ItemStack remainder = itemsToAdd.copy();

            var inserted = storage.insert(ItemVariant.of(itemsToAdd), itemsToAdd.getCount(), tx);

            if (!simulate) {
                tx.commit();
            }

            remainder.shrink((int) inserted);
            return remainder.isEmpty() ? ItemStack.EMPTY : remainder;
        }
    }
}
