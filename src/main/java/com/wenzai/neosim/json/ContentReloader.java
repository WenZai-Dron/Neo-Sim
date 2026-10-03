package com.wenzai.neosim.json;

// 内容表统一热重载入口：NeoSim/Json/ 目录有任何变化就一起重载
// 单独让某张表各自轮询 mtime 会互相"吃掉"变更（先轮询到的表消费掉时间戳），所以集中在这里
public final class ContentReloader
{
	private ContentReloader()
	{
	}

	// 目录有变化才重载；返回是否发生了重载
	public static boolean reloadIfChanged()
	{
		if (!JsonContent.pollChanged())
		{
			// 首次调用（各表还没加载过）时也要建立一次基线
			if (!com.wenzai.neosim.compat.attached.AttachedBlockTable.isLoaded())
			{
				reloadAll();
				return true;
			}
			return false;
		}
		reloadAll();
		return true;
	}

	public static void reloadAll()
	{
		// 模组依赖性方块表先扫：AttachedBlockTable 的每块判定要问它"这个方块是不是模组的"
		com.wenzai.neosim.compat.modded.ModBlockRegistry.reload();
		com.wenzai.neosim.compat.attached.AttachedBlockTable.reload();
		com.wenzai.neosim.compat.crops.CropRegistry.reload();
		com.wenzai.neosim.schematic.MaterialCalculator.invalidateClassification();

		// 界面个人设置（NeoSim/Json/ui/<玩家名>.json）：只有客户端读，专用服务器跳过
		if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT)
		{
			com.wenzai.neosim.client.ui.UiSettings.refresh();
		}
	}
}
