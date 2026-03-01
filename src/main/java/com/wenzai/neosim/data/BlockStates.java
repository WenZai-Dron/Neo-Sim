package com.wenzai.neosim.data;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class BlockStates extends BlockStateProvider
{
    public BlockStates(PackOutput output, ExistingFileHelper existingFileHelper)
    {
        super(output, NeoSim.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels()
    {
        simpleBlockWithItem(ModBlocks.BUILDING_CONSTRUCTOR.get(), cubeAll(ModBlocks.BUILDING_CONSTRUCTOR.get()));
        simpleBlockWithItem(ModBlocks.CONTROL_BOX.get(), cubeAll(ModBlocks.CONTROL_BOX.get()));
        simpleBlockWithItem(ModBlocks.MARKER.get(), cubeAll(ModBlocks.MARKER.get()));
        simpleBlockWithItem(ModBlocks.FARMING_BOX.get(), cubeAll(ModBlocks.FARMING_BOX.get()));
        simpleBlockWithItem(ModBlocks.MINING_BOX.get(), cubeAll(ModBlocks.MINING_BOX.get()));
    }
}
