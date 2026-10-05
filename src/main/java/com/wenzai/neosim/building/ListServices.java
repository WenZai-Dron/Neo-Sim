package com.wenzai.neosim.building;

import com.google.gson.JsonObject;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.ControlBoxPersistence;
import com.wenzai.neosim.block.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.block.ControlBoxPersistence.Resident;
import com.wenzai.neosim.network.ServerToClientPayloads.HireListResponsePayload.HireEntry;
import com.wenzai.neosim.network.ServerToClientPayloads.MissingScanResponsePayload.MissingEntry;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.NpcRegistry;
import com.wenzai.neosim.schematic.MaterialCalculator;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 服务端名单服务：雇佣名单 / 无家名单 / 缺料清单（三个 S→C 名单组装都从这里出，替代客户端直读本地档案）
public final class ListServices
{
	// 雇佣名单条数上限
	private static final int MAX_HIRE_ENTRIES = 200;

	private ListServices()
	{
	}

	// 可雇佣市民清单；jobKind: 0=architect 1=farmer 2=miner 3=courier
	public static List<HireEntry> collectHire(ServerLevel level, String city, int jobKind)
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
			if (out.size() >= MAX_HIRE_ENTRIES) break;
			int jobLevel = switch (jobKind)
			{
				case 1 -> npc.getJobFarmer();
				case 2 -> npc.getJobMiner();
				case 3 -> npc.getJobCourier();
				default -> npc.getJobArchitect();
			};
			out.add(new HireEntry(npc.getNpcName(), jobLevel, npc.getAge(),
					npc.getPregnancyStage() > 0.0F,
					NeoSim.WORKER_MAP.containsValue(npc.getNpcName())));
		}

		// 未加载档案补充
		for (String name : NpcData.listNpcNames(level, city))
		{
			if (out.size() >= MAX_HIRE_ENTRIES) break;
			if (loadedNames.contains(name)) continue;
			JsonObject json = NpcData.load(level, city, name);
			if (json == null) continue;

			int age = json.has("age") ? json.get("age").getAsShort() : 0;
			float pregnancy = json.has("pregnancy") ? json.get("pregnancy").getAsFloat() : 0.0F;
			JsonObject job = JsonUtil.getObject(json, "job");
			int jobLevel = 1;
			if (job != null && job.has(jobField))
			{
				jobLevel = job.get(jobField).getAsByte();
			}
			out.add(new HireEntry(name, jobLevel, age, pregnancy > 0.0F, NeoSim.WORKER_MAP.containsValue(name)));
		}
		return out;
	}

	// 无家 NPC 名单：判定以 ControlBox.json 全部记录的 residents[] 为唯一"有家"来源 + NPC 档案 home 字段交叉判定
	// （在任一 ControlBox 居民列表中，或档案仍带 home 残留者，均不算无家）
	public static List<String> collectHomeless(ServerLevel level, String city)
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

	// 缺料清单：模盒 + 控制箱两处箱子合起来算，按缺料量从多到少
	public static List<MissingEntry> scanMissing(ServerLevel level, BlockPos boxPos)
	{
		List<MissingEntry> out = new ArrayList<>();
		ConstructionTask task = ConstructionEngine.findTask(boxPos);
		if (task == null || task.getBuilding() == null) return out;
		SchematicData sd = task.getBuilding().getSchematic();
		if (sd == null) return out;

		List<ChestBlockEntity> chests = new ArrayList<>(InventoryManager.findNearbyChests(level, boxPos));
		BlockPos cp = task.getBuilding().getControlBoxPos();
		if (cp != null && !cp.equals(boxPos))
		{
			for (ChestBlockEntity chest : InventoryManager.findNearbyChests(level, cp))
			{
				if (!chests.contains(chest)) chests.add(chest);
			}
		}

		// 缺料量按全服全局模式计算（立项基线 0.1：模式不按城市隔离）
		byte mode = ModSavedData.get(level).getMode();

		for (MaterialCalculator.MaterialEntry e : MaterialCalculator.calculate(sd, mode))
		{
			int have = InventoryManager.countItems(chests, e.item);
			int missing = e.count - have;
			if (missing > 0)
			{
				out.add(new MissingEntry(e.item, missing));
			}
		}
		out.sort((a, b) -> Integer.compare(b.count(), a.count()));
		return out;
	}
}
