// 箱子搜索与物品存取

package com.wenzai.neosim.building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.List;

public class InventoryManager
{
    // 搜索与模盒相邻的6个面
    public static List<ChestBlockEntity> findNearbyChests(ServerLevel level, BlockPos center)
    {
        List<ChestBlockEntity> chests = new ArrayList<>();
        BlockPos[] neighbors = {
                center.above(), center.below(),
                center.north(), center.south(), center.east(), center.west()
        };
        for (BlockPos pos : neighbors)
        {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity chest)
            {
                chests.add(chest);
            }
        }
        return chests;
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

    // 将物品存入箱子，优先合并已有堆叠
    public static void depositItems(List<ChestBlockEntity> chests, ItemStack stack)
    {
        ItemStack remainder = stack.copy();
        for (ChestBlockEntity chest : chests)
        {
            for (int i = 0; i < chest.getContainerSize() && !remainder.isEmpty(); i++)
            {
                ItemStack existing = chest.getItem(i);
                if (existing.isEmpty())
                {
                    chest.setItem(i, remainder.copy());
                    remainder.setCount(0);
                }
                else if (ItemStack.isSameItemSameComponents(existing, remainder))
                {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    int move = Math.min(space, remainder.getCount());
                    existing.grow(move);
                    remainder.shrink(move);
                }
            }
            chest.setChanged();
            if (remainder.isEmpty()) break;
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
}
