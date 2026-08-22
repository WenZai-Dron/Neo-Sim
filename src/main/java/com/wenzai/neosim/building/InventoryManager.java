package com.wenzai.neosim.building;

import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InventoryManager
{
	private InventoryManager()
	{
	}

	// 搜索与模盒相邻的6个面；大箱子（合并双人箱）把另一半一并纳入
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
