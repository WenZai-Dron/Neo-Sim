/*  使用：
    ModSavedData data = ModSavedData.get(level);
    data.setCredit(data.getCredit() + 5.0);     credit += 5
    data.setMode(1);                            设置mode为1

*/

package com.wenzai.neosim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public class ModSavedData extends SavedData
{
    private int mode = 0;
    private int singleOrMulti = 0;
    private int population = 0;
    private int dayOfWeek = 0;
    private int day = 1;
    private double credit = 10.0;

    // 固定ID（世界存档文件名）
    public static final String ID = "NeoSim";

    // 读取数据，从存档加载
    public static ModSavedData load(CompoundTag tag, HolderLookup.Provider provider)
    {
        ModSavedData data = new ModSavedData();

        // 从NBT读取所有状态对应原版的文件读取
        data.mode = tag.getInt("mode");
        data.singleOrMulti = tag.getInt("singleOrMulti");
        data.population = tag.getInt("population");
        data.dayOfWeek = tag.getInt("dayOfWeek");
        data.day = tag.getInt("day");
        data.credit = tag.getDouble("credit");
        return data;
    }

    // 保存数据
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider)
    {
        tag.putInt("mode", mode);
        tag.putInt("singleOrMulti", singleOrMulti);
        tag.putInt("population", population);
        tag.putInt("dayOfWeek", dayOfWeek);
        tag.putInt("day",day);
        tag.putDouble("credit",credit);
        return tag;
    }

    public ModSavedData()
    {
        setDirty();

        // 日志
        NeoSim.LOGGER.info(
                "NeoSim: Saved | mode={}, singleOrMulti={}, population={}, dayOfWeek={}, day={}, credit={}",
                mode, singleOrMulti, population, dayOfWeek, day, credit
        );
    }

    public void setMode(int mode)
    {
        this.mode = mode;
        setDirty();
    }
    public int getMode() {return mode;}

    public void setSingleOrMulti(int singleOrMulti)
    {
        this.singleOrMulti = singleOrMulti;
        setDirty();
    }
    public int getSingleOrMulti() {return singleOrMulti;}

    public void setPopulation(int population)
    {
        this.population = population;
        setDirty();
    }
    public int getPopulation() {return population;}

    public void setDayOfWeek(int dayOfWeek)
    {
        this.dayOfWeek = dayOfWeek;
        setDirty();
    }
    public int getDayOfWeek() {return dayOfWeek;}

    public void setDay(int day)
    {
        this.day = day;
        setDirty();
    }
    public int getDay() {return day;}

    public void setCredit(double credit)
    {
        this.credit = credit;
        setDirty();
    }
    public double getCredit() {return credit;}

    public static ModSavedData get(ServerLevel level)
    {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        ModSavedData::new,      // 新建逻辑
                        ModSavedData::load,     // 加载逻辑
                        null
                ),
                ID                              // 存档ID
        );
}
}
