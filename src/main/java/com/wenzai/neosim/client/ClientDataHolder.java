package com.wenzai.neosim.client;

import com.wenzai.neosim.storage.SimData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientDataHolder
{
	private static final ClientDataHolder INSTANCE = new ClientDataHolder();

	private SimData data = SimData.DEFAULT;
	private String cityName = "";

	private ClientDataHolder()
	{
	}

	public static ClientDataHolder getInstance()
	{
		return INSTANCE;
	}

	// 由客户端处理逻辑调用；空 cityName 表示该包不携带城市信息（全局同步），不覆盖已有城市
	public void updateData(SimData data, String cityName)
	{
		this.data = data;
		if (cityName != null && !cityName.isEmpty())
		{
			this.cityName = cityName;
		}
	}

	// 切换存档时重置
	public void reset()
	{
		this.data = SimData.DEFAULT;
		this.cityName = "";
	}

	// Getter
	public byte getMode()
	{
		return data.mode();
	}

	public short getPopulation()
	{
		return data.population();
	}

	public int getDayOfWeek()
	{
		return data.dayOfWeek();
	}

	public int getDay()
	{
		return data.day();
	}

	public double getCredit()
	{
		return data.credit();
	}

	public String getCityName()
	{
		return cityName;
	}
}
