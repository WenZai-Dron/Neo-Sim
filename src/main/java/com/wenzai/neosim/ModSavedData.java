package com.wenzai.neosim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

public class ModSavedData extends SavedData
{
    public static final String ID = NeoSim.MOD_ID + "_data";

    private SimData data = SimData.DEFAULT;

    // NBT持久化
    private static ModSavedData load(CompoundTag tag, HolderLookup.Provider provider)
    {
        ModSavedData sd = new ModSavedData();
        sd.data = SimData.fromNBT(tag);
        return sd;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider)
    {
        data.toNBT(tag);
        NeoSim.LOGGER.info("NeoSim-SavedData: {}", data);
        return tag;
    }

    public static final SavedData.Factory<ModSavedData> FACTORY =
            new Factory<>(ModSavedData::new, ModSavedData::load);

    // 网络同步
    private void syncToClients(ServerLevel level)
    {
        SyncDataPayload payload = new SyncDataPayload(data);
        level.players().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    public ModSavedData()
    {
        setDirty();
    }

    // 获取内部数据，用于构造网络包
    public SimData getData() { return data; }

    // 全量替换数据并同步一次
    public void setData(SimData newData, ServerLevel level)
    {
        this.data = newData;
        setDirty();
        syncToClients(level);
    }

    // 单独Getter/Setter（每次调用会触发同步）
    public int getMode() { return data.mode(); }
    public void setMode(int mode, ServerLevel level)
    {
        this.data = data.withMode(mode);
        setDirty();
        syncToClients(level);
    }

    public int getSingleOrMulti() { return data.singleOrMulti(); }
    public void setSingleOrMulti(int singleOrMulti, ServerLevel level)
    {
        this.data = data.withSingleOrMulti(singleOrMulti);
        setDirty();
        syncToClients(level);
    }

    public int getPopulation() { return data.population(); }
    public void setPopulation(int population, ServerLevel level)
    {
        this.data = data.withPopulation(population);
        setDirty();
        syncToClients(level);
    }

    public int getDayOfWeek() { return data.dayOfWeek(); }
    public void setDayOfWeek(int dayOfWeek, ServerLevel level)
    {
        this.data = data.withDayOfWeek(dayOfWeek);
        setDirty();
        syncToClients(level);
    }

    public int getDay() { return data.day(); }
    public void setDay(int day, ServerLevel level)
    {
        this.data = data.withDay(day);
        setDirty();
        syncToClients(level);
    }

    public double getCredit() { return data.credit(); }
    public void setCredit(double credit, ServerLevel level)
    {
        this.data = data.withCredit(credit);
        setDirty();
        syncToClients(level);
    }

    // 获取实例
    public static ModSavedData get(ServerLevel level)
    {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }
}
