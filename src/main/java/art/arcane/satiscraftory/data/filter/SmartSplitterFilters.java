package art.arcane.satiscraftory.data.filter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Locale;

/** Configuration only: template stacks never represent real inventory. */
public final class SmartSplitterFilters {
    public static final int FILTER_SLOTS = 9;
    private final SplitterFilterMode[] modes = new SplitterFilterMode[SplitterOutput.values().length];
    private final ItemStack[][] templates = new ItemStack[modes.length][FILTER_SLOTS];

    public SmartSplitterFilters() {
        clear();
    }

    private void clear() {
        Arrays.fill(modes, SplitterFilterMode.UNASSIGNED);
        for (ItemStack[] side : templates) {
            Arrays.fill(side, ItemStack.EMPTY);
        }
    }

    public SplitterFilterMode mode(SplitterOutput output) {
        return modes[output.ordinal()];
    }

    public void setMode(SplitterOutput output, SplitterFilterMode mode) {
        modes[output.ordinal()] = mode;
    }

    public ItemStack template(SplitterOutput output, int slot) {
        return templates[output.ordinal()][slot].copy();
    }

    public void setTemplate(SplitterOutput output, int slot, ItemStack stack) {
        ItemStack[] side = templates[output.ordinal()];
        if (!stack.isEmpty()) {
            for (int index = 0; index < side.length; index++) {
                if (index != slot && side[index].is(stack.getItem())) {
                    side[index] = ItemStack.EMPTY;
                }
            }
        }
        side[slot] = stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getItem());
    }

    public boolean matches(SplitterOutput output, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        boolean listed = Arrays.stream(templates[output.ordinal()]).anyMatch(template -> template.is(stack.getItem()));
        return switch (mode(output)) {
            case UNASSIGNED -> true;
            case OVERFLOW -> false;
            case WHITELIST -> listed;
            case BLACKLIST -> !listed;
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (SplitterOutput output : SplitterOutput.values()) {
            CompoundTag side = new CompoundTag();
            side.putString("mode", mode(output).name());
            ListTag filters = new ListTag();
            for (int slot = 0; slot < FILTER_SLOTS; slot++) {
                ItemStack stack = templates[output.ordinal()][slot];
                if (!stack.isEmpty()) {
                    CompoundTag filter = stack.save(new CompoundTag());
                    filter.putInt("slot", slot);
                    filters.add(filter);
                }
            }
            side.put("templates", filters);
            tag.put(output.name().toLowerCase(Locale.ROOT), side);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        clear();
        for (SplitterOutput output : SplitterOutput.values()) {
            CompoundTag side = tag.getCompound(output.name().toLowerCase(Locale.ROOT));
            try {
                setMode(output, SplitterFilterMode.valueOf(side.getString("mode")));
            } catch (IllegalArgumentException ignored) {
                // Missing or unknown modes retain normal splitter behavior.
            }
            for (Tag value : side.getList("templates", Tag.TAG_COMPOUND)) {
                CompoundTag filter = (CompoundTag) value;
                int slot = filter.getInt("slot");
                if (slot >= 0 && slot < FILTER_SLOTS) {
                    setTemplate(output, slot, ItemStack.of(filter));
                }
            }
        }
    }
}
