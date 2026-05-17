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
import org.jetbrains.annotations.NotNull;

public class ModSavedData extends SavedData
{
    // 固定ID（世界存档文件名）
    public static final String ID = NeoSim.MOD_ID + "_data";

    private int mode = 0;
    private int singleOrMulti = 0;
    private int population = 0;
    private int dayOfWeek = 0;
    private int day = 1;
    private double credit = 10.0;

    // 读取数据
    private static ModSavedData load(CompoundTag tag, HolderLookup.Provider provider)
    {
        ModSavedData data = new ModSavedData();

        // 从NBT读取
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
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider)
    {
        tag.putInt("mode", mode);
        tag.putInt("singleOrMulti", singleOrMulti);
        tag.putInt("population", population);
        tag.putInt("dayOfWeek", dayOfWeek);
        tag.putInt("day", day);
        tag.putDouble("credit", credit);

        // 日志
        NeoSim.LOGGER.info(
                "NeoSim-ModSavedData: mode={}, singleOrMulti={}, population={}, dayOfWeek={}, day={}, credit={}",
                mode, singleOrMulti, population, dayOfWeek, day, credit
        );

        return tag;
    }

    public static final SavedData.Factory<ModSavedData> FACTORY =
            new Factory<>(ModSavedData::new, ModSavedData::load);

    public ModSavedData()
    {
        setDirty();
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

    // 工具方法：全局获取数据
    public static ModSavedData get(ServerLevel level)
    {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }
}
