package com.wenzai.neosim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

// 标记位置存档持久化
public class MarkerSavedData extends SavedData
{
    private static final String NAME = "neosim_markers";
    private final List<BlockPos> markers = new ArrayList<>();

    private static final SavedData.Factory<MarkerSavedData> FACTORY = new SavedData.Factory<>(
            MarkerSavedData::new,
            MarkerSavedData::load,
            DataFixTypes.LEVEL
    );

    public static MarkerSavedData get(ServerLevel level)
    {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public List<BlockPos> getMarkers()
    {
        return markers;
    }

    public void setMarkers(List<BlockPos> positions)
    {
        markers.clear();
        markers.addAll(positions);
        setDirty();
    }

    private static MarkerSavedData load(CompoundTag tag, HolderLookup.Provider registries)
    {
        MarkerSavedData data = new MarkerSavedData();
        ListTag list = tag.getList("markers", Tag.TAG_INT_ARRAY);
        for (Tag t : list)
        {
            int[] arr = ((IntArrayTag) t).getAsIntArray();
            if (arr.length == 3)
            {
                data.markers.add(new BlockPos(arr[0], arr[1], arr[2]));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries)
    {
        ListTag list = new ListTag();
        for (BlockPos p : markers)
        {
            list.add(new IntArrayTag(new int[] { p.getX(), p.getY(), p.getZ() }));
        }
        tag.put("markers", list);
        return tag;
    }
}
