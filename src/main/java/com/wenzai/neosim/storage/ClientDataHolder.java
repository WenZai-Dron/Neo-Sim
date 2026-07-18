// 客户端缓存变量

package com.wenzai.neosim.storage;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientDataHolder
{
    private static final ClientDataHolder INSTANCE = new ClientDataHolder();

    private SimData data = SimData.DEFAULT;

    private ClientDataHolder() {}

    public static ClientDataHolder getInstance() { return INSTANCE; }

    // 由SyncDataPayload客户端处理逻辑调用
    public void updateData(SimData data) { this.data = data; }

    // Getter

    public byte getMode() { return data.mode(); }
    public short getPopulation() { return data.population(); }
    public int getDayOfWeek() { return data.dayOfWeek(); }
    public int getDay() { return data.day(); }
    public double getCredit() { return data.credit(); }
}
