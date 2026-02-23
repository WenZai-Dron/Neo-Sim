package com.wenzai.neosim.data;

import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

import java.util.Set;

public class LootTables extends BlockLootSubProvider
{
    public LootTables(HolderLookup.Provider registries)
    {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate()
    {
        dropSelf(ModBlocks.BUILDING_CONSTRUCTOR.get());
        dropSelf(ModBlocks.MARKER.get());
        dropSelf(ModBlocks.FARMING_BOX.get());
        dropSelf(ModBlocks.MINING_BOX.get());
    }

    @Override
    protected Iterable<Block> getKnownBlocks()
    {
        return ModBlocks.BLOCKS.getEntries().stream().map(Holder::value)::iterator;
    }
}
