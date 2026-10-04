package com.wenzai.neosim.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InventoryManager
{
	private InventoryManager()
	{
	}

	// 搜索与模盒紧邻的6个面；大箱子（合并双人箱）把另一半一并纳入
	public static List<ChestBlockEntity> findNearbyChests(ServerLevel level, BlockPos center)
	{
		Set<BlockPos> seen = new HashSet<>();
		List<ChestBlockEntity> chests = new ArrayList<>();
		BlockPos[] neighbors = {
				center.above(), center.below(),
				center.north(), center.south(), center.east(), center.west()
		};
		for (BlockPos pos : neighbors)
		{
			if (!seen.add(pos)) continue;
			BlockEntity be = level.getBlockEntity(pos);
			if (!isReadableChest(be)) continue;
			chests.add((ChestBlockEntity) be);
			addDoubleChestPartner(level, pos, chests, seen);
		}
		return chests;
	}

	// 箱链扫描上限：防止整城箱子连成一堵墙时扫描爆炸（64 箱 × 27 格 = 1728 格容量）
	public static final int MAX_CHAIN_CHESTS = 64;

	// 相连箱链：从中心 6 邻面出发，沿「箱子—箱子相邻」关系向外扩散（模盒 → 箱 → 箱 → …）
	// 整条链上的箱子都可读写；传播只经过可读箱子——陷阱箱既不纳入，也不作为链的中继
	// 顺序即 BFS 层序：直接相邻的箱子优先，链式远端的箱子靠后
	public static List<ChestBlockEntity> findChainedChests(ServerLevel level, BlockPos center)
	{
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		List<ChestBlockEntity> chests = new ArrayList<>();

		for (Direction d : Direction.values())
		{
			BlockPos pos = center.relative(d);
			if (seen.add(pos)) queue.add(pos);
		}

		while (!queue.isEmpty() && chests.size() < MAX_CHAIN_CHESTS)
		{
			BlockPos pos = queue.poll();

			// 只读已加载区块：链路跨到未加载区块时停在边界，不因扫箱子触发区块加载
			if (!level.isLoaded(pos)) continue;

			BlockEntity be = level.getBlockEntity(pos);
			if (!isReadableChest(be)) continue;

			chests.add((ChestBlockEntity) be);

			// 只从已纳入的箱子继续扩散（双人箱的另一半是相邻方块，自然会被纳入）
			for (Direction d : Direction.values())
			{
				BlockPos next = pos.relative(d);
				if (seen.add(next)) queue.add(next);
			}
		}
		return chests;
	}

	// 只读普通箱子：陷阱箱不读
	private static boolean isReadableChest(BlockEntity be)
	{
		return be instanceof ChestBlockEntity && !(be instanceof TrappedChestBlockEntity);
	}

	// 大箱子：仅并入「合并的双人箱」另一半（独立单箱/陷阱箱不并入）
	private static void addDoubleChestPartner(ServerLevel level, BlockPos pos,
			List<ChestBlockEntity> chests, Set<BlockPos> seen)
	{
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)
				|| state.getBlock() instanceof TrappedChestBlock) return;
		ChestType type = state.hasProperty(ChestBlock.TYPE)
				? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
		if (type == ChestType.SINGLE) return;
		BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
		if (!seen.add(partner)) return;
		BlockEntity pb = level.getBlockEntity(partner);
		if (isReadableChest(pb))
		{
			chests.add((ChestBlockEntity) pb);
		}
	}

	// 统计物品总数量
	public static int countItems(List<ChestBlockEntity> chests, Item item)
	{
		int total = 0;
		for (ChestBlockEntity chest : chests)
		{
			for (int i = 0; i < chest.getContainerSize(); i++)
			{
				ItemStack stack = chest.getItem(i);
				if (stack.is(item))
				{
					total += stack.getCount();
				}
			}
		}
		return total;
	}

	// 从箱子取出指定数量物品，返回实际取出的数量
	public static int extractItem(List<ChestBlockEntity> chests, Item item, int count)
	{
		int remaining = count;
		for (ChestBlockEntity chest : chests)
		{
			for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++)
			{
				ItemStack stack = chest.getItem(i);
				if (stack.is(item))
				{
					int take = Math.min(stack.getCount(), remaining);
					stack.shrink(take);
					remaining -= take;
					chest.setChanged();
				}
			}
		}
		return count - remaining;
	}

	// 将物品存入箱子：优先堆叠到已有同种堆叠上（堆满），仍有剩余才放入空格子
	public static void depositItems(List<ChestBlockEntity> chests, ItemStack stack)
	{
		ItemStack remainder = stack.copy();
		if (remainder.isEmpty() || chests.isEmpty()) return;

		// 第一遍：优先堆叠到已有同种物品的堆叠上（所有箱子都堆满为止）
		for (ChestBlockEntity chest : chests)
		{
			boolean changed = false;
			for (int i = 0; i < chest.getContainerSize() && !remainder.isEmpty(); i++)
			{
				ItemStack existing = chest.getItem(i);
				if (existing.isEmpty()) continue;
				if (!ItemStack.isSameItemSameComponents(existing, remainder)) continue;
				int space = existing.getMaxStackSize() - existing.getCount();
				if (space <= 0) continue;
				int move = Math.min(space, remainder.getCount());
				existing.grow(move);
				remainder.shrink(move);
				changed = true;
			}
			if (changed) chest.setChanged();
			if (remainder.isEmpty()) break;
		}

		// 第二遍：堆叠机会用尽后，才放入空格子
		if (!remainder.isEmpty())
		{
			for (ChestBlockEntity chest : chests)
			{
				boolean changed = false;
				for (int i = 0; i < chest.getContainerSize() && !remainder.isEmpty(); i++)
				{
					ItemStack existing = chest.getItem(i);
					if (!existing.isEmpty()) continue;
					chest.setItem(i, remainder.copy());
					remainder.setCount(0);
					changed = true;
				}
				if (changed) chest.setChanged();
				if (remainder.isEmpty()) break;
			}
		}

		// 放不下的掉落
		if (!remainder.isEmpty())
		{
			Containers.dropItemStack(
					chests.get(0).getLevel(),
					chests.get(0).getBlockPos().getX(),
					chests.get(0).getBlockPos().getY(),
					chests.get(0).getBlockPos().getZ(),
					remainder);
		}
	}

	// 挖掘方块并让掉落物自然生成
	public static void mineBlockIntoChests(ServerLevel level, BlockPos pos, List<ChestBlockEntity> chests)
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

	// 该物品在箱子里还能放下多少件（同种堆叠余量 + 空格），用于"存入不落地"的场景
	public static int spaceFor(List<ChestBlockEntity> chests, ItemStack stack)
	{
		if (stack.isEmpty()) return 0;
		int max = stack.getMaxStackSize();
		int space = 0;
		for (ChestBlockEntity chest : chests)
		{
			for (int i = 0; i < chest.getContainerSize(); i++)
			{
				ItemStack existing = chest.getItem(i);
				if (existing.isEmpty()) space += max;
				else if (ItemStack.isSameItemSameComponents(existing, stack))
				{
					space += Math.max(0, existing.getMaxStackSize() - existing.getCount());
				}
			}
		}
		return space;
	}

	// 是否有空间存入该物品
	public static boolean canDeposit(List<ChestBlockEntity> chests, ItemStack stack)
	{
		if (stack.isEmpty()) return true;
		for (ChestBlockEntity chest : chests)
		{
			for (int i = 0; i < chest.getContainerSize(); i++)
			{
				ItemStack existing = chest.getItem(i);
				if (existing.isEmpty()) return true;
				if (ItemStack.isSameItemSameComponents(existing, stack)
						&& existing.getCount() < existing.getMaxStackSize())
				{
					return true;
				}
			}
		}
		return false;
	}
}
