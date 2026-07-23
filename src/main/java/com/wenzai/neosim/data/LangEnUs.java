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
        add(ModBlocks.DELIVERY_BOX.get(), "Delivery Box");

        add("itemGroup.neosim_tab","Neo-Sim");

        add("gui.neosim.run.title","Run");
        add("gui.neosim.run.buttonNormal","Normal Mode");
        add("gui.neosim.run.buttonCreative","Creative Mode");
        add("gui.neosim.run.buttonHardcore","Hardcore Mode");
        add("gui.neosim.run.buttonClose","Close");
        add("gui.neosim.run.tipNormal","Author doesn't know what will happen:)");
        add("gui.neosim.run.tipCreative","Unlimited funds and resources");
        add("gui.neosim.run.tipHardcore","Buildings require all blocks");

        add("gui.neosim.hud.population","population");
        add("gui.neosim.hud.day","Day %s");
        add("gui.neosim.hud.credit","credit");
        add("gui.neosim.hud.monday","Monday");
        add("gui.neosim.hud.tuesday","Tuesday");
        add("gui.neosim.hud.wednesday","Wednesday");
        add("gui.neosim.hud.thursday","Thursday");
        add("gui.neosim.hud.friday","Friday");
        add("gui.neosim.hud.saturday","Saturday");
        add("gui.neosim.hud.sunday","Sunday");

        add("gui.neosim.city.title","City");
        add("gui.neosim.city.add","Add City");
        add("gui.neosim.city.choose","Choose City");
        add("gui.neosim.city.buttonConfirm","OK");

        add("gui.neosim.BuildingConstructor.title","Building Constructor");
        add("gui.neosim.BuildingConstructor.status","Current status: %s");
        add("gui.neosim.BuildingConstructor.buildingType","Building type: %s");
        add("gui.neosim.BuildingConstructor.page0.hint","Please choose a task for this building constructor");
        add("gui.neosim.BuildingConstructor.page1.hint","Please choose a type of building");
        add("gui.neosim.BuildingConstructor.hireFireWorker","Hire / Fire worker");
        add("gui.neosim.BuildingConstructor.chooseBuilding","Choose building");
        add("gui.neosim.BuildingConstructor.buildingPreview","Building preview");
        add("gui.neosim.BuildingConstructor.continuePause","Continue / Pause");
        add("gui.neosim.BuildingConstructor.choosePlan","Choose plan");
        add("gui.neosim.BuildingConstructor.moveBuilding","Move building");
        add("gui.neosim.BuildingConstructor.typeResidential","Residential");
        add("gui.neosim.BuildingConstructor.typeCommercial","Commercial");
        add("gui.neosim.BuildingConstructor.typeIndustrial","Industrial");
        add("gui.neosim.BuildingConstructor.typeOther","Other");
        
        add("entity.neo_sim.nsnpc","NSnpc");

        add("gui.neosim.npc.title","NPC");
        add("gui.neosim.npc.setSkin","Set Skin");
        add("gui.neosim.npc.rename","Rename");
        add("gui.neosim.npc.rename.surname","Surname");
        add("gui.neosim.npc.rename.givenName","Given Name");
        add("gui.neosim.npc.rename.confirm","Confirm");
        add("gui.neosim.npc.rename.cancel","Cancel");
        add("gui.neosim.npc.back","Back");
        add("gui.neosim.npc.info.sex","Sex: %s");
        add("gui.neosim.npc.info.male","Male");
        add("gui.neosim.npc.info.female","Female");
        add("gui.neosim.npc.info.city","City: %s");
        add("gui.neosim.npc.info.noCity","None");
        add("gui.neosim.npc.info.age","Age: %d");
        add("gui.neosim.npc.skin.prevPage","Previous");
        add("gui.neosim.npc.skin.nextPage","Next");
        add("gui.neosim.npc.skin.openFolder","Open Skins Folder");
        add("gui.neosim.npc.skin.searchPlayer","Enter Player Name");
        add("gui.neosim.npc.skin.searchConfirm","OK");
        add("gui.neosim.npc.skin.searching","Searching...");
        add("gui.neosim.npc.skin.searchFailed","Failed");
    }
}
