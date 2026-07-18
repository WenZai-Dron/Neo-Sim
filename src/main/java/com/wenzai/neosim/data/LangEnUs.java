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

        add("gui.neosim.run.title","Run");
        add("gui.neosim.run.buttonNormal","Normal Mode");
        add("gui.neosim.run.buttonCreative","Creative Mode");
        add("gui.neosim.run.buttonHardcore","Hardcore Mode");
        add("gui.neosim.run.buttonClose","Close");
        add("gui.neosim.run.tipNormal","Author doesn't know what will happen:)");
        add("gui.neosim.run.tipCreative","Author doesn't know how to translate:(");
        add("gui.neosim.run.tipHardcore","Author doesn't know how to translate:(");


        add("gui.neosim.hud.mode","mode");
        add("gui.neosim.hud.population","population");
        add("gui.neosim.hud.dayOfWeek","dayOfWeek");
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

        add("gui.neosim.cityAdd.title","Add City");
        add("gui.neosim.cityChoose.title","Choose City");

        add("gui.neosim.cityAdd.input","City Name");
        add("gui.neosim.cityAdd.buttonConfirm","Confirm");

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

        add("gui.neosim.npc.title","NPC Interaction");
        add("gui.neosim.npc.talk","Talk");
        add("gui.neosim.npc.interact","Interact");
        add("gui.neosim.npc.gift","Gift");
        add("gui.neosim.npc.setSkin","Set Skin");
        add("gui.neosim.npc.rename","Rename");
        add("gui.neosim.npc.back","← Back");
        add("gui.neosim.npc.follow","Follow");
        add("gui.neosim.npc.stay","Stay");
        add("gui.neosim.npc.teleportTo","Teleport to NPC");
        add("gui.neosim.npc.teleportHere","Teleport NPC Here");

        add("gui.neosim.npc.info.title","%s");
        add("gui.neosim.npc.info.sex","Sex: %s");
        add("gui.neosim.npc.info.male","Male");
        add("gui.neosim.npc.info.female","Female");
        add("gui.neosim.npc.info.city","City: %s");
        add("gui.neosim.npc.info.health","Health: %s / %s");
        add("gui.neosim.npc.info.skin","Skin: %s");
        add("gui.neosim.npc.info.noCity","None");
        add("gui.neosim.npc.info.noSkin","Not set");

        add("gui.neosim.npc.rename.input","Enter new name…");
        add("gui.neosim.npc.rename.confirm","Confirm");
        add("gui.neosim.npc.rename.cancel","Cancel");

        add("gui.neosim.npc.skin.prevPage","Previous");
        add("gui.neosim.npc.skin.nextPage","Next");
    }
}
