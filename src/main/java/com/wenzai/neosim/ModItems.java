package com.wenzai.neosim;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems
{
	// 工具类：只有静态成员，禁止实例化
	private ModItems()
	{
	}

	public static final DeferredRegister.Items ITEMS =
		DeferredRegister.createItems(NeoSim.MOD_ID);

	public static void register(IEventBus eventBus)
	{
		ITEMS.register(eventBus);
	}
}
