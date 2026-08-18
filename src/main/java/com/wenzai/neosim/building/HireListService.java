package com.wenzai.neosim.building;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.network.ServerToClientPayloads.HireListResponsePayload.HireEntry;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.NpcRegistry;
import com.wenzai.neosim.storage.NpcData;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 服务端收集"可雇佣市民"清单（替代客户端直接读本地档案；联机/单机统一走此服务）
public final class HireListService
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_ENTRIES = 200;

	private HireListService() {}

	// jobKind: 0=architect 1=farmer 2=miner 3=courier
	public static List<HireEntry> collect(ServerLevel level, String city, int jobKind)
	{
		List<HireEntry> out = new ArrayList<>();
		if (city.isEmpty()) return out;

		String jobField = switch (jobKind)
		{
			case 1 -> "farmer";
			case 2 -> "miner";
			case 3 -> "courier";
			default -> "architect";
		};

		// 已加载实体按实体取值（反映实时状态；C1 城市索引）
		Set<String> loadedNames = new HashSet<>();
		for (Entity npc : NpcRegistry.byCity(city))
		{
			loadedNames.add(npc.getNpcName());
			if (out.size() >= MAX_ENTRIES) break;
			int level0 = switch (jobKind)
			{
				case 1 -> npc.getJobFarmer();
				case 2 -> npc.getJobMiner();
				case 3 -> npc.getJobCourier();
				default -> npc.getJobArchitect();
			};
			out.add(new HireEntry(npc.getNpcName(), level0, npc.getAge(),
					npc.getPregnancyStage() > 0.0F,
					NeoSim.WORKER_MAP.containsValue(npc.getNpcName())));
		}

		// 未加载档案补充
		for (String name : NpcData.listNpcNames(level, city))
		{
			if (out.size() >= MAX_ENTRIES) break;
			if (loadedNames.contains(name)) continue;
			JsonObject json = NpcData.load(level, city, name);
			if (json == null) continue;

			int age = json.has("age") ? json.get("age").getAsShort() : 0;
			float pregnancy = json.has("pregnancy") ? json.get("pregnancy").getAsFloat() : 0.0F;
			JsonObject job = JsonUtil.getObject(json, "job");
			int level0 = 1;
			if (job != null && job.has(jobField))
			{
				level0 = job.get(jobField).getAsByte();
			}
			out.add(new HireEntry(name, level0, age, pregnancy > 0.0F, NeoSim.WORKER_MAP.containsValue(name)));
		}
		return out;
	}
}
