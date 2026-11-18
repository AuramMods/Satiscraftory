package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.SplitterBlock;
import art.arcane.satiscraftory.data.filter.SplitterFilterMode;
import art.arcane.satiscraftory.data.filter.SplitterOutput;
import art.arcane.satiscraftory.item.ConveyorItem;
import art.arcane.satiscraftory.menu.SmartSplitterMenu;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@GameTestHolder(Satiscraftory.MODID)
@PrefixGameTestTemplate(false)
public final class SmartSplitterGameTests {
    private static final String TEMPLATE = "crafting_output_test";

    @GameTest(template = TEMPLATE)
    public static void unassignedOutputsKeepNormalRoundRobinBehavior(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        for (int i = 0; i < 9; i++) send(helper, rig, Items.DIAMOND);
        for (SplitterOutput output : SplitterOutput.values()) {
            helper.assertTrue(chest(rig, output).countItem(Items.DIAMOND) == 3, "Unassigned outputs share the supply equally");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void originalSplitterStillDistributesAndRetainsJammedCargo(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        var level = helper.getLevel();
        level.setBlockAndUpdate(rig.pos(), Satiscraftory.SPLITTER.get().defaultBlockState());
        var splitter = (SplitterBlockEntity) level.getBlockEntity(rig.pos());
        var input = splitter.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
        for (int i = 0; i < 9; i++) {
            helper.assertTrue(input.insertItem(0, new ItemStack(Items.DIAMOND), false).isEmpty(), "Original splitter accepts one incoming item");
            SplitterBlockEntity.serverTick(level, rig.pos(), splitter.getBlockState(), splitter);
        }
        for (SplitterOutput output : SplitterOutput.values()) {
            var chest = (ChestBlockEntity) level.getBlockEntity(rig.pos().relative(output.direction(Direction.NORTH)));
            helper.assertTrue(chest.countItem(Items.DIAMOND) == 3, "Original splitter retains its round-robin distribution");
            fill(chest);
        }
        input.insertItem(0, new ItemStack(Items.EMERALD), false);
        SplitterBlockEntity.serverTick(level, rig.pos(), splitter.getBlockState(), splitter);
        helper.assertTrue(input.getStackInSlot(0).is(Items.EMERALD), "Jammed original splitter preserves its cargo");
        var center = (ChestBlockEntity) level.getBlockEntity(rig.pos().south());
        center.clearContent();
        SplitterBlockEntity.serverTick(level, rig.pos(), splitter.getBlockState(), splitter);
        helper.assertTrue(input.getStackInSlot(0).isEmpty() && center.countItem(Items.EMERALD) == 1, "Unblocking delivers exactly the waiting item");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void whitelistAndBlacklistRouteDifferentItems(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.BLACKLIST, Items.DIAMOND);
        configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.OVERFLOW);
        for (int i = 0; i < 3; i++) {
            send(helper, rig, Items.DIAMOND);
            send(helper, rig, Items.GOLD_INGOT);
        }
        helper.assertTrue(chest(rig, SplitterOutput.LEFT).countItem(Items.DIAMOND) == 3
                && chest(rig, SplitterOutput.CENTER).countItem(Items.GOLD_INGOT) == 3
                && chest(rig, SplitterOutput.RIGHT).isEmpty(), "Only matching regular outputs receive items");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void overflowWaitsForEveryEligibleRegularOutputToBlock(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.OVERFLOW);
        fill(chest(rig, SplitterOutput.LEFT));
        send(helper, rig, Items.DIAMOND);
        helper.assertTrue(chest(rig, SplitterOutput.CENTER).countItem(Items.DIAMOND) == 1
                && chest(rig, SplitterOutput.RIGHT).isEmpty(), "One open regular output prevents overflow");
        fill(chest(rig, SplitterOutput.CENTER));
        send(helper, rig, Items.DIAMOND);
        helper.assertTrue(chest(rig, SplitterOutput.RIGHT).countItem(Items.DIAMOND) == 1, "All regular outputs jammed enables overflow");
        chest(rig, SplitterOutput.CENTER).clearContent();
        send(helper, rig, Items.DIAMOND);
        helper.assertTrue(chest(rig, SplitterOutput.CENTER).countItem(Items.DIAMOND) == 1
                && chest(rig, SplitterOutput.RIGHT).countItem(Items.DIAMOND) == 1, "Regular output resumes immediately after unblocking");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void overflowAlsoHandlesItemsRejectedByRegularFilters(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.IRON_INGOT);
        configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.WHITELIST, Items.GOLD_INGOT);
        configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.OVERFLOW);
        send(helper, rig, Items.DIAMOND);
        helper.assertTrue(chest(rig, SplitterOutput.RIGHT).countItem(Items.DIAMOND) == 1, "Unmatched items use overflow without leaking into whitelists");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void multipleOverflowOutputsShareTheirSupply(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        for (SplitterOutput output : SplitterOutput.values()) configure(rig.splitter(), output, SplitterFilterMode.OVERFLOW);
        for (int i = 0; i < 6; i++) send(helper, rig, Items.COAL);
        for (SplitterOutput output : SplitterOutput.values()) {
            helper.assertTrue(chest(rig, output).countItem(Items.COAL) == 2, "Overflow-only configurations still make progress fairly");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void matchingWhitelistOutputsRemainFair(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.WHITELIST, Items.GOLD_INGOT);
        configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        for (int i = 0; i < 6; i++) send(helper, rig, Items.DIAMOND);
        helper.assertTrue(chest(rig, SplitterOutput.LEFT).countItem(Items.DIAMOND) == 3
                && chest(rig, SplitterOutput.RIGHT).countItem(Items.DIAMOND) == 3
                && chest(rig, SplitterOutput.CENTER).isEmpty(), "Eligible whitelists rotate without routing to a mismatching side");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void beltPullsCannotBypassFiltersOrOverflowPriority(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.BLACKLIST, Items.DIAMOND);
        configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.OVERFLOW);
        input(rig).insertItem(0, new ItemStack(Items.DIAMOND), false);
        helper.assertTrue(port(rig, SplitterOutput.CENTER).extractItem(0, 1, true).isEmpty()
                && port(rig, SplitterOutput.CENTER).extractItem(0, 1, false).isEmpty(), "Blacklist applies to simulated and actual pulls");
        helper.assertTrue(port(rig, SplitterOutput.RIGHT).extractItem(0, 1, false).isEmpty(), "Overflow pull waits while a matching regular output can accept");
        helper.assertTrue(port(rig, SplitterOutput.LEFT).extractItem(0, 1, true).is(Items.DIAMOND)
                && !input(rig).getStackInSlot(0).isEmpty(), "Allowed pull simulation does not consume the item");
        fill(chest(rig, SplitterOutput.LEFT));
        helper.assertTrue(port(rig, SplitterOutput.RIGHT).extractItem(0, 1, false).is(Items.DIAMOND)
                && input(rig).getStackInSlot(0).isEmpty(), "Overflow pull can extract exactly once when regular destinations block");
        input(rig).insertItem(0, new ItemStack(Items.GOLD_INGOT), false);
        helper.assertTrue(port(rig, SplitterOutput.LEFT).extractItem(0, 1, false).isEmpty(), "Whitelist rejects an unlisted pull");
        helper.assertTrue(rig.splitter().getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(AssertionError::new)
                .extractItem(0, 1, false).isEmpty(), "Unsided access cannot bypass output rules");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void emptyWhitelistBlocksAndEmptyBlacklistAllows(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        for (SplitterOutput output : SplitterOutput.values()) configure(rig.splitter(), output, SplitterFilterMode.WHITELIST);
        input(rig).insertItem(0, new ItemStack(Items.EMERALD), false);
        tick(rig);
        helper.assertTrue(input(rig).getStackInSlot(0).is(Items.EMERALD), "No accepting output retains the actual item");
        helper.assertTrue(!input(rig).insertItem(0, new ItemStack(Items.DIAMOND), false).isEmpty(), "A retained item applies input backpressure");
        configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.BLACKLIST);
        tick(rig);
        helper.assertTrue(chest(rig, SplitterOutput.CENTER).countItem(Items.EMERALD) == 1, "An empty blacklist allows the waiting item to resume");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void ghostTemplatesAreIndependentDeduplicatedAndPersisted(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        List<Item> items = List.of(Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT,
                Items.REDSTONE, Items.COAL, Items.EMERALD, Items.LAPIS_LAZULI, Items.QUARTZ);
        for (int slot = 0; slot < items.size(); slot++) rig.splitter().setTemplate(SplitterOutput.LEFT, slot, new ItemStack(items.get(slot), 64));
        configureMode(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST);
        configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.BLACKLIST, Items.STICK);
        configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.OVERFLOW);
        input(rig).insertItem(0, new ItemStack(Items.DIAMOND), false);
        rig.splitter().load(rig.splitter().getUpdateTag());
        for (int slot = 0; slot < items.size(); slot++) {
            helper.assertTrue(rig.splitter().template(SplitterOutput.LEFT, slot).is(items.get(slot))
                    && rig.splitter().template(SplitterOutput.LEFT, slot).getCount() == 1, "All nine templates persist as single ghost items");
        }
        helper.assertTrue(rig.splitter().mode(SplitterOutput.LEFT) == SplitterFilterMode.WHITELIST
                && rig.splitter().mode(SplitterOutput.CENTER) == SplitterFilterMode.BLACKLIST
                && rig.splitter().mode(SplitterOutput.RIGHT) == SplitterFilterMode.OVERFLOW
                && input(rig).getStackInSlot(0).is(Items.DIAMOND), "Each mode and real buffered item survive snapshot/reload");
        rig.splitter().setTemplate(SplitterOutput.LEFT, 8, new ItemStack(Items.DIAMOND));
        helper.assertTrue(rig.splitter().template(SplitterOutput.LEFT, 0).isEmpty()
                && rig.splitter().template(SplitterOutput.LEFT, 8).is(Items.DIAMOND), "A duplicate template moves rather than wasting a second slot");
        helper.assertTrue(rig.splitter().template(SplitterOutput.CENTER, 0).is(Items.STICK), "Changing one side preserves other sides");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void ghostMenuNeverTakesItemsAndRejectsInvalidConfiguration(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "SmartFilters"));
        player.setPos(rig.pos().getX() + 0.5, rig.pos().getY(), rig.pos().getZ() + 0.5);
        SmartSplitterMenu menu = new SmartSplitterMenu(51, player.getInventory(), rig.splitter());
        ItemStack carried = new ItemStack(Items.DIAMOND, 64);
        carried.setHoverName(Component.literal("Keep this real stack"));
        menu.setCarried(carried);
        helper.assertTrue(menu.setFilter(player, SplitterOutput.LEFT, 0, menu.getCarried())
                && menu.getCarried().getCount() == 64 && rig.splitter().template(SplitterOutput.LEFT, 0).getCount() == 1,
                "Clicking a carried stack records only a template");
        helper.assertTrue(!rig.splitter().template(SplitterOutput.LEFT, 0).hasTag(), "Filters store item types rather than source NBT");
        helper.assertTrue(menu.setFilter(player, SplitterOutput.LEFT, 0, ItemStack.EMPTY)
                && rig.splitter().template(SplitterOutput.LEFT, 0).isEmpty(), "An empty cursor clears the chosen slot");
        helper.assertTrue(!menu.setFilter(player, SplitterOutput.LEFT, -1, carried)
                && !menu.setFilter(player, SplitterOutput.LEFT, 9, carried), "Invalid filter indexes are rejected");
        for (SplitterFilterMode mode : new SplitterFilterMode[]{SplitterFilterMode.OVERFLOW, SplitterFilterMode.WHITELIST,
                SplitterFilterMode.BLACKLIST, SplitterFilterMode.UNASSIGNED}) {
            helper.assertTrue(menu.clickMenuButton(player, 0) && rig.splitter().mode(SplitterOutput.LEFT) == mode, "Each side cycles independently through all modes");
        }
        helper.assertTrue(!menu.clickMenuButton(player, -1) && !menu.clickMenuButton(player, 3)
                && menu.slots.size() == 36, "Only real player slots are inventory slots");
        player.getInventory().setItem(9, new ItemStack(Items.EMERALD, 17));
        helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty() && player.getInventory().getItem(9).getCount() == 17,
                "Shift-click never transfers real items into ghost slots");
        player.setPos(rig.pos().getX() + 30, rig.pos().getY(), rig.pos().getZ());
        helper.assertTrue(!menu.clickMenuButton(player, 1) && !menu.setFilter(player, SplitterOutput.RIGHT, 0, carried),
                "Distant/stale menus cannot configure the machine");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void relativeOutputNamesFollowAllFourInputOrientations(GameTestHelper helper) {
        for (Direction input : Direction.Plane.HORIZONTAL) {
            Rig rig = rig(helper, input);
            configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
            configure(rig.splitter(), SplitterOutput.CENTER, SplitterFilterMode.WHITELIST);
            configure(rig.splitter(), SplitterOutput.RIGHT, SplitterFilterMode.WHITELIST);
            send(helper, rig, Items.DIAMOND);
            helper.assertTrue(chest(rig, SplitterOutput.LEFT).countItem(Items.DIAMOND) == 1,
                    "Left is viewed from the input while looking along the flow for " + input);
            var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "SmartPorts"));
            for (Direction face : Direction.Plane.HORIZONTAL) {
                helper.assertTrue(ConveyorItem.isTargetingConnectionFace(player, rig.pos(), face, true) == (face != input), "All three outputs remain attachable");
                helper.assertTrue(ConveyorItem.isTargetingConnectionFace(player, rig.pos(), face, false) == (face == input), "Only the input accepts a terminating belt");
            }
            // Keep the shared fixture clear before rotating the input.
            helper.getLevel().removeBlock(rig.pos(), false);
            for (Direction side : Direction.Plane.HORIZONTAL) helper.getLevel().removeBlock(rig.pos().relative(side), false);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void breakingDropsRealCargoButNeverGhostFilters(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        for (SplitterOutput output : SplitterOutput.values()) configure(rig.splitter(), output, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        input(rig).insertItem(0, new ItemStack(Items.GOLD_INGOT), false);
        helper.getLevel().destroyBlock(rig.pos(), true);
        int gold = 0, diamonds = 0, blocks = 0;
        for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(rig.pos()).inflate(2))) {
            if (drop.getItem().is(Items.GOLD_INGOT)) gold += drop.getItem().getCount();
            if (drop.getItem().is(Items.DIAMOND)) diamonds += drop.getItem().getCount();
            if (drop.getItem().is(Satiscraftory.SMART_SPLITTER_ITEM.get())) blocks += drop.getItem().getCount();
        }
        helper.assertTrue(gold == 1 && diamonds == 0 && blocks == 1, "Break returns actual cargo and the block, without materializing templates");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void reentrantPullDuringPushCannotDuplicateCargo(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        for (SplitterOutput output : SplitterOutput.values()) configure(rig.splitter(), output, SplitterFilterMode.WHITELIST);
        configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        BlockPos target = rig.pos().relative(SplitterOutput.LEFT.direction(rig.input()));
        SinkChest sink = new SinkChest(target, helper.getLevel().getBlockState(target), port(rig, SplitterOutput.LEFT));
        helper.getLevel().removeBlockEntity(target);
        helper.getLevel().setBlockEntity(sink);
        input(rig).insertItem(0, new ItemStack(Items.DIAMOND), false);
        tick(rig);
        helper.assertTrue(sink.inventory.getStackInSlot(0).getCount() == 1 && sink.reentrant.isEmpty()
                && input(rig).getStackInSlot(0).isEmpty(), "A destination callback cannot pull the item being pushed a second time");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void actualOutputRejectionRetainsTheItem(GameTestHelper helper) {
        Rig rig = rig(helper, Direction.NORTH);
        for (SplitterOutput output : SplitterOutput.values()) configure(rig.splitter(), output, SplitterFilterMode.WHITELIST);
        configure(rig.splitter(), SplitterOutput.LEFT, SplitterFilterMode.WHITELIST, Items.DIAMOND);
        BlockPos target = rig.pos().relative(SplitterOutput.LEFT.direction(rig.input()));
        SinkChest sink = new SinkChest(target, helper.getLevel().getBlockState(target), port(rig, SplitterOutput.LEFT));
        sink.reject = true;
        helper.getLevel().removeBlockEntity(target);
        helper.getLevel().setBlockEntity(sink);
        input(rig).insertItem(0, new ItemStack(Items.DIAMOND), false);
        tick(rig);
        helper.assertTrue(input(rig).getStackInSlot(0).is(Items.DIAMOND), "A handler that rejects actual insertion leaves the source item intact");
        sink.reject = false;
        tick(rig);
        helper.assertTrue(sink.inventory.getStackInSlot(0).getCount() == 1 && input(rig).getStackInSlot(0).isEmpty(), "Retry delivers exactly one item");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void recipeAndGuideLinkTheSmartSplitter(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var grid = new TransientCraftingContainer(new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
            @Override
            public boolean stillValid(Player player) { return true; }
        }, 3, 3);
        for (int slot = 0; slot < 9; slot++) {
            Item item = switch (slot) {
                case 0, 2, 6, 8 -> Items.GOLD_INGOT;
                case 4 -> Satiscraftory.SPLITTER_ITEM.get();
                default -> Items.COMPARATOR;
            };
            grid.setItem(slot, new ItemStack(item));
        }
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level).orElseThrow();
        helper.assertTrue(recipe.getId().toString().equals("satiscraftory:smart_splitter")
                && recipe.assemble(grid, level.registryAccess()).is(Satiscraftory.SMART_SPLITTER_ITEM.get())
                && recipe.assemble(grid, level.registryAccess()).getCount() == 1, "IXI / XSX / IXI makes exactly one Smart Splitter");
        String entry = "assets/satiscraftory/patchouli_books/satiscraftory/en_us/entries/routing/smart_splitter.json";
        try (var stream = SmartSplitterGameTests.class.getClassLoader().getResourceAsStream(entry)) {
            var book = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            helper.assertTrue(book.getAsJsonArray("pages").get(0).getAsJsonObject().get("recipe").getAsString().equals(recipe.getId().toString()),
                    "Guide includes the registered crafting recipe");
        }
        helper.succeed();
    }

    private record Rig(BlockPos pos, Direction input, SmartSplitterBlockEntity splitter) {}

    private static Rig rig(GameTestHelper helper, Direction input) {
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 1));
        for (SplitterOutput output : SplitterOutput.values()) {
            helper.getLevel().setBlockAndUpdate(pos.relative(output.direction(input)), Blocks.CHEST.defaultBlockState());
        }
        helper.getLevel().setBlockAndUpdate(pos, Satiscraftory.SMART_SPLITTER.get().defaultBlockState().setValue(SplitterBlock.INPUT_FACING, input));
        return new Rig(pos, input, (SmartSplitterBlockEntity) helper.getLevel().getBlockEntity(pos));
    }

    private static ChestBlockEntity chest(Rig rig, SplitterOutput output) {
        return (ChestBlockEntity) rig.splitter().getLevel().getBlockEntity(rig.pos().relative(output.direction(rig.input())));
    }

    private static IItemHandler input(Rig rig) {
        return rig.splitter().getCapability(ForgeCapabilities.ITEM_HANDLER, rig.input()).orElseThrow(AssertionError::new);
    }

    private static IItemHandler port(Rig rig, SplitterOutput output) {
        return rig.splitter().getCapability(ForgeCapabilities.ITEM_HANDLER, output.direction(rig.input())).orElseThrow(AssertionError::new);
    }

    private static void configure(SmartSplitterBlockEntity splitter, SplitterOutput output, SplitterFilterMode mode, Item... items) {
        configureMode(splitter, output, mode);
        for (int slot = 0; slot < items.length; slot++) splitter.setTemplate(output, slot, new ItemStack(items[slot]));
    }

    private static void configureMode(SmartSplitterBlockEntity splitter, SplitterOutput output, SplitterFilterMode mode) {
        while (splitter.mode(output) != mode) splitter.cycleMode(output);
    }

    private static void send(GameTestHelper helper, Rig rig, Item item) {
        helper.assertTrue(input(rig).insertItem(0, new ItemStack(item), false).isEmpty(), "Splitter accepts the incoming item");
        tick(rig);
        helper.assertTrue(input(rig).getStackInSlot(0).isEmpty(), "Accepted item routes to a valid output");
    }

    private static void tick(Rig rig) {
        SplitterBlockEntity.serverTick(rig.splitter().getLevel(), rig.pos(), rig.splitter().getBlockState(), rig.splitter());
    }

    private static void fill(ChestBlockEntity chest) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
    }

    private static final class SinkChest extends ChestBlockEntity {
        private boolean reject;
        private ItemStack reentrant = ItemStack.EMPTY;
        private final ItemStackHandler inventory;
        private final LazyOptional<IItemHandler> port;

        private SinkChest(BlockPos pos, BlockState state, IItemHandler source) {
            super(pos, state);
            inventory = new ItemStackHandler(1) {
                @Override
                public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    if (simulate) return ItemStack.EMPTY;
                    if (reject) return stack;
                    reentrant = source.extractItem(0, 1, false);
                    return super.insertItem(slot, stack, false);
                }
            };
            port = LazyOptional.of(() -> inventory);
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
            return capability == ForgeCapabilities.ITEM_HANDLER ? port.cast() : super.getCapability(capability, side);
        }
    }
}
