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

        add("gui.neosim.run.title","Please Choose Game Mode");
        add("gui.neosim.run.button1","Normal Mode");
        add("gui.neosim.run.button2","Creative Mode");
        add("gui.neosim.run.button3","Hardcore Mode");
        add("gui.neosim.run.tip1","Author doesn't know what will happen:)");     //
        add("gui.neosim.run.tip2","Author doesn't know how to translate:(");     //
        add("gui.neosim.run.tip3","Author doesn't know how to translate:(");     //
    }
}
