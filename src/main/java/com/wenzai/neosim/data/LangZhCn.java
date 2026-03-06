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
        add("gui.neosim.run.button1","普通模式");
        add("gui.neosim.run.button2","创造模式");
        add("gui.neosim.run.button3","硬核模式");
        add("gui.neosim.run.tip1","作者也不知道会干嘛:)");
        add("gui.neosim.run.tip2","无限资金和资源");
        add("gui.neosim.run.tip3","建筑需要所有方块");
    }
}
