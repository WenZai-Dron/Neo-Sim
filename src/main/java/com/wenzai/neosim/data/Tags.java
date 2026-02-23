package com.wenzai.neosim.data;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

public class Tags extends BlockTagsProvider
{
    public Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, ExistingFileHelper existingFileHelper)
    {
        super(output, lookupProvider, NeoSim.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider)
    {
        tag(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)
                .add(ModBlocks.BUILDING_CONSTRUCTOR.get())
                .add(ModBlocks.CONTROL_BOX.get())
                .add(ModBlocks.MARKER.get())
                .add(ModBlocks.FARMING_BOX.get())
                .add(ModBlocks.MINING_BOX.get());
    }
}
