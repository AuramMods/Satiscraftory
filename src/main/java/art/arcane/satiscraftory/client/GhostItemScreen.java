package art.arcane.satiscraftory.client;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** Shared drop targets for virtual templates, including optional JEI dragging. */
public interface GhostItemScreen {
    List<GhostTarget> ghostTargets();

    record GhostTarget(Rect2i area, Consumer<ItemStack> setter) {
        public void accept(ItemStack stack) {
            setter.accept(stack);
        }
    }
}
