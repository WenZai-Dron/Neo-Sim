package com.wenzai.neosim.data;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.ModBlocks;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class LangZhCn extends LanguageProvider
{
    public LangZhCn(PackOutput output)
    {
        super(output, NeoSim.MOD_ID, "zh_cn");
    }

    @Override
    protected void addTranslations()
    {
        add(ModBlocks.BUILDING_CONSTRUCTOR.get(), "建筑模盒");
        add(ModBlocks.CONTROL_BOX.get(), "控制箱");
        add(ModBlocks.MARKER.get(), "标记棒");
        add(ModBlocks.FARMING_BOX.get(),  "农业盒");
        add(ModBlocks.MINING_BOX.get(), "矿业盒");

        add("itemGroup.neosim_tab","新模拟城市");

        add("gui.neosim.run.title","请选择游戏模式");
        add("gui.neosim.run.buttonNormal","普通模式");
        add("gui.neosim.run.buttonCreative","创造模式");
        add("gui.neosim.run.buttonHardcore","硬核模式");
        add("gui.neosim.run.buttonSingle","单人模式");
        add("gui.neosim.run.buttonMulti","多人模式");
        add("gui.neosim.run.buttonClose","确定已选模式");
        add("gui.neosim.run.tipNormal","作者也不知道会干嘛:)");
        add("gui.neosim.run.tipCreative","无限资金和资源");
        add("gui.neosim.run.tipHardcore","建筑需要所有方块");

        add("gui.neosim.hud.mode","游戏模式");
        add("gui.neosim.hud.singleOrMulti","游玩模式");
        add("gui.neosim.hud.population","人口");
        add("gui.neosim.hud.dayOfWeek","星期");
        add("gui.neosim.hud.day","天数");
        add("gui.neosim.hud.credit","资金");
    }
}
