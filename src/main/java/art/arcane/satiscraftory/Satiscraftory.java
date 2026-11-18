package art.arcane.satiscraftory;

import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.ConveyorEndBlock;
import art.arcane.satiscraftory.block.MergerBlock;
import art.arcane.satiscraftory.block.SplitterBlock;
import art.arcane.satiscraftory.block.SmartSplitterBlock;
import art.arcane.satiscraftory.block.SmelterBlock;
import art.arcane.satiscraftory.block.ConstructorBlock;
import art.arcane.satiscraftory.block.AssemblerBlock;
import art.arcane.satiscraftory.block.ManufacturerBlock;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity;
import art.arcane.satiscraftory.block.entity.ConveyorBlockEntity.SplineVisualItemEntity;
import art.arcane.satiscraftory.block.entity.ConveyorEndBlockEntity;
import art.arcane.satiscraftory.block.entity.MergerBlockEntity;
import art.arcane.satiscraftory.block.entity.SplitterBlockEntity;
import art.arcane.satiscraftory.block.entity.SmartSplitterBlockEntity;
import art.arcane.satiscraftory.block.entity.SmelterBlockEntity;
import art.arcane.satiscraftory.block.entity.ConstructorBlockEntity;
import art.arcane.satiscraftory.block.entity.AssemblerBlockEntity;
import art.arcane.satiscraftory.block.entity.ManufacturerBlockEntity;
import art.arcane.satiscraftory.config.BeltClientConfig;
import art.arcane.satiscraftory.data.ConveyorTier;
import art.arcane.satiscraftory.item.ConveyorItem;
import art.arcane.satiscraftory.network.BeltInteractionNetwork;
import art.arcane.satiscraftory.network.BeltPlacementNetwork;
import art.arcane.satiscraftory.network.CraftingMachineNetwork;
import art.arcane.satiscraftory.menu.ConstructorMenu;
import art.arcane.satiscraftory.menu.AssemblerMenu;
import art.arcane.satiscraftory.menu.ManufacturerMenu;
import art.arcane.satiscraftory.menu.SmartSplitterMenu;
import art.arcane.satiscraftory.network.SmartSplitterNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
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
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);
    public static final RegistryObject<MenuType<ConstructorMenu>> CONSTRUCTOR_MENU = MENUS.register(
            "constructor", () -> IForgeMenuType.create(ConstructorMenu::new));
    public static final RegistryObject<MenuType<AssemblerMenu>> ASSEMBLER_MENU = MENUS.register(
            "assembler", () -> IForgeMenuType.create(AssemblerMenu::new));
    public static final RegistryObject<MenuType<ManufacturerMenu>> MANUFACTURER_MENU = MENUS.register(
            "manufacturer", () -> IForgeMenuType.create(ManufacturerMenu::new));

    public static final RegistryObject<MenuType<SmartSplitterMenu>> SMART_SPLITTER_MENU = MENUS.register(
            "smart_splitter", () -> IForgeMenuType.create(SmartSplitterMenu::new));

    public static final RegistryObject<EntityType<SplineVisualItemEntity>> CONVEYOR_ITEM_VISUAL = ENTITIES.register(
            "conveyor_item_visual",
            () -> EntityType.Builder.<SplineVisualItemEntity>createNothing(MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .noSave()
                    .noSummon()
                    .clientTrackingRange(0)
                    .build(MODID + ":conveyor_item_visual")
    );

    public static final RegistryObject<Block> CONVEYOR_1 = registerConveyorBlock("conveyor_1", ConveyorTier.MARK_1);
    public static final RegistryObject<Block> CONVEYOR_2 = registerConveyorBlock("conveyor_2", ConveyorTier.MARK_2);
    public static final RegistryObject<Block> CONVEYOR_3 = registerConveyorBlock("conveyor_3", ConveyorTier.MARK_3);
    public static final RegistryObject<Block> CONVEYOR_4 = registerConveyorBlock("conveyor_4", ConveyorTier.MARK_4);
    public static final RegistryObject<Block> CONVEYOR_5 = registerConveyorBlock("conveyor_5", ConveyorTier.MARK_5);
    public static final RegistryObject<Block> CONVEYOR_6 = registerConveyorBlock("conveyor_6", ConveyorTier.MARK_6);
    public static final RegistryObject<Block> CONVEYOR_END = BLOCKS.register(
            "conveyor_end",
            () -> new ConveyorEndBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL).noCollission())
    );
    public static final RegistryObject<Block> SPLITTER = BLOCKS.register(
            "splitter",
            () -> new SplitterBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL))
    );
    public static final RegistryObject<Block> SMART_SPLITTER = BLOCKS.register(
            "smart_splitter",
            () -> new SmartSplitterBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL))
    );
    public static final RegistryObject<Block> MERGER = BLOCKS.register(
            "merger",
            () -> new MergerBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL))
    );
    public static final RegistryObject<Block> SMELTER = BLOCKS.register(
            "smelter",
            () -> new SmelterBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).requiresCorrectToolForDrops())
    );
    public static final RegistryObject<Block> CONSTRUCTOR = BLOCKS.register(
            "constructor",
            () -> new ConstructorBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).requiresCorrectToolForDrops())
    );

    public static final RegistryObject<Block> ASSEMBLER = BLOCKS.register(
            "assembler",
            () -> new AssemblerBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).requiresCorrectToolForDrops())
    );

    public static final RegistryObject<Block> MANUFACTURER = BLOCKS.register(
            "manufacturer",
            () -> new ManufacturerBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).requiresCorrectToolForDrops())
    );

    public static final RegistryObject<Item> CONVEYOR_1_ITEM = registerConveyorItem("conveyor_1", CONVEYOR_1);
    public static final RegistryObject<Item> CONVEYOR_2_ITEM = registerConveyorItem("conveyor_2", CONVEYOR_2);
    public static final RegistryObject<Item> CONVEYOR_3_ITEM = registerConveyorItem("conveyor_3", CONVEYOR_3);
    public static final RegistryObject<Item> CONVEYOR_4_ITEM = registerConveyorItem("conveyor_4", CONVEYOR_4);
    public static final RegistryObject<Item> CONVEYOR_5_ITEM = registerConveyorItem("conveyor_5", CONVEYOR_5);
    public static final RegistryObject<Item> CONVEYOR_6_ITEM = registerConveyorItem("conveyor_6", CONVEYOR_6);
    public static final RegistryObject<Item> SPLITTER_ITEM = ITEMS.register("splitter", () -> new BlockItem(SPLITTER.get(), new Item.Properties()));
    public static final RegistryObject<Item> SMART_SPLITTER_ITEM = ITEMS.register("smart_splitter", () -> new BlockItem(SMART_SPLITTER.get(), new Item.Properties()));
    public static final RegistryObject<Item> MERGER_ITEM = ITEMS.register("merger", () -> new BlockItem(MERGER.get(), new Item.Properties()));
    public static final RegistryObject<Item> SMELTER_ITEM = ITEMS.register("smelter", () -> new BlockItem(SMELTER.get(), new Item.Properties()));
    public static final RegistryObject<Item> CONSTRUCTOR_ITEM = ITEMS.register("constructor", () -> new BlockItem(CONSTRUCTOR.get(), new Item.Properties()));

    public static final RegistryObject<Item> ASSEMBLER_ITEM = ITEMS.register("assembler", () -> new BlockItem(ASSEMBLER.get(), new Item.Properties()));

    public static final RegistryObject<Item> MANUFACTURER_ITEM = ITEMS.register("manufacturer", () -> new BlockItem(MANUFACTURER.get(), new Item.Properties()));

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
    public static final RegistryObject<BlockEntityType<SmartSplitterBlockEntity>> SMART_SPLITTER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "smart_splitter",
            () -> BlockEntityType.Builder.of(SmartSplitterBlockEntity::new, SMART_SPLITTER.get()).build(null)
    );
    public static final RegistryObject<BlockEntityType<MergerBlockEntity>> MERGER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "merger",
            () -> BlockEntityType.Builder.of(MergerBlockEntity::new, MERGER.get()).build(null)
    );
    public static final RegistryObject<BlockEntityType<SmelterBlockEntity>> SMELTER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "smelter",
            () -> BlockEntityType.Builder.of(SmelterBlockEntity::new, SMELTER.get()).build(null)
    );
    public static final RegistryObject<BlockEntityType<ConstructorBlockEntity>> CONSTRUCTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "constructor",
            () -> BlockEntityType.Builder.of(ConstructorBlockEntity::new, CONSTRUCTOR.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<AssemblerBlockEntity>> ASSEMBLER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "assembler",
            () -> BlockEntityType.Builder.of(AssemblerBlockEntity::new, ASSEMBLER.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<ManufacturerBlockEntity>> MANUFACTURER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "manufacturer",
            () -> BlockEntityType.Builder.of(ManufacturerBlockEntity::new, MANUFACTURER.get()).build(null)
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
                output.accept(SMART_SPLITTER_ITEM.get());
                output.accept(MERGER_ITEM.get());
                output.accept(SMELTER_ITEM.get());
                output.accept(CONSTRUCTOR_ITEM.get());
                output.accept(ASSEMBLER_ITEM.get());
                output.accept(MANUFACTURER_ITEM.get());
            })
            .build());


    public Satiscraftory(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, BeltClientConfig.SPEC);
        BeltPlacementNetwork.register();
        CraftingMachineNetwork.register();
        SmartSplitterNetwork.register();
        BeltInteractionNetwork.register();
        IEventBus modEventBus = context.getModEventBus();
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MENUS.register(modEventBus);
        modEventBus.register(this);
    }

    private static RegistryObject<Block> registerConveyorBlock(String name, ConveyorTier tier) {
        return BLOCKS.register(name, () -> new ConveyorBlock(
                BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL)
                        .noCollission().requiresCorrectToolForDrops(),
                tier,
                ResourceLocation.fromNamespaceAndPath(MODID, "models/block/" + name + ".json")
        ));
    }

    private static RegistryObject<Item> registerConveyorItem(String name, RegistryObject<Block> block) {
        return ITEMS.register(name, () -> new ConveyorItem(block.get(), new Item.Properties()));
    }
}
