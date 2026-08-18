package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.block.*;
import com.wenzai.neosim.client.ClientBlockInteractions;
import com.wenzai.neosim.npc.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.slf4j.Logger;

// 破坏事件处理
@EventBusSubscriber(modid = NeoSim.MOD_ID)
public class BreakHandler
{
	private static final Logger LOGGER = LogUtils.getLogger();

	@SubscribeEvent
	public static void onBlockBreak(BlockEvent.BreakEvent event)
	{
		if (!(event.getLevel() instanceof ServerLevel level)) return;

		BlockPos pos = event.getPos();
		if (event.getState().getBlock() instanceof BuildingConstructor)
		{
			handleConstructorBreak(level, pos);
		}
		else if (event.getState().getBlock() instanceof ControlBox)
		{
			handleControlBoxBreak(level, pos);
		}
		else if (event.getState().getBlock() instanceof Marker)
		{
			// 双保险：标记棒被破坏立即注销（正常玩家破坏已由 Marker.onDestroyedByPlayer 触发）
			MarkerManager.onRemoved(level, pos);
		}
	}

	// 爆炸炸掉工作盒/快递盒：同样清理任务与工人，否则NPC会保持工作AI永不恢复
	@SubscribeEvent
	public static void onExplosionDetonate(ExplosionEvent.Detonate event)
	{
		if (!(event.getLevel() instanceof ServerLevel level)) return;
		for (BlockPos pos : event.getAffectedBlocks())
		{
			Block b = level.getBlockState(pos).getBlock();
			if (b instanceof FarmingBox || b instanceof MiningBox)
			{
				WorkPlotEngine.removePlotAt(level, pos);
			}
			else if (b instanceof DeliveryBox)
			{
				DeliveryEngine.removeBoxAt(level, pos);
			}
			else if (b instanceof Marker)
			{
				// 爆炸炸掉标记棒：立即注销，光幕不再残留
				MarkerManager.onRemoved(level, pos);
			}
		}
	}

	// 模盒被破坏：解雇NPC、取消建造任务、删除控制箱记录
	private static void handleConstructorBreak(ServerLevel level, BlockPos pos)
	{
		// 先取任务（取消后取不到控制箱位置）
		com.wenzai.neosim.building.ConstructionTask task =
				com.wenzai.neosim.building.ConstructionEngine.findTask(pos);

		String workerName = NeoSim.WORKER_MAP.get(pos);
		if (workerName != null)
		{
			Entity npc = com.wenzai.neosim.npc.Entity.findByNpcName(level, workerName);
			if (npc != null)
			{
				npc.releaseFromSite();

				// 任务中断：手臂复位+清空手持
				npc.setBuildAnim(0.0F);
				npc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
						net.minecraft.world.item.ItemStack.EMPTY);
			}
			NeoSim.WORKER_MAP.remove(pos);
			LOGGER.info("NeoSim-BreakHandler: auto-fired '{}' from {}", workerName, pos);
		}

		// 取消模盒的整地任务（工人已在上方统一解雇）
		com.wenzai.neosim.block.TerraformEngine.cancelAt(level, pos);

		// 取消模盒的建造任务
		com.wenzai.neosim.building.ConstructionEngine.cancelTaskAt(pos, level);
		com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(level);

		// 清理已选蓝图缓存
		if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT)
		{
			ClientBlockInteractions.clearSelectedAt(pos);
		}

		// 删除控制箱记录：已放置的控制箱方块保留，右键不可交互；居民失去家
		if (task != null)
		{
			com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord removed =
					com.wenzai.neosim.building.ControlBoxPersistence.removeAt(
							level, task.getBuilding().getControlBoxPos());
			if (removed != null)
			{
				com.wenzai.neosim.npc.CityLivingManager.evictResidents(level, removed);
			}
		}
	}

	// 控制箱被破坏：删除对应记录；居民失去家
	private static void handleControlBoxBreak(ServerLevel level, BlockPos pos)
	{
		com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord removed =
				com.wenzai.neosim.building.ControlBoxPersistence.removeAt(level, pos);
		if (removed != null)
		{
			com.wenzai.neosim.npc.CityLivingManager.evictResidents(level, removed);
		}
		LOGGER.info("NeoSim-BreakHandler: control box broken at {}", pos);
	}
}
