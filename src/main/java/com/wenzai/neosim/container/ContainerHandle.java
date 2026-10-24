package com.wenzai.neosim.container;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.function.Consumer;
import java.util.function.Predicate;

// 单个容器方块：对外只有意图级动作（统计 / 取出 / 存入 / 试放），槽位号不出现在 API 上
// 这样调用方手里永远不会出现「第 3 号槽」，也就无从原地改 ItemStack（IItemHandler 给的是副本）
// 扫描方法都收 limit = 本次最多看多少槽，预算由调用方逐段扣减
public final class ContainerHandle
{
	private final BlockPos pos;
	private final IItemHandler handler;
	private final int size;

	ContainerHandle(BlockPos pos, IItemHandler handler)
	{
		this.pos = pos;
		this.handler = handler;
		this.size = safeSize(handler);
	}

	public BlockPos pos()
	{
		return pos;
	}

	public int size()
	{
		return size;
	}

	// 统计某物品：只读原栈、不 copy；数量是「取得出」的量，不是「读得到」的量
	public int count(Item item, int limit)
	{
		return countMatching(stack -> stack.is(item), limit);
	}

	// 统计任意条件：树苗判定、站点汇总都用它；只算真正取得出的数量
	public int countMatching(Predicate<ItemStack> filter, int limit)
	{
		int total = 0;
		int end = bound(limit);
		for (int slot = 0; slot < end; slot++)
		{
			ItemStack stack = peek(slot);
			if (stack == null || stack.isEmpty() || !filter.test(stack)) continue;
			total += movable(slot, stack.getCount());
		}
		return total;
	}

	// 遍历「取得到的东西」：对外只给副本，数量按可取出量算
	public void forEach(Consumer<ItemStack> visitor, int limit)
	{
		int end = bound(limit);
		for (int slot = 0; slot < end; slot++)
		{
			ItemStack stack = peek(slot);
			if (stack == null || stack.isEmpty()) continue;
			int movable = movable(slot, stack.getCount());
			if (movable <= 0) continue;
			ItemStack copy = stack.copy();
			copy.setCount(movable);
			visitor.accept(copy);
		}
	}

	// 取出指定物品，返回实取数
	public int extract(Item item, int count, int limit)
	{
		int remaining = count;
		int end = bound(limit);
		for (int slot = 0; slot < end && remaining > 0; slot++)
		{
			ItemStack stack = peek(slot);
			if (stack == null || !stack.is(item)) continue;
			try
			{
				ItemStack out = handler.extractItem(slot, remaining, false);
				if (out != null) remaining -= out.getCount();
			}
			catch (RuntimeException e)
			{
				// 单个槽失败不影响其余槽
			}
		}
		return count - remaining;
	}

	// 取出第一个命中条件的，返回其副本；取不到返回 EMPTY
	public ItemStack extractFirst(Predicate<ItemStack> filter, int limit)
	{
		int end = bound(limit);
		for (int slot = 0; slot < end; slot++)
		{
			ItemStack stack = peek(slot);
			if (stack == null || stack.isEmpty() || !filter.test(stack)) continue;
			try
			{
				ItemStack out = handler.extractItem(slot, 1, false);
				if (out != null && !out.isEmpty()) return out.copy();
			}
			catch (RuntimeException e)
			{
				// 单个槽失败不影响其余槽
			}
		}
		return ItemStack.EMPTY;
	}

	// 存入：先填已有同种堆叠（非空槽），再放空槽；返回没塞进去的余量
	public ItemStack insert(ItemStack stack, int limit)
	{
		ItemStack remainder = stack.copy();
		int end = bound(limit);
		for (int slot = 0; slot < end && !remainder.isEmpty(); slot++)
		{
			ItemStack existing = peek(slot);
			if (existing != null && !existing.isEmpty()) remainder = put(slot, remainder);
		}
		for (int slot = 0; slot < end && !remainder.isEmpty(); slot++)
		{
			ItemStack existing = peek(slot);
			if (existing != null && existing.isEmpty()) remainder = put(slot, remainder);
		}
		return remainder;
	}

	// 还能放下多少件：模拟插入，不改容器
	public int roomFor(ItemStack stack, int limit)
	{
		if (stack.isEmpty()) return 0;
		long room = 0L;
		int end = bound(limit);
		for (int slot = 0; slot < end; slot++)
		{
			ItemStack rest = simulate(slot, stack);
			if (rest != null) room += stack.getCount() - rest.getCount();
		}
		return (int) Math.min(room, Integer.MAX_VALUE);
	}

	// 有没有地方放：模拟插入为主，乐观回退限定在「名义允许 + 当前为空」的槽
	// 乐观的理由：乐观错了最坏是物品掉在盒子旁（能捡回来），悲观错了是盒子永久停在等箱子
	public boolean accepts(ItemStack stack, int limit)
	{
		if (stack.isEmpty()) return true;
		int end = bound(limit);
		boolean emptyValidSlot = false;
		for (int slot = 0; slot < end; slot++)
		{
			ItemStack rest = simulate(slot, stack);
			if (rest != null && rest.getCount() < stack.getCount()) return true;
			ItemStack existing = peek(slot);
			if (existing != null && existing.isEmpty() && isItemValid(slot, stack)) emptyValidSlot = true;
		}
		return emptyValidSlot;
	}

	// 这个槽是否根本不收这种物品（不看满不满）：机器的输出槽在这里被挡掉
	private boolean isItemValid(int slot, ItemStack stack)
	{
		try
		{
			return handler.isItemValid(slot, stack);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	// 该槽此刻能真正取出多少：模拟取出，取不到给 0
	// 「读得到」不等于「取得出」：只读容器、机器的输出槽、带锁的容器都在这里被挡掉
	private int movable(int slot, int wanted)
	{
		try
		{
			ItemStack out = handler.extractItem(slot, wanted, true);
			return out == null ? 0 : Math.min(wanted, out.getCount());
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	// 类内取原栈：只读、不 copy、不外传；失败给 null（与「空槽」严格区分）
	private ItemStack peek(int slot)
	{
		if (slot < 0 || slot >= size) return null;
		try
		{
			return handler.getStackInSlot(slot);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// 真插入一槽，返回余量
	private ItemStack put(int slot, ItemStack stack)
	{
		try
		{
			ItemStack rest = handler.insertItem(slot, stack, false);
			return rest == null ? ItemStack.EMPTY : rest;
		}
		catch (RuntimeException e)
		{
			return stack;
		}
	}

	// 模拟插入一槽：失败给 null，与「放不下」区分开
	private ItemStack simulate(int slot, ItemStack stack)
	{
		try
		{
			return handler.insertItem(slot, stack, true);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// 实际可看的槽位：min(size, limit)
	private int bound(int limit)
	{
		return Math.max(0, Math.min(size, limit));
	}

	private static int safeSize(IItemHandler handler)
	{
		try
		{
			return Math.max(0, handler.getSlots());
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}
}
