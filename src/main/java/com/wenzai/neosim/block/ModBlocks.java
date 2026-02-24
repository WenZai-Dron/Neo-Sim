package com.wenzai.neosim.block;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlocks
{
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(NeoSim.MOD_ID);



    // 此处注册方块
    public static final DeferredBlock<Block> BUILDING_CONSTRUCTOR =
            registerBlocks("building_constructor", () -> new Block(BlockBehaviour.Properties.of().strength(0.5F)));
    public static final DeferredBlock<Block> CONTROL_BOX =
            registerBlocks("control_box", () -> new Block(BlockBehaviour.Properties.of().strength(0.5F)
                    .noLootTable()));
    public static final DeferredBlock<Block> MARKER =
            registerBlocks("marker", () -> new Block(BlockBehaviour.Properties.of().strength(0.5F)));
    public static final DeferredBlock<Block> FARMING_BOX =
            registerBlocks("farming_box", () -> new Block(BlockBehaviour.Properties.of().strength(0.5F)));
    public static final DeferredBlock<Block> MINING_BOX =
            registerBlocks("mining_box", () -> new Block(BlockBehaviour.Properties.of().strength(0.5F)));

    private static  <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block)
    {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private  static <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier block)
    {
        DeferredBlock<T> blocks = BLOCKS.register(name, block);
        registerBlockItems(name, blocks);
        return blocks;
    }

    public  static void register(IEventBus eventBus)
    {
        BLOCKS.register(eventBus);
    }
}
