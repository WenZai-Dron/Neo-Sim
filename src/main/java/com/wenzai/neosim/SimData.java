// 共享数据模型，服务端ModSavedData和客户端ClientDataHolder共用此结构

package com.wenzai.neosim;

import net.minecraft.nbt.CompoundTag;

public record SimData(byte mode, short population, int dayOfWeek, int day, double credit)
{

    public static final SimData DEFAULT = new SimData((byte)0, (short)0, 0, 1, 10.0);

    // NBT序列化
    public static SimData fromNBT(CompoundTag tag)
    {
        return new SimData(
                tag.getByte("mode"),
                tag.getShort("population"),
                tag.getInt("dayOfWeek"),
                tag.getInt("day"),
                tag.getDouble("credit")
        );
    }

    public CompoundTag toNBT(CompoundTag tag)
    {
        tag.putByte("mode", mode);
        tag.putShort("population", population);
        tag.putInt("dayOfWeek", dayOfWeek);
        tag.putInt("day", day);
        tag.putDouble("credit", credit);
        return tag;
    }

    // 返回新实例
    public SimData withMode(byte mode) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withPopulation(short population) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withDayOfWeek(int dayOfWeek) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withDay(int day) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withCredit(double credit) { return new SimData(mode, population, dayOfWeek, day, credit); }
}
