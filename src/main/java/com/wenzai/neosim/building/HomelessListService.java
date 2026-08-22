package com.wenzai.neosim.building;

import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.building.ControlBoxPersistence.Resident;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 缺陷 C 结构性方案：服务端权威生成"无家 NPC"名单（替代客户端 loadHomelessNpcs 直读本地文件，
// 同时解决多人下客户端读不到服务器文件、列表恒空的问题）。
// 判定：以 ControlBox.json 全部记录的 residents[] 为唯一"有家"来源 + NPC 档案 home 字段交叉判定——
// 在任一 ControlBox 居民列表中，或档案仍带 home（残留）者，均不算无家。
public final class HomelessListService
{
	private HomelessListService()
	{
	}

	public static List<String> collect(ServerLevel level, String city)
	{
		List<String> out = new ArrayList<>();
		if (level.getServer() == null || city == null || city.isEmpty()) return out;

		// 唯一"有家"来源：全部控制箱记录的居民列表
		Set<String> hasHome = new HashSet<>();
		for (ControlBoxRecord rec : ControlBoxPersistence.load(level, city))
		{
			for (Resident r : rec.residents())
			{
				hasHome.add(r.name());
			}
		}

		// 本城全部 NPC 档案：不在任何居民列表且档案无 home 残留者 = 无家
		for (String name : NpcData.listNpcNames(level, city))
		{
			if (hasHome.contains(name)) continue;
			int home = NpcData.homeStatus(level, city, name);
			if (home == 0)
			{
				out.add(name);
			}
		}
		return out;
	}
}
