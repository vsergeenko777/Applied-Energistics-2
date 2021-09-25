package appeng.api.inventories;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;

/**
 * Implementation aid for {@link InternalInventory} that ensures the platorm adapter maintains its referential equality
 * over time.
 */
public abstract class BaseInternalInventory implements InternalInventory {

    private Storage<ItemVariant> platformWrapper;

    @Override
    public final Storage<ItemVariant> toStorage() {
        if (platformWrapper == null) {
            platformWrapper = new InternalInventoryStorage(this);
        }
        return platformWrapper;
    }

}
