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
        add("gui.neosim.run.buttonNormal","Normal Mode");
        add("gui.neosim.run.buttonCreative","Creative Mode");
        add("gui.neosim.run.buttonHardcore","Hardcore Mode");
        add("gui.neosim.run.buttonSingle","Singleplayer");
        add("gui.neosim.run.buttonMulti","Multiplayer");
        add("gui.neosim.run.buttonClose","Close");
        add("gui.neosim.run.tipNormal","Author doesn't know what will happen:)");
        add("gui.neosim.run.tipCreative","Author doesn't know how to translate:(");
        add("gui.neosim.run.tipHardcore","Author doesn't know how to translate:(");


        add("gui.neosim.hud.mode","mode");
        add("gui.neosim.hud.singleOrMulti","singleOrMulti");
        add("gui.neosim.hud.population","population");
        add("gui.neosim.hud.dayOfWeek","dayOfWeek");
        add("gui.neosim.hud.day","day");
        add("gui.neosim.hud.credit","credit");
    }
}
