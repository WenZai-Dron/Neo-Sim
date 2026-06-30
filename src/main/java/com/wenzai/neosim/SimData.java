// 共享数据模型，服务端ModSavedData和客户端ClientDataHolder共用此结构

package com.wenzai.neosim;

import com.google.gson.JsonObject;

public record SimData(byte mode, short population, int dayOfWeek, int day, double credit)
{

    public static final SimData DEFAULT = new SimData((byte)0, (short)0, 0, 1, 10.0);

    // 文件序列化
    public static SimData fromJson(JsonObject json)
    {
        return new SimData(
                json.get("mode").getAsByte(),
                json.get("population").getAsShort(),
                json.get("dayOfWeek").getAsInt(),
                json.get("day").getAsInt(),
                json.get("credit").getAsDouble()
        );
    }

    public JsonObject toJson(JsonObject json)
    {
        json.addProperty("mode", mode);
        json.addProperty("population", population);
        json.addProperty("dayOfWeek", dayOfWeek);
        json.addProperty("day", day);
        json.addProperty("credit", credit);
        return json;
    }

    // 返回新实例
    public SimData withMode(byte mode) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withPopulation(short population) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withDayOfWeek(int dayOfWeek) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withDay(int day) { return new SimData(mode, population, dayOfWeek, day, credit); }
    public SimData withCredit(double credit) { return new SimData(mode, population, dayOfWeek, day, credit); }
}
