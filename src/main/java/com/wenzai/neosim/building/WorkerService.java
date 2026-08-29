package com.wenzai.neosim.building;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.BuildingConstructor;
import com.wenzai.neosim.block.DeliveryEngine;
import com.wenzai.neosim.block.DeliveryTask;
import com.wenzai.neosim.block.PlotTask;
import com.wenzai.neosim.block.WorkPlotEngine;
import com.wenzai.neosim.compat.sable.PhysicsWorld;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.storage.CityManager;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

// 服务端雇佣/解雇核心（客户端 GUI 只发包，此处做全部校验与落盘）
public final class WorkerService
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private WorkerService()
	{
	}

	// 返回 null=成功，否则为提示文本
	public static String tryHire(ServerLevel level, ServerPlayer player, BlockPos boxPos, String npcName)
	{
		String city = CityManager.getCity(player.getUUID());
		if (city.isEmpty()) return "§c请先加入城市";
		if (npcName == null || npcName.isBlank()) return "§c无效的市民";
		if (NeoSim.WORKER_MAP.containsValue(npcName)) return "§c该市民已在别处工作";

		// 档案校验：存在、成年、非产假
		JsonObject json = NpcData.load(level, city, npcName);
		if (json == null) return "§c市民档案不存在";
		if (json.has("age") && json.get("age").getAsInt() < Config.LIFE_ADULT_AGE.get())
			return "§c未成年不可雇佣";
		if (json.has("pregnancy") && json.get("pregnancy").getAsFloat() > 0.0F)
			return "§c产假中不可雇佣";

		// 实体（可能未加载）：未加载则从档案恢复到岗位位置（世界坐标：模盒在子世界时投影到甲板，
		// 避免实体生成在 20.48M 局部坐标——那是保存卸载自旋的触发源）
		Entity npc = Entity.findByNpcName(level, npcName);
		if (npc == null)
		{
			npc = Manage.spawnSingle(level, city, npcName,
					PhysicsWorld.toWorld(level, boxPos));
			if (npc == null) return "§c市民恢复失败";
		}
		else if (!city.equals(npc.getCityName()))
		{
			return "§c该市民不属于你的城市";
		}

		// 按岗位类型雇佣
		ConstructionTask ct = ConstructionEngine.findTask(boxPos);
		if (ct != null && ct.getState() != BuildingInstance.BuildState.COMPLETE)
		{
			NeoSim.WORKER_MAP.put(boxPos, npcName);
			ct.assignWorker();
			ConstructionEngine.saveAllTasks(level);
			npc.assignToSite(PhysicsWorld.toWorld(level, boxPos));
			PhysicsWorld.attachNpc(level, npc, boxPos);
			LOGGER.info("NeoSim-WorkerService: hired builder '{}' at {}", npcName, boxPos);
			return null;
		}
		PlotTask plot = WorkPlotEngine.findTask(boxPos);
		if (plot != null)
		{
			plot.hireWorker(npcName);
			WorkPlotEngine.saveAll(level);
			LOGGER.info("NeoSim-WorkerService: hired worker '{}' at {}", npcName, boxPos);
			return null;
		}
		DeliveryTask delivery = DeliveryEngine.findTask(boxPos);
		if (delivery != null)
		{
			delivery.hireWorker(npcName);
			DeliveryEngine.saveAll(level);
			LOGGER.info("NeoSim-WorkerService: hired courier '{}' at {}", npcName, boxPos);
			return null;
		}

		// 建筑模盒：允许先雇佣（尚无建造任务）。
		// 之后在模盒 GUI 确认蓝图时，createBuilding 会从 WORKER_MAP 快照工人到任务。
		if (level.getBlockState(boxPos).getBlock() instanceof BuildingConstructor)
		{
			NeoSim.WORKER_MAP.put(boxPos, npcName);
			npc.assignToSite(PhysicsWorld.toWorld(level, boxPos));
			PhysicsWorld.attachNpc(level, npc, boxPos);
			LOGGER.info("NeoSim-WorkerService: pre-hired builder '{}' at {} (no task yet, waiting blueprint)", npcName, boxPos);
			return null;
		}

		LOGGER.warn("NeoSim-WorkerService: no hireable position at {} (buildings={}, block={}, npc='{}')",
				boxPos, ConstructionEngine.getActiveBuildings().size(),
				level.getBlockState(boxPos).getBlock(), npcName);
		return "§c此处没有可雇佣的岗位";
	}

	// 解雇：返回 null=成功，否则为提示文本
	public static String tryFire(ServerLevel level, BlockPos boxPos)
	{
		ConstructionTask ct = ConstructionEngine.findTask(boxPos);
		if (ct != null && ct.getState() != BuildingInstance.BuildState.COMPLETE)
		{
			String name = NeoSim.WORKER_MAP.remove(boxPos);
			if (name != null)
			{
				Entity npc = Entity.findByNpcName(level, name);
				if (npc != null)
				{
					npc.releaseFromSite();
					npc.setBuildAnim(0.0F);
				}
			}
			// 状态由 ConstructionTask.tick 自动回落 WAITING_FOR_WORKER
			ConstructionEngine.saveAllTasks(level);
			return null;
		}
		PlotTask plot = WorkPlotEngine.findTask(boxPos);
		if (plot != null)
		{
			plot.fireWorker();
			WorkPlotEngine.saveAll(level);
			return null;
		}
		DeliveryTask delivery = DeliveryEngine.findTask(boxPos);
		if (delivery != null)
		{
			delivery.fireWorker();
			DeliveryEngine.saveAll(level);
			return null;
		}

		// 建筑模盒：先雇佣的工人直接解除（无任务场景）
		if (level.getBlockState(boxPos).getBlock() instanceof BuildingConstructor)
		{
			String name = NeoSim.WORKER_MAP.remove(boxPos);
			if (name != null)
			{
				Entity npc = Entity.findByNpcName(level, name);
				if (npc != null)
				{
					npc.releaseFromSite();
					npc.setBuildAnim(0.0F);
				}
				LOGGER.info("NeoSim-WorkerService: pre-hire cancelled for '{}' at {}", name, boxPos);
			}
			return null;
		}
		return "§c此处没有可解雇的岗位";
	}
}
