package com.wenzai.neosim.compat.sable;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

// 物理适配器注册表：按模组 id 注册/加载适配器，支撑多物理模组共存。
// 适配器类在自己的静态块里调用 register() 注册自身；本类 init() 仅反射加载
// "已安装模组"的适配器类，未安装模组的适配器类永远不会被加载，保持零硬依赖。
public final class PhysicsAdapterRegistry
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 注册表：保持注册顺序（先注册优先）
	private static final List<IPhysicsAdapter> ADAPTERS = new ArrayList<>();

	private static boolean initTried = false;

	private PhysicsAdapterRegistry()
	{
	}

	// 初始化（幂等）：按模组加载状态反射加载各适配器类，由 NeoSim 构造时调用。
	public static synchronized void init()
	{
		if (initTried) return;
		initTried = true;
		tryLoad("sable", "com.wenzai.neosim.compat.sable.SablePhysicsAdapter");
		// 未来模组在此追加（模组 id + 适配器全限定名）：
		// tryLoad("simulated", "com.wenzai.neosim.compat.simulated.SimulatedPhysicsAdapter");
	}

	// 按模组 id 反射加载适配器类（类静态块负责注册自身）
	private static void tryLoad(String modId, String className)
	{
		if (!ModList.get().isLoaded(modId)) return;
		try
		{
			Class.forName(className);
		}
		catch (Throwable t)
		{
			LOGGER.warn("NeoSim-PhysicsAdapterRegistry: failed to load adapter {} for mod {}",
					className, modId, t);
		}
	}

	// 适配器类在静态块中调用：注册自身（同模组重复注册时保留先注册者）
	public static synchronized void register(IPhysicsAdapter adapter)
	{
		if (ADAPTERS.stream().anyMatch(a -> a.modId().equals(adapter.modId()))) return;
		ADAPTERS.add(adapter);
		LOGGER.debug("NeoSim-PhysicsAdapterRegistry: registered adapter for mod {}", adapter.modId());
	}

	// 当前可用的适配器（对应模组已加载；单个适配器异常视为不可用）
	public static List<IPhysicsAdapter> activeAdapters()
	{
		return ADAPTERS.stream().filter(PhysicsAdapterRegistry::isAvailable).toList();
	}

	// 适配器可用性判定（异常隔离）
	private static boolean isAvailable(IPhysicsAdapter adapter)
	{
		try
		{
			return adapter.isAvailable();
		}
		catch (Throwable t)
		{
			LOGGER.warn("NeoSim-PhysicsAdapterRegistry: adapter {} isAvailable failed, treated unavailable",
					adapter.modId(), t);
			return false;
		}
	}

	// 支持方块读写的适配器（第一个可用者；各适配器经 isBlockIoSupported 自报，可能为 null）
	public static IPhysicsAdapter blockIoAdapter()
	{
		for (IPhysicsAdapter a : activeAdapters())
		{
			if (a.isBlockIoSupported()) return a;
		}
		return null;
	}
}
