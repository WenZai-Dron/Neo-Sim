// 共享数据模型，服务端ModSavedData和客户端ClientDataHolder共用此结构

package com.wenzai.neosim;

import net.minecraft.nbt.CompoundTag;

public record SimData(int mode, int singleOrMulti, int population, int dayOfWeek, int day, double credit)
{
    public static final SimData DEFAULT = new SimData(0, 0, 0, 0, 1, 10.0);

    // NBT序列化
    public static SimData fromNBT(CompoundTag tag)
    {
        return new SimData(
                tag.getInt("mode"),
                tag.getInt("singleOrMulti"),
                tag.getInt("population"),
                tag.getInt("dayOfWeek"),
                tag.getInt("day"),
                tag.getDouble("credit")
        );
    }

    public CompoundTag toNBT(CompoundTag tag)
    {
        tag.putInt("mode", mode);
        tag.putInt("singleOrMulti", singleOrMulti);
        tag.putInt("population", population);
        tag.putInt("dayOfWeek", dayOfWeek);
        tag.putInt("day", day);
        tag.putDouble("credit", credit);
        return tag;
    }

    // 返回新实例
    public SimData withMode(int mode) { return new SimData(mode, singleOrMulti, population, dayOfWeek, day, credit); }
    public SimData withSingleOrMulti(int som) { return new SimData(mode, som, population, dayOfWeek, day, credit); }
    public SimData withPopulation(int pop) { return new SimData(mode, singleOrMulti, pop, dayOfWeek, day, credit); }
    public SimData withDayOfWeek(int dow) { return new SimData(mode, singleOrMulti, population, dow, day, credit); }
    public SimData withDay(int d) { return new SimData(mode, singleOrMulti, population, dayOfWeek, d, credit); }
    public SimData withCredit(double credit) { return new SimData(mode, singleOrMulti, population, dayOfWeek, day, credit); }
}
