package com.wenzai.neosim.data;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class LangEnUs extends LanguageProvider
{
    public LangEnUs(PackOutput output)
    {
        super(output, NeoSim.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations()
    {
        add(ModBlocks.BUILDING_CONSTRUCTOR.get(), "Building Constructor");
        add(ModBlocks.CONTROL_BOX.get(), "Control Box");
        add(ModBlocks.MARKER.get(), "Marker");
        add(ModBlocks.FARMING_BOX.get(),  "Farming Box");
        add(ModBlocks.MINING_BOX.get(), "Mining Box");

        add("itemGroup.neosim_tab","Neo-Sim");
    }
}
