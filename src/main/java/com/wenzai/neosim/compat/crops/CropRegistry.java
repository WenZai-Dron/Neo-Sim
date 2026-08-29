package com.wenzai.neosim.compat.crops;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 模组作物注册表：扫描已装模组的注册表，自动发现可种植作物（任何模组）。
// 判定：种子为 BlockItem && 方块可骨粉催熟 && 方块状态含 age 生长属性；
// 排除原版命名空间（原版作物走 FarmTask 枚举）。
// 特殊作物（两阶段/需水）经内置覆盖表修正成熟目标与需水标记。
public final class CropRegistry
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 特殊作物覆盖配置：种下方块注册名 → 成熟目标注册名 + 是否需水 + 是否排除
	private record CropOverrides(ResourceLocation matureBlockId, boolean needsWater, boolean excluded) {}

	// 特殊作物覆盖表（record 内嵌类型放类顶部，此处按注册名索引）
	private static final Map<String, CropOverrides> OVERRIDES = buildOverrides();

	// 检测结果缓存（懒加载；数据包重载后调用 invalidate）
	private static List<CropEntry> cached = null;

	private static Map<String, CropOverrides> buildOverrides()
	{
		Map<String, CropOverrides> map = new HashMap<>();
		// 农夫乐事：番茄两阶段（幼苗 budding_tomatoes → 成株 tomatoes）
		map.put("farmersdelight:budding_tomatoes", new CropOverrides(
				ResourceLocation.fromNamespaceAndPath("farmersdelight", "tomatoes"), false, false));
		// 农夫乐事：水稻需水（农业盒无水源支持，种植时跳过）
		map.put("farmersdelight:rice", new CropOverrides(null, true, false));
		// 农夫乐事：蘑菇群非农田作物（种农田无法存活），检测时排除
		map.put("farmersdelight:brown_mushroom_colony", new CropOverrides(null, false, true));
		map.put("farmersdelight:red_mushroom_colony", new CropOverrides(null, false, true));
		return map;
	}

	private CropRegistry()
	{
	}

	// 强制重扫（数据包重载后调用）
	public static synchronized void invalidate()
	{
		cached = null;
	}

	// 全部已检测作物（含需水等不可种植条目）
	public static synchronized List<CropEntry> all()
	{
		if (cached == null) cached = scan();
		return cached;
	}

	// 可种植作物（排除需水条目）
	public static List<CropEntry> plantable()
	{
		return all().stream().filter(e -> !e.needsWater()).toList();
	}

	// 按种下方块查找
	public static CropEntry findByPlantBlock(Block block)
	{
		if (block == null) return null;
		for (CropEntry e : all())
		{
			if (e.plantBlock() == block) return e;
		}
		return null;
	}

	// 按成熟目标方块查找（两阶段作物的成株）
	public static CropEntry findByMatureBlock(Block block)
	{
		if (block == null) return null;
		for (CropEntry e : all())
		{
			if (e.matureBlock() == block) return e;
		}
		return null;
	}

	// 按种子物品查找
	public static CropEntry findBySeed(Item seed)
	{
		if (seed == null) return null;
		for (CropEntry e : all())
		{
			if (e.seed() == seed) return e;
		}
		return null;
	}

	// 注册名是否已检测（持久化 token 校验用）
	public static boolean isKnown(String plantBlockId)
	{
		return findByPlantBlockId(plantBlockId) != null;
	}

	// 按种下方块注册名查找（持久化 token 解析用）
	public static CropEntry findByPlantBlockId(String plantBlockId)
	{
		ResourceLocation id = ResourceLocation.tryParse(plantBlockId);
		if (id == null) return null;
		return findByPlantBlock(BuiltInRegistries.BLOCK.get(id));
	}

	// 扫描注册表：BlockItem && 可骨粉催熟 && 含 age 生长属性 && 非原版
	private static List<CropEntry> scan()
	{
		List<CropEntry> out = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM)
		{
			if (!(item instanceof BlockItem blockItem)) continue;
			Block block = blockItem.getBlock();
			if (block == null) continue;
			ResourceLocation id = block.builtInRegistryHolder().key().location();
			if (id.getNamespace().equals("minecraft")) continue;
			if (!(block instanceof BonemealableBlock)) continue;
			if (!hasAgeProperty(block)) continue;

			CropOverrides overrides = OVERRIDES.get(id.toString());
			if (overrides != null && overrides.excluded()) continue;
			Block mature = overrides != null && overrides.matureBlockId() != null
					? BuiltInRegistries.BLOCK.get(overrides.matureBlockId()) : block;
			boolean needsWater = overrides != null && overrides.needsWater();
			out.add(new CropEntry(item, block, mature, needsWater, id.getNamespace()));
		}
		out.sort(Comparator.comparing(e -> e.plantBlockId().toString()));
		LOGGER.info("NeoSim-CropRegistry: discovered {} mod crops: {}",
				out.size(), out.stream().map(e -> e.plantBlockId().toString()).toList());
		return out;
	}

	// 方块状态是否含 age 生长属性（CropBlock / BuddingBushBlock / RiceBlock 等均带）
	private static boolean hasAgeProperty(Block block)
	{
		return block.defaultBlockState().getProperties().stream()
				.anyMatch(p -> p.getName().equals("age"));
	}
}
