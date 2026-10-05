package com.wenzai.neosim.block;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.InventoryManager;
import com.wenzai.neosim.util.ChunkWindows;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 强制加载：每个盒种一个记账表 + 一个 ticket 类型，窗口由各任务算好后交给这里做差量增删
// - 距离 0 的 region ticket = FULL 等级：区块可读写方块，不跑实体刻/方块刻，代价最小
// - 按盒子/任务记账：两个盒共享区块时，一个释放不会误删另一个的 ticket
// - 底层窗口运算在 util/ChunkWindows，这里只负责"哪种盒用哪个 ticket、记在哪张表"
public final class ChunkLoaders
{
	private ChunkLoaders()
	{
	}

	// 工作盒：只保留「作业游标 3×3 + 盒子 + 材料箱」窗口，随游标滚动
	public static final class Plot
	{
		private static final TicketType<ChunkPos> TICKET =
				TicketType.create("neo_sim:workplot", Comparator.comparingLong(ChunkPos::toLong));

		private static final Map<BlockPos, Set<Long>> tickets = new HashMap<>();

		private Plot()
		{
		}

		// 用任务算好的窗口替换该盒子的加载集合
		public static void setWindow(ServerLevel level, BlockPos box, Set<Long> desired)
		{
			ChunkWindows.apply(level, TICKET, tickets, box, desired);
		}

		// 释放该盒子的全部区块（解雇/拆除/夜间/等待）
		public static void releaseForPlot(ServerLevel level, BlockPos box)
		{
			ChunkWindows.release(level, TICKET, tickets, box);
		}

		// 服务器停止/世界卸载时清空
		public static void clear()
		{
			ChunkWindows.clearAll(tickets);
		}
	}

	// 整地：等待/扫描阶段要整块地块，作业阶段收窄成「游标 3×3 + 盒子 + 材料箱」
	public static final class Terraform
	{
		private static final TicketType<ChunkPos> TICKET =
				TicketType.create("neo_sim:terraform", Comparator.comparingLong(ChunkPos::toLong));

		private static final Map<BlockPos, Set<Long>> tickets = new HashMap<>();

		private Terraform()
		{
		}

		// 用任务算好的窗口替换该任务的加载集合
		public static void setWindow(ServerLevel level, BlockPos box, Set<Long> desired)
		{
			ChunkWindows.apply(level, TICKET, tickets, box, desired);
		}

		// 释放该任务加载的区块
		public static void releaseForPlot(ServerLevel level, BlockPos box)
		{
			ChunkWindows.release(level, TICKET, tickets, box);
		}

		// 服务器停止/世界卸载时清空
		public static void clear()
		{
			ChunkWindows.clearAll(tickets);
		}
	}

	// 重建盒：只保留「当前扫描层 ±1 的整栋覆盖 + 重建盒 ±1（含相邻材料箱）+ 控制箱」，随换层滚动
	public static final class Rebuild
	{
		private static final Logger LOGGER = LogUtils.getLogger();

		private static final TicketType<ChunkPos> TICKET =
				TicketType.create("neo_sim:rebuild", Comparator.comparingLong(ChunkPos::toLong));

		private static final Map<BlockPos, Set<Long>> tickets = new HashMap<>();

		private Rebuild()
		{
		}

		// 用任务算好的窗口替换该盒子的加载集合（含单盒上限兜底）
		public static void setWindow(ServerLevel level, BlockPos box, Set<Long> desired)
		{
			ChunkWindows.apply(level, TICKET, tickets, box, enforceCap(box, desired));
		}

		// 释放该盒子的全部区块
		public static void release(ServerLevel level, BlockPos box)
		{
			ChunkWindows.release(level, TICKET, tickets, box);
		}

		// 服务器停止/世界卸载时清空
		public static void clear()
		{
			ChunkWindows.clearAll(tickets);
		}

		// 单盒上限兜底：超限时按到盒子的距离保留最近的若干区块
		private static Set<Long> enforceCap(BlockPos box, Set<Long> desired)
		{
			int cap = Math.max(1, Config.REBUILD_MAX_CHUNKS.get());
			if (desired.size() <= cap) return desired;

			int bcx = box.getX() >> 4;
			int bcz = box.getZ() >> 4;
			List<Long> all = new ArrayList<>(desired);
			all.sort(Comparator.comparingLong(l ->
			{
				ChunkPos cp = new ChunkPos(l);
				long dx = cp.x - bcx;
				long dz = cp.z - bcz;
				return dx * dx + dz * dz;
			}));
			LOGGER.warn("NeoSim-ChunkLoaders.rebuild: window {} chunks at {} exceeds cap {}, keeping nearest {}",
					desired.size(), box, cap, cap);
			return new HashSet<>(all.subList(0, cap));
		}
	}

	// 快递盒：站点区只增不减（箱链一旦发现就自我维持），另有跟随快递员的滚动窗口
	public static final class Delivery
	{
		private static final TicketType<ChunkPos> TICKET =
				TicketType.create("neo_sim:delivery", Comparator.comparingLong(ChunkPos::toLong));

		// 按快递盒记账：站点区区块 与 滚动窗口区块（防跨盒误释放）
		private static final Map<BlockPos, Set<Long>> boxTickets = new HashMap<>();
		private static final Map<BlockPos, Set<Long>> windowTickets = new HashMap<>();

		private Delivery()
		{
		}

		// 站点区：快递盒所在区块 ±1 + 整条箱链所在区块（链可能横向延伸很远）
		// - 箱链扫描只读已加载区块，不触发同步加载；已纳入的区块会保持加载
		// - 只增不减：某格一时不可见（未加载/临时拆除）不会把窗口丢掉；解雇/拆盒由 releaseAll 统一清空
		public static void registerBox(ServerLevel level, BlockPos box)
		{
			Set<Long> set = boxTickets.computeIfAbsent(box, b -> new HashSet<>());
			Set<Long> desired = new HashSet<>();
			ChunkWindows.addAround(desired, box, 1);
			for (ChestBlockEntity chest : InventoryManager.findChainedChests(level, box))
			{
				desired.add(ChunkWindows.of(chest.getBlockPos()));
			}

			for (Long l : desired)
			{
				if (set.add(l))
				{
					ChunkPos cp = new ChunkPos(l);
					level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
				}
			}
		}

		// 滚动窗口：以快递员位置为中心、半径 deliveryChunkRadius 的区块列，跟随移动增删
		public static void setWindow(ServerLevel level, BlockPos box, BlockPos courierPos)
		{
			Set<Long> current = windowTickets.computeIfAbsent(box, b -> new HashSet<>());
			Set<Long> next = new HashSet<>();
			if (courierPos != null)
			{
				int cx = courierPos.getX() >> 4;
				int cz = courierPos.getZ() >> 4;
				int r = Config.DELIVERY_CHUNK_RADIUS.get();
				for (int dx = -r; dx <= r; dx++)
				{
					for (int dz = -r; dz <= r; dz++)
					{
						next.add(ChunkPos.asLong(cx + dx, cz + dz));
					}
				}
			}
			for (Long l : next)
			{
				if (current.add(l))
				{
					ChunkPos cp = new ChunkPos(l);
					level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
				}
			}
			Iterator<Long> it = current.iterator();
			while (it.hasNext())
			{
				Long l = it.next();
				if (!next.contains(l))
				{
					ChunkPos cp = new ChunkPos(l);
					level.getChunkSource().removeRegionTicket(TICKET, cp, 0, cp);
					it.remove();
				}
			}
		}

		// 释放某快递盒的全部区块（解雇/拆除/任务取消）
		public static void releaseAll(ServerLevel level, BlockPos box)
		{
			release(level, boxTickets.remove(box));
			release(level, windowTickets.remove(box));
		}

		// 服务器停止/世界卸载时清空
		public static void clear()
		{
			boxTickets.clear();
			windowTickets.clear();
		}

		private static void release(ServerLevel level, Set<Long> chunks)
		{
			if (chunks == null) return;
			for (Long l : chunks)
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().removeRegionTicket(TICKET, cp, 0, cp);
			}
		}
	}

	// 建造：按当前建造层 ±1 的窗口随进度滚动（整栋常驻改为小窗口），模盒所在区块始终保留
	public static final class Building
	{
		private static final TicketType<ChunkPos> TICKET =
				TicketType.create("neo_sim:building", Comparator.comparingLong(ChunkPos::toLong));

		private Building()
		{
		}

		// 首次注册：只注册当前层 ±1 窗口（而非整栋全部层）
		public static void registerForBuilding(BuildingInstance building, ServerLevel level)
		{
			if (building.getControlBoxPos() == null || building.getSchematic() == null) return;
			int sx = building.getSchematic().getSizeX();
			int sy = building.getSchematic().getSizeY();
			int sz = building.getSchematic().getSizeZ();
			if (sx <= 0 || sy <= 0 || sz <= 0) return;

			Set<Long> window = windowFor(building, level, 0);
			for (Long l : window)
			{
				ChunkPos cp = new ChunkPos(l);
				level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
				building.addLoadedChunk(cp);
			}
		}

		// 按建造进度把强加载窗口滚动到"当前层 ±1"；返回是否有变化
		public static boolean updateWindow(BuildingInstance building, ServerLevel level)
		{
			if (building.getControlBoxPos() == null || building.getSchematic() == null) return false;
			int sx = building.getSchematic().getSizeX();
			int sy = building.getSchematic().getSizeY();
			int sz = building.getSchematic().getSizeZ();
			if (sx <= 0 || sy <= 0 || sz <= 0) return false;

			int layer = building.getBuildProgress() / (sx * sz);
			Set<Long> wanted = windowFor(building, level, layer);

			// 增：窗口内缺的
			boolean changed = false;
			for (Long l : wanted)
			{
				if (!building.containsLoadedChunk(l))
				{
					ChunkPos cp = new ChunkPos(l);
					level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
					building.addLoadedChunk(cp);
					changed = true;
				}
			}

			// 减：已注册但不在新窗口内的
			Iterator<ChunkPos> it = building.getLoadedChunks().iterator();
			while (it.hasNext())
			{
				ChunkPos cp = it.next();
				if (!wanted.contains(cp.toLong()))
				{
					level.getChunkSource().removeRegionTicket(TICKET, cp, 0, cp);
					it.remove();
					changed = true;
				}
			}
			return changed;
		}

		// 释放该建筑加载的区块
		public static void releaseForBuilding(BuildingInstance building, ServerLevel level)
		{
			for (ChunkPos cp : building.getLoadedChunks())
			{
				level.getChunkSource().removeRegionTicket(TICKET, cp, 0, cp);
			}
			building.clearLoadedChunks();
		}

		// 当前层 ±1 覆盖的区块集合
		private static Set<Long> windowFor(BuildingInstance building, ServerLevel level, int layer)
		{
			Set<Long> out = new HashSet<>();
			int sx = building.getSchematic().getSizeX();
			int sy = building.getSchematic().getSizeY();
			int sz = building.getSchematic().getSizeZ();
			for (int dy = -1; dy <= 1; dy++)
			{
				int y = layer + dy;
				if (y < 0 || y >= sy) continue;
				BlockPos c0 = building.blueprintToWorld(0, y, 0);
				BlockPos c1 = building.blueprintToWorld(sx - 1, y, sz - 1);
				int minCX = Math.min(c0.getX(), c1.getX()) >> 4;
				int maxCX = Math.max(c0.getX(), c1.getX()) >> 4;
				int minCZ = Math.min(c0.getZ(), c1.getZ()) >> 4;
				int maxCZ = Math.max(c0.getZ(), c1.getZ()) >> 4;
				for (int cx = minCX; cx <= maxCX; cx++)
				{
					for (int cz = minCZ; cz <= maxCZ; cz++)
					{
						out.add(ChunkPos.asLong(cx, cz));
					}
				}
			}

			// 模盒所在区块始终保留（工人/交互点）
			BlockPos con = building.getConstructorPos();
			if (con != null)
			{
				out.add(ChunkPos.asLong(con.getX() >> 4, con.getZ() >> 4));
			}
			return out;
		}
	}
}
