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
        add(ModBlocks.FARMING_BOX.get(), "农业盒");
        add(ModBlocks.MINING_BOX.get(), "矿业盒");

        add("itemGroup.neosim_tab","新模拟城市");

        add("gui.neosim.run.title","运行");
        add("gui.neosim.run.buttonNormal","普通模式");
        add("gui.neosim.run.buttonCreative","创造模式");
        add("gui.neosim.run.buttonHardcore","硬核模式");
        add("gui.neosim.run.buttonClose","确定");
        add("gui.neosim.run.tipNormal","作者也不知道会干嘛:)");
        add("gui.neosim.run.tipCreative","无限资金和资源");
        add("gui.neosim.run.tipHardcore","建筑需要所有方块");
        
        add("gui.neosim.hud.population","人口");
        add("gui.neosim.hud.day","第%s天");
        add("gui.neosim.hud.credit","资金");
        add("gui.neosim.hud.monday","星期一");
        add("gui.neosim.hud.tuesday","星期二");
        add("gui.neosim.hud.wednesday","星期三");
        add("gui.neosim.hud.thursday","星期四");
        add("gui.neosim.hud.friday","星期五");
        add("gui.neosim.hud.saturday","星期六");
        add("gui.neosim.hud.sunday","星期日");

        add("gui.neosim.city.title","城市");
        add("gui.neosim.city.add","新增城市");
        add("gui.neosim.city.choose","选择城市");
        add("gui.neosim.city.buttonConfirm","确定");

        add("gui.neosim.BuildingConstructor.title","建筑模盒");
        add("gui.neosim.BuildingConstructor.status","当前状态: %s");
        add("gui.neosim.BuildingConstructor.buildingType","建筑类型: %s");
        add("gui.neosim.BuildingConstructor.page0.hint","请为建筑模盒选择一个任务");
        add("gui.neosim.BuildingConstructor.page1.hint","请选择一种建筑类型");
        add("gui.neosim.BuildingConstructor.hireFireWorker","雇佣工人 / 炒了XXX");
        add("gui.neosim.BuildingConstructor.chooseBuilding","选择建筑");
        add("gui.neosim.BuildingConstructor.buildingPreview","建筑预览");
        add("gui.neosim.BuildingConstructor.continuePause","继续 / 暂停");
        add("gui.neosim.BuildingConstructor.choosePlan","选择规划");
        add("gui.neosim.BuildingConstructor.moveBuilding","移动建筑");
        add("gui.neosim.BuildingConstructor.typeResidential","住宅");
        add("gui.neosim.BuildingConstructor.typeCommercial","商业");
        add("gui.neosim.BuildingConstructor.typeIndustrial","工业");
        add("gui.neosim.BuildingConstructor.typeOther","其他");

        add("entity.neo_sim.nsnpc","新模拟城市NPC");

        add("gui.neosim.npc.title","NPC");
        add("gui.neosim.npc.setSkin","设置皮肤");
        add("gui.neosim.npc.rename","重命名");
        add("gui.neosim.npc.back","返回");
        add("gui.neosim.npc.info.sex","性别: %s");
        add("gui.neosim.npc.info.male","男");
        add("gui.neosim.npc.info.female","女");
        add("gui.neosim.npc.info.city","城市: %s");
        add("gui.neosim.npc.info.noCity","无");
        add("gui.neosim.npc.rename.surname","姓");
        add("gui.neosim.npc.rename.givenName","名");
        add("gui.neosim.npc.rename.confirm","确认");
        add("gui.neosim.npc.rename.cancel","取消");
        add("gui.neosim.npc.skin.prevPage","上一页");
        add("gui.neosim.npc.skin.nextPage","下一页");
        add("gui.neosim.npc.skin.openFolder","打开皮肤文件夹");
        add("gui.neosim.npc.skin.searchPlayer","输入玩家名");
        add("gui.neosim.npc.skin.searchConfirm","确定");
        add("gui.neosim.npc.skin.searching","正在搜索...");
        add("gui.neosim.npc.skin.searchFailed","失败");
    }
}
