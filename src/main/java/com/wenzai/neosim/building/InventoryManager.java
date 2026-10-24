package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.container.ContainerFinder;
import com.wenzai.neosim.container.ContainerHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

// 库存原语：槽遍历在 ContainerHandle 里，跨容器循环与预算在这里
// 签名一律显式收 ServerLevel + List<ContainerHandle>：放不下的掉落需要 level，而句柄不持有 level
public class InventoryManager
{
	private InventoryManager()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();

	// 截断日志的最小间隔：巨型容器每 tick 被截断时不刷屏
	private static final long TRUNCATE_LOG_INTERVAL_MS = 2000L;

	private static long lastTruncateLogMs;

	// 搜索与模盒紧邻的6个面；大箱子（合并双人箱）把另一半一并纳入
	// 同一实例只进一次（多方块容器的多个面可能指向同一份库存）
	public static List<ContainerHandle> findNearbyContainers(ServerLevel level, BlockPos center)
	{
		Set<BlockPos> seen = new HashSet<>();
		Set<ContainerHandle> found = new LinkedHashSet<>();
		BlockPos[] neighbors = {
				center.above(), center.below(),
				center.north(), center.south(), center.east(), center.west()
		};
		for (BlockPos pos : neighbors)
		{
			if (!seen.add(pos)) continue;
			addContainer(level, pos, found);

			// 大箱子的另一半未必落在中心 6 邻面里；模组容器不猜，交给能力查询去合并
			BlockPos partner = doubleChestPartner(level, pos);
			if (partner != null && seen.add(partner))
			{
				addContainer(level, partner, found);
			}
		}
		return List.copyOf(found);
	}

	// 多个中心点的近邻容器合并：建筑控制箱 + 模盒这类两处盒子共用一套材料箱
	// 同一句柄只进一次（同一位置在本刻缓存里解析出同一个实例）；null 中心直接跳过
	public static List<ContainerHandle> findMergedNearbyContainers(ServerLevel level, BlockPos... centers)
	{
		Set<ContainerHandle> merged = new LinkedHashSet<>();
		for (BlockPos center : centers)
		{
			if (center == null) continue;
			merged.addAll(findNearbyContainers(level, center));
		}
		return List.copyOf(merged);
	}

	// 相连箱链：从中心 6 邻面出发，沿「容器—容器相邻」关系向外扩散（模盒 → 箱 → 箱 → …）
	// 只给快递盒用：站点库存可以沿一排箱子 / 抽屉延伸，其他盒子一律只读 6 邻面
	// 传播只经过可读容器——陷阱箱与黑名单方块既不纳入，也不作为链的中继
	public static List<ContainerHandle> findChainedContainers(ServerLevel level, BlockPos center)
	{
		int max = Math.max(1, Config.CONTAINER_MAX_CHAIN.get());
		Set<BlockPos> seen = new HashSet<>();
		Set<ContainerHandle> found = new LinkedHashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		int accepted = 0;

		for (Direction d : Direction.values())
		{
			BlockPos pos = center.relative(d);
			if (seen.add(pos)) queue.add(pos);
		}

		while (!queue.isEmpty() && accepted < max)
		{
			BlockPos pos = queue.poll();

			// 只读已加载区块：链路跨到未加载区块时停在边界，不因扫箱子触发区块加载
			if (!readable(level, pos)) continue;

			ContainerHandle handle = ContainerFinder.resolve(level, pos);
			if (handle == null) continue;

			// 上限按方块位置计：同一个多方块容器的各个面各占一个名额
			accepted++;
			found.add(handle);

			// 只从已纳入的容器继续扩散（双人箱的另一半是相邻方块，自然会被纳入）
			for (Direction d : Direction.values())
			{
				BlockPos next = pos.relative(d);
				if (seen.add(next)) queue.add(next);
			}
		}
		return List.copyOf(found);
	}

	// 收集一个位置上的容器；解不出来的跳过
	private static void addContainer(ServerLevel level, BlockPos pos, Set<ContainerHandle> found)
	{
		if (!readable(level, pos)) return;
		ContainerHandle handle = ContainerFinder.resolve(level, pos);
		if (handle != null) found.add(handle);
	}

	// 可读容器过滤：陷阱箱不读；allowForeignContainers=false 时只认原版箱子 / 木桶
	// 近邻与箱链共用这一个判定，所以命中的方块既不读，也不作为链的中继
	private static boolean readable(ServerLevel level, BlockPos pos)
	{
		if (!level.isLoaded(pos)) return false;
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null) return false;
		if (be instanceof TrappedChestBlockEntity) return false;
		if (Config.CONTAINER_ALLOW_FOREIGN.get()) return true;
		return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity;
	}

	// 大箱子：仅并入「合并的双人箱」另一半（独立单箱/陷阱箱不并入）；不是双人箱返回 null
	private static BlockPos doubleChestPartner(ServerLevel level, BlockPos pos)
	{
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)
				|| state.getBlock() instanceof TrappedChestBlock) return null;
		ChestType type = state.hasProperty(ChestBlock.TYPE)
				? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
		if (type == ChestType.SINGLE) return null;
		return pos.relative(ChestBlock.getConnectedDirection(state));
	}

	// 统计物品总数量（只算取得出的量）
	public static int countItems(List<ContainerHandle> containers, Item item)
	{
		return countMatching(containers, stack -> stack.is(item));
	}

	// 统计满足条件的物品总数量（只算取得出的量）：整地取树苗这类"任意品牌"判定用它
	public static int countMatching(List<ContainerHandle> containers, Predicate<ItemStack> filter)
	{
		int total = 0;
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			total += handle.countMatching(filter, limit);
			scan.spent(limit);
		}
		return total;
	}

	// 遍历取得出的物品（副本）：快递盒 GUI 的站点汇总用它
	public static void forEachItem(List<ContainerHandle> containers, Consumer<ItemStack> visitor)
	{
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			handle.forEach(visitor, limit);
			scan.spent(limit);
		}
	}

	// 取出指定数量物品，返回实际取出的数量（以容器返回值为准，不按预估扣）
	public static int extractItem(List<ContainerHandle> containers, Item item, int count)
	{
		int remaining = count;
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			if (remaining <= 0) break;
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			remaining -= handle.extract(item, remaining, limit);
			scan.spent(limit);
		}
		return count - remaining;
	}

	// 取出第一个满足条件的物品，返回其副本（取不到返回 EMPTY）
	public static ItemStack extractFirstMatching(List<ContainerHandle> containers, Predicate<ItemStack> filter)
	{
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			ItemStack out = handle.extractFirst(filter, limit);
			if (!out.isEmpty()) return out;
			scan.spent(limit);
		}
		return ItemStack.EMPTY;
	}

	// 将物品存入容器：优先堆叠到已有同种堆叠上（堆满），仍有剩余才放入空格
	// 逐个容器转交余量，最后放不下的按首个容器坐标掉落（与改动前口径一致）
	public static void depositItems(ServerLevel level, List<ContainerHandle> containers, ItemStack stack)
	{
		if (stack.isEmpty()) return;
		ItemStack remainder = stack.copy();
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			if (remainder.isEmpty()) return;
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			remainder = handle.insert(remainder, limit);
			scan.spent(limit);
		}
		if (remainder.isEmpty() || containers.isEmpty()) return;
		BlockPos drop = containers.get(0).pos();
		Containers.dropItemStack(level, drop.getX(), drop.getY(), drop.getZ(), remainder);
	}

	// 挖掘方块并让掉落物自然生成
	public static void mineBlockIntoChests(ServerLevel level, BlockPos pos, List<ContainerHandle> containers)
	{
		level.destroyBlock(pos, true);
	}

	// 手工计算方块掉落
	public static List<ItemStack> getBlockDrops(ServerLevel level, BlockPos pos, BlockState state)
	{
		if (level.getServer() == null) return List.of();
		ResourceKey<LootTable> key = state.getBlock().getLootTable();
		if (key == BuiltInLootTables.EMPTY) return List.of();
		try
		{
			LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
			LootParams params = new LootParams.Builder(level)
					.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
					.withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
					.withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(pos))
					.withOptionalParameter(LootContextParams.BLOCK_STATE, state)
					.create(LootContextParamSets.BLOCK);
			return table.getRandomItems(params);
		}
		catch (Exception e)
		{
			return List.of();
		}
	}

	// 该物品在容器里还能放下多少件（模拟插入求和，容量可超过 64 的抽屉也能算对）
	public static int spaceFor(List<ContainerHandle> containers, ItemStack stack)
	{
		if (stack.isEmpty()) return 0;
		long room = 0L;
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			room += handle.roomFor(stack, limit);
			scan.spent(limit);
		}
		return (int) Math.min(room, Integer.MAX_VALUE);
	}

	// 是否有空间存入该物品
	public static boolean canDeposit(List<ContainerHandle> containers, ItemStack stack)
	{
		if (stack.isEmpty()) return true;
		Scan scan = new Scan();
		for (ContainerHandle handle : containers)
		{
			int limit = scan.limit(handle);
			if (limit <= 0)
			{
				scan.truncated();
				break;
			}
			if (handle.accepts(stack, limit)) return true;
			scan.spent(limit);
		}
		return false;
	}

	// 一次扫描的两条预算：授权槽位数 + 时间上限。超了就停，不对外报错——
	// 盒子的语义是「这一 tick 干不干活」，不是「数据完整不完整」
	private static final class Scan
	{
		private int slots = Math.max(0, Config.CONTAINER_SCAN_SLOT_BUDGET.get());
		private final long deadline =
				System.nanoTime() + Math.max(0L, Config.CONTAINER_SCAN_TIME_BUDGET_MICROS.get() * 1000L);

		// 本次最多能看这个容器的多少槽；0 = 预算已耗尽，本次扫描到此为止
		private int limit(ContainerHandle handle)
		{
			if (slots <= 0 || System.nanoTime() > deadline) return 0;
			return Math.min(slots, handle.size());
		}

		// 按授权槽数扣，不是按实际槽数扣：句柄方法允许提前结束，那部分属于白赚
		private void spent(int limit)
		{
			slots -= limit;
		}

		// 预算耗尽：只留一行节流日志（巨型容器每 tick 被截断时不刷屏）
		private void truncated()
		{
			long now = System.currentTimeMillis();
			if (now - lastTruncateLogMs < TRUNCATE_LOG_INTERVAL_MS) return;
			lastTruncateLogMs = now;
			LOGGER.warn("NeoSim-InventoryManager: container scan truncated at {} slots",
					Config.CONTAINER_SCAN_SLOT_BUDGET.get());
		}
	}
}
