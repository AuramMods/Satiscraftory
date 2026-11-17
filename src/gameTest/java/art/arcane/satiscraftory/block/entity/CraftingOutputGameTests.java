package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.CraftingMachineBlock;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Satiscraftory.MODID)
@PrefixGameTestTemplate(false)
public final class CraftingOutputGameTests {
    private static final String TEMPLATE = "crafting_output_test";

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void constructorDeliversAllThreeDoors(GameTestHelper helper) {
        checkOutput(helper, Satiscraftory.CONSTRUCTOR.get(), 6, 3, 0, true, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void fullStackDeliversThirtyDoorsAndLeavesFourPlanks(GameTestHelper helper) {
        checkOutput(helper, Satiscraftory.CONSTRUCTOR.get(), 64, 30, 4, true, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void pendingDoorBatchSurvivesReloadAndBeltPulls(GameTestHelper helper) {
        checkOutput(helper, Satiscraftory.CONSTRUCTOR.get(), 6, 3, 0, true, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void assemblerDeliversAllThreeDoors(GameTestHelper helper) {
        checkOutput(helper, Satiscraftory.ASSEMBLER.get(), 6, 3, 0, true, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void manufacturerDeliversAllThreeDoors(GameTestHelper helper) {
        checkOutput(helper, Satiscraftory.MANUFACTURER.get(), 6, 3, 0, true, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void directChestOutputPreservesTheFullBatch(GameTestHelper helper) {
        checkOutput(helper, Satiscraftory.CONSTRUCTOR.get(), 64, 30, 4, false, false);
    }

    private static void checkOutput(GameTestHelper helper, Block machineBlock, int planks, int doors,
            int remainingPlanks, boolean useBelt, boolean reload) {
        var level = helper.getLevel();
        BlockPos machinePos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos beltPos = machinePos.east();
        BlockPos chestPos = machinePos.east(useBelt ? 4 : 1);
        level.setBlockAndUpdate(machinePos, machineBlock.defaultBlockState()
                .setValue(CraftingMachineBlock.OUTPUT_FACING, Direction.EAST));
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        if (useBelt) {
            level.setBlockAndUpdate(beltPos, Satiscraftory.CONVEYOR_6.get().defaultBlockState()
                    .setValue(ConveyorBlock.FACING, Direction.EAST));
            var belt = (ConveyorBlockEntity) level.getBlockEntity(beltPos);
            belt.setEndData(machinePos.east(3), Direction.EAST, BeltRouteMode.STRAIGHT);
        }
        var machine = (CraftingMachineBlockEntity) level.getBlockEntity(machinePos);
        var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
        machine.setTemplate(new ItemStack(Items.OAK_DOOR));
        helper.assertTrue(machine.inputInventory().insertItem(0, new ItemStack(Items.OAK_PLANKS, planks), false).isEmpty(),
                "All test ingredients enter the machine");
        var power = machine.getCapability(ForgeCapabilities.ENERGY, Direction.UP).orElseThrow(AssertionError::new);
        power.receiveEnergy(machine.spec().energyCapacity(), false);
        if (reload) {
            machine.load(machine.saveWithoutMetadata());
        }
        helper.onEachTick(() -> power.receiveEnergy(machine.spec().energyCapacity(), false));
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(chest.countItem(Items.OAK_DOOR) == doors,
                    "Expected " + doors + " doors, received " + chest.countItem(Items.OAK_DOOR));
            ItemStack remaining = machine.inputInventory().getStackInSlot(0);
            helper.assertTrue(remaining.getCount() == remainingPlanks,
                    "Expected " + remainingPlanks + " leftover planks, received " + remaining.getCount());
            helper.assertTrue(remaining.isEmpty() || remaining.is(Items.OAK_PLANKS), "Leftover ingredients keep their type");
            helper.assertTrue(machine.saveWithoutMetadata().getList("finished", 10).isEmpty(), "All paid outputs are delivered");
            helper.succeed();
        });
    }
}
