package com.wenzai.neosim.schematic;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 双向映射
public class BlockStatePalette
{
    private static final int LINEAR_THRESHOLD = 16;

    private BlockState[] linearPalette;
    private int linearCount;

    @Nullable
    private Map<BlockState, Integer> idMap;
    @Nullable
    private List<BlockState> idToList;

    private int bits;

    // 创建空调色板，AIR位于索引0
    public BlockStatePalette()
    {
        this.linearPalette = new BlockState[LINEAR_THRESHOLD];
        BlockState air = Blocks.AIR.defaultBlockState();
        this.linearPalette[0] = air;
        this.linearCount = 1;
        this.bits = 1;
    }
    
    // 返回调色板ID，不存在则插入
    public int idFor(BlockState state)
    {
        if (isHashMapMode())
        {
            return idForHashMap(state);
        }
        return idForLinear(state);
    }

    // 查找ID，不存在返回-1
    public int idOf(BlockState state)
    {
        if (isHashMapMode())
        {
            return idMap.getOrDefault(state, -1);
        }
        for (int i = 0; i < linearCount; i++)
        {
            if (linearPalette[i].equals(state))
            {
                return i;
            }
        }
        return -1;
    }

    // 返回ID对应的BlockState，越界返回AIR
    public BlockState getBlockState(int id)
    {
        if (id < 0)
        {
            return Blocks.AIR.defaultBlockState();
        }
        if (isHashMapMode())
        {
            if (id < idToList.size())
            {
                return idToList.get(id);
            }
            return Blocks.AIR.defaultBlockState();
        }
        if (id < linearCount)
        {
            return linearPalette[id];
        }
        return Blocks.AIR.defaultBlockState();
    }

    // 调色板当前条目数
    public int size()
    {
        return isHashMapMode() ? idToList.size() : linearCount;
    }
    
    public int getBits()
    {
        return bits;
    }

    // 序列化为Litematica兼容的{@code ListTag<CompoundTag>}
    public ListTag writeToNBT()
    {
        ListTag list = new ListTag();
        int count = size();
        for (int i = 0; i < count; i++)
        {
            list.add(NbtUtils.writeBlockState(getBlockState(i)));
        }
        return list;
    }

    private boolean isHashMapMode()
    {
        return idMap != null;
    }

    private int idForLinear(BlockState state)
    {
        for (int i = 0; i < linearCount; i++)
        {
            if (linearPalette[i].equals(state))
            {
                return i;
            }
        }

        if (linearCount < LINEAR_THRESHOLD)
        {
            linearPalette[linearCount] = state;
            int newId = linearCount;
            linearCount++;
            this.bits = computeBits();
            return newId;
        }

        migrateToHashMap(state);
        return idMap.get(state);
    }

    private int idForHashMap(BlockState state)
    {
        Integer existing = idMap.get(state);
        if (existing != null)
        {
            return existing;
        }
        int newId = idToList.size();
        idMap.put(state, newId);
        idToList.add(state);
        this.bits = computeBits();
        return newId;
    }

    // 一次性迁移，保留已有ID
    private void migrateToHashMap(BlockState triggeringState)
    {
        idMap = new HashMap<>();
        idToList = new ArrayList<>();

        for (int i = 0; i < linearCount; i++)
        {
            idMap.put(linearPalette[i], i);
            idToList.add(linearPalette[i]);
        }

        int newId = idToList.size();
        idMap.put(triggeringState, newId);
        idToList.add(triggeringState);

        linearPalette = null;
        linearCount = 0;

        this.bits = computeBits();
    }

    private int computeBits()
    {
        int paletteSize = size();
        if (paletteSize <= 1)  return 1;
        if (paletteSize <= 2)  return 1;
        if (paletteSize <= 4)  return 2;
        if (paletteSize <= 8)  return 3;
        if (paletteSize <= 16) return 4;
        if (paletteSize <= 32) return 5;
        if (paletteSize <= 64) return 6;
        if (paletteSize <= 128) return 7;
        if (paletteSize <= 256) return 8;
        if (paletteSize <= 512) return 9;
        if (paletteSize <= 1024) return 10;
        if (paletteSize <= 2048) return 11;
        if (paletteSize <= 4096) return 12;
        if (paletteSize <= 8192) return 13;
        if (paletteSize <= 16384) return 14;
        if (paletteSize <= 32768) return 15;
        if (paletteSize <= 65536) return 16;
        if (paletteSize <= 131072) return 17;
        if (paletteSize <= 262144) return 18;
        if (paletteSize <= 524288) return 19;
        if (paletteSize <= 1048576) return 20;
        if (paletteSize <= 2097152) return 21;
        if (paletteSize <= 4194304) return 22;
        if (paletteSize <= 8388608) return 23;
        if (paletteSize <= 16777216) return 24;
        return 32;
    }

    @Override
    public String toString()
    {
        return "BlockStatePalette{mode=" + (isHashMapMode() ? "HashMap" : "Linear")
                + ", size=" + size() + ", bits=" + bits + "}";
    }
}
