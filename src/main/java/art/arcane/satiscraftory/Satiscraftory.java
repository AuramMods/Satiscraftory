package art.arcane.satiscraftory;

import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import art.arcane.satiscraftory.block.MergerBlock;
import art.arcane.satiscraftory.block.SplitterBlock;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.block.entity.ConveyorEndBlockEntity;
import art.arcane.satiscraftory.block.entity.MergerBlockEntity;
import art.arcane.satiscraftory.block.entity.SplitterBlockEntity;
import art.arcane.satiscraftory.data.ConveyorTier;
import art.arcane.satiscraftory.item.ConveyorItem;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    public static final RegistryObject<Block> CONVEYOR_1 = registerConveyorBlock("conveyor_1", ConveyorTier.MARK_1);
    public static final RegistryObject<Block> CONVEYOR_2 = registerConveyorBlock("conveyor_2", ConveyorTier.MARK_2);
    public static final RegistryObject<Block> CONVEYOR_3 = registerConveyorBlock("conveyor_3", ConveyorTier.MARK_3);
    public static final RegistryObject<Block> CONVEYOR_4 = registerConveyorBlock("conveyor_4", ConveyorTier.MARK_4);
    public static final RegistryObject<Block> CONVEYOR_5 = registerConveyorBlock("conveyor_5", ConveyorTier.MARK_5);
    public static final RegistryObject<Block> CONVEYOR_6 = registerConveyorBlock("conveyor_6", ConveyorTier.MARK_6);
    public static final RegistryObject<Block> CONVEYOR_END = BLOCKS.register(
            "conveyor_end",
            () -> new ConveyorEndBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL).noOcclusion())
    );
    public static final RegistryObject<Block> SPLITTER = BLOCKS.register(
            "splitter",
            () -> new SplitterBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL))
    );
    public static final RegistryObject<Block> MERGER = BLOCKS.register(
            "merger",
            () -> new MergerBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL))
    );

    public static final RegistryObject<Item> CONVEYOR_1_ITEM = registerConveyorItem("conveyor_1", CONVEYOR_1);
    public static final RegistryObject<Item> CONVEYOR_2_ITEM = registerConveyorItem("conveyor_2", CONVEYOR_2);
    public static final RegistryObject<Item> CONVEYOR_3_ITEM = registerConveyorItem("conveyor_3", CONVEYOR_3);
    public static final RegistryObject<Item> CONVEYOR_4_ITEM = registerConveyorItem("conveyor_4", CONVEYOR_4);
    public static final RegistryObject<Item> CONVEYOR_5_ITEM = registerConveyorItem("conveyor_5", CONVEYOR_5);
    public static final RegistryObject<Item> CONVEYOR_6_ITEM = registerConveyorItem("conveyor_6", CONVEYOR_6);
    public static final RegistryObject<Item> SPLITTER_ITEM = ITEMS.register("splitter", () -> new BlockItem(SPLITTER.get(), new Item.Properties()));
    public static final RegistryObject<Item> MERGER_ITEM = ITEMS.register("merger", () -> new BlockItem(MERGER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<ConveyorBlockEntity>> CONVEYOR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "conveyor_1",
            () -> BlockEntityType.Builder.of(
                    ConveyorBlockEntity::new,
                    CONVEYOR_1.get(),
                    CONVEYOR_2.get(),
                    CONVEYOR_3.get(),
                    CONVEYOR_4.get(),
                    CONVEYOR_5.get(),
                    CONVEYOR_6.get()
            ).build(null)
    );
    public static final RegistryObject<BlockEntityType<ConveyorEndBlockEntity>> CONVEYOR_END_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "conveyor_end",
            () -> BlockEntityType.Builder.of(ConveyorEndBlockEntity::new, CONVEYOR_END.get()).build(null)
    );
    public static final RegistryObject<BlockEntityType<SplitterBlockEntity>> SPLITTER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "splitter",
            () -> BlockEntityType.Builder.of(SplitterBlockEntity::new, SPLITTER.get()).build(null)
    );
    public static final RegistryObject<BlockEntityType<MergerBlockEntity>> MERGER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "merger",
            () -> BlockEntityType.Builder.of(MergerBlockEntity::new, MERGER.get()).build(null)
    );

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register(MODID+"_tab", () -> CreativeModeTab.builder()
            .title(Component.literal("Satiscraftory"))
            .icon(() -> new ItemStack(CONVEYOR_1_ITEM.get()))
            .displayItems((params, output) -> {
                output.accept(CONVEYOR_1_ITEM.get());
                output.accept(CONVEYOR_2_ITEM.get());
                output.accept(CONVEYOR_3_ITEM.get());
                output.accept(CONVEYOR_4_ITEM.get());
                output.accept(CONVEYOR_5_ITEM.get());
                output.accept(CONVEYOR_6_ITEM.get());
                output.accept(SPLITTER_ITEM.get());
                output.accept(MERGER_ITEM.get());
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

    private static RegistryObject<Block> registerConveyorBlock(String name, ConveyorTier tier) {
        return BLOCKS.register(name, () -> new ConveyorBlock(
                BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL).noOcclusion(),
                tier,
                ResourceLocation.fromNamespaceAndPath(MODID, "models/block/" + name + ".json")
        ));
    }

    private static RegistryObject<Item> registerConveyorItem(String name, RegistryObject<Block> block) {
        return ITEMS.register(name, () -> new ConveyorItem(block.get(), new Item.Properties()));
    }
}
