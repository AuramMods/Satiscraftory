package art.arcane.satiscraftory;

import art.arcane.satiscraftory.block.ConveyorStraightBlock;
import art.arcane.satiscraftory.block.entity.ConveyorStraightBlockEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;
import org.slf4j.Logger;

@Mod(Satiscraftory.MODID)
public class Satiscraftory {
    public static final String MODID = "satiscraftory";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<Block> CONVEYOR = BLOCKS.register("conveyor", () ->
            new ConveyorStraightBlock(
                    BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL).noOcclusion(),
                    5L
            ));
    public static final RegistryObject<Item> CONVEYOR_ITEM = ITEMS.register("conveyor", () ->
            new BlockItem(CONVEYOR.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<ConveyorStraightBlockEntity>> CONVEYOR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("conveyor", () ->
                    BlockEntityType.Builder.of(ConveyorStraightBlockEntity::new, CONVEYOR.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register(MODID+"_tab", () -> CreativeModeTab.builder()
            .title(Component.literal("Satiscraftory"))
            .icon(() -> new ItemStack(CONVEYOR_ITEM.get()))
            .displayItems((params, output) -> {
                output.accept(CONVEYOR_ITEM.get());
            })
            .build());


    public Satiscraftory(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        modEventBus.register(this);
    }
}
