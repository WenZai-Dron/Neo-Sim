package com.wenzai.neosim.schematic;

import com.wenzai.neosim.compat.attached.AttachedBlockTable;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

// 统计蓝图所需的全部方块数量
public class MaterialCalculator
{
	// 工具类：只有静态成员，禁止实例化
	private MaterialCalculator()
	{
	}

	// Block → [0]=普通模式耗材, [1]=困难模式免费 静态 IdentityHashMap 缓存（首次判定后查表，消灭每格 contains/字符串分配）
	private static final IdentityHashMap<Block, boolean[]> CLASS_CACHE = new IdentityHashMap<>();

	// 材料清单按 (schematicName, mode) 缓存（进页/模式变化时键变化即失效）
	private static String cachedCalcKey;
	private static List<MaterialEntry> cachedCalcResult;

	// 依附性方块：判定统一交给可编辑的 AttachedBlockTable
	// （内置类型规则 = instanceof 注册 + jar 内置 JSON + NeoSim/Json/ 外部覆盖 + canSurvive 兜底）
	public static boolean isAttachedBlock(BlockState state)
	{
		return AttachedBlockTable.isAttached(state);
	}

	// 内容表重载后清空分类缓存（依附判定与材料清单都重新计算）
	public static void invalidateClassification()
	{
		CLASS_CACHE.clear();
		cachedCalcKey = null;
		cachedCalcResult = null;
	}

	// 方块注册名
	private static String blockName(Block block)
	{
		return block.builtInRegistryHolder().key().location().getPath();
	}

	// 普通模式：仅基础建材消耗材料，装饰/功能/衍生方块一律免费
	private static boolean computeNormalRequired(Block block)
	{
		String name = blockName(block);

		// 衍生/装饰/功能方块：一律免费（不耗材）
		if (isFreeDerivative(name)) return false;

		// 一、木材族：木板 / 栅栏（栅栏门免费）/ 原木·去皮原木·菌柄·菌柄体·竹块（含 1.19+ 红树/樱花木/竹）
		if (name.contains("planks")) return true;
		if (name.contains("fence")) return true;
		if (name.contains("wood") || name.contains("log")
				|| name.contains("crimson_stem") || name.contains("warped_stem")
				|| name.contains("hyphae")
				|| name.equals("bamboo_block") || name.equals("stripped_bamboo_block"))
			return true;

		// 二、石材族：石头/圆石/深板岩/凝灰岩/黑石/玄武岩/方解石/花岗岩/闪长岩/安山岩/末地石/砂岩/海晶石/石英及其加工品（1.17+ 深板岩/凝灰岩、1.16 黑石/玄武岩/石英；含磨制/抛光/砖/瓦/雕纹变体）
		if (name.equals("stone") || name.equals("smooth_stone") || name.equals("chiseled_stone")
				|| name.contains("cobblestone")
				|| name.contains("deepslate")
				|| name.contains("tuff")
				|| name.contains("blackstone")
				|| name.contains("basalt")
				|| name.equals("calcite")
				|| name.contains("granite") || name.contains("diorite") || name.contains("andesite")
				|| name.contains("end_stone")
				|| name.contains("sandstone")
				|| name.contains("prismarine")
				|| name.contains("quartz"))
			return true;

		// 三、砖类：砖块及全部砖变体（泥砖/深板岩砖/凝灰岩砖/下界砖/石砖/黑石砖等）
		if (name.contains("bricks")) return true;

		// 四、其他基础建材：玻璃（玻璃板免费）/羊毛/泥土/砂土/泥坯
		if (name.equals("glass") || name.contains("wool")
				|| name.equals("dirt") || name.equals("coarse_dirt")
				|| name.equals("packed_mud"))
			return true;

		// 五、铜块族（1.17/1.21）：铜块/切制铜/雕纹铜及氧化·涂蜡变体；铜门/活板门/格栅/灯已在上方排除
		if (name.contains("copper")) return true;

		return false;
	}

	// 衍生 / 装饰 / 功能方块：普通模式免费（不耗材），与"仅基础建材耗材"设计一致
	private static boolean isFreeDerivative(String name)
	{
		// 建筑衍生/装饰/功能方块：台阶/楼梯/墙/门/活板门/栅栏门/压力板/按钮/拉杆/火把/玻璃板/铁轨/床/告示牌/地毯/蜡烛/灯笼/锁链/花盆/画框/头颅/旗帜/脚手架/切石机/营火/酿造台/信标/刷怪笼/宝库/重型核/铜格栅/铜灯/矿石
		if (name.contains("slab") || name.contains("stairs") || name.contains("wall")
				|| name.contains("door") || name.contains("trapdoor") || name.contains("gate")
				|| name.contains("pressure_plate") || name.contains("button") || name.contains("lever")
				|| name.contains("torch") || name.contains("pane") || name.contains("rail")
				|| name.contains("bed") || name.contains("sign") || name.contains("carpet")
				|| name.contains("candle") || name.contains("lantern") || name.contains("chain")
				|| name.contains("pot") || name.contains("frame") || name.contains("head")
				|| name.contains("banner") || name.contains("scaffolding") || name.contains("stonecutter")
				|| name.contains("campfire") || name.contains("brewing") || name.contains("beacon")
				|| name.contains("spawner") || name.contains("vault") || name.contains("core")
				|| name.contains("grate") || name.contains("bulb") || name.contains("ore"))
			return true;

		// 植物/自然方块：草/作物/花/树苗/树叶/藤蔓/海带/海草/蘑菇/蕨/仙人掌/南瓜/西瓜/甘蔗/可可/下界疣/甜浆果/瓶子草/火把花
		return name.contains("grass") || name.contains("crop") || name.contains("plant")
				|| name.contains("flower") || name.contains("sapling") || name.contains("leaves")
				|| name.contains("vine") || name.contains("kelp") || name.contains("seagrass")
				|| name.contains("mushroom") || name.contains("fern") || name.contains("cactus")
				|| name.contains("melon") || name.contains("pumpkin") || name.contains("attached")
				|| name.contains("sugar_cane") || name.contains("cocoa") || name.contains("nether_wart")
				|| name.contains("sweet_berry") || name.contains("pitcher") || name.contains("torchflower");
	}

	// 困难模式
	private static boolean computeHardcoreFree(Block block)
	{
		// 免费清单
		String name = blockName(block);
		return name.contains("water") || name.contains("lava")
				|| name.contains("grass") || name.contains("bed") || name.contains("sign")
				|| name.contains("cake") || name.contains("door") || name.contains("slab")
				|| name.contains("farmland");
	}

	// 按运行模式判断该方块是否需要（分类结果走 IdentityHashMap 缓存）
	public static boolean requiresMaterial(BlockState state, byte mode)
	{
		if (state.isAir()) return false;
		return switch (mode)
		{
			case 2 -> false;
			case 3 -> {
				Block block = state.getBlock();
				boolean[] c = CLASS_CACHE.get(block);
				if (c == null)
				{
					c = new boolean[2];
					c[0] = computeNormalRequired(block);
					c[1] = computeHardcoreFree(block);
					CLASS_CACHE.put(block, c);
				}
				yield !c[1];
			}
			default -> {
				Block block = state.getBlock();
				boolean[] c = CLASS_CACHE.get(block);
				if (c == null)
				{
					c = new boolean[2];
					c[0] = computeNormalRequired(block);
					c[1] = computeHardcoreFree(block);
					CLASS_CACHE.put(block, c);
				}
				yield c[0];
			}
		};
	}

	// 按数量降序排列的材料清单条目（按 (schematicName, mode) 缓存 + 按 palette 项聚合）
	public static List<MaterialEntry> calculate(SchematicData schematic, byte mode)
	{
		String key = schematic.getName() + "|" + mode;
		List<MaterialEntry> hit = cachedCalcResult;
		if (cachedCalcKey != null && cachedCalcKey.equals(key) && hit != null)
		{
			return hit;
		}

		LightweightBlockContainer container = schematic.getBlockContainer();
		int[] usage = container.countPaletteUsage();
		BlockStatePalette palette = container.getPalette();
		Map<Item, Integer> counts = new LinkedHashMap<>();

		for (int id = 0; id < usage.length; id++)
		{
			if (usage[id] == 0) continue;
			BlockState state = palette.getBlockState(id);
			if (state.isAir()) continue;
			if (!requiresMaterial(state, mode)) continue;

			Item item = state.getBlock().asItem();
			counts.merge(item, usage[id], Integer::sum);
		}

		List<MaterialEntry> entries = new ArrayList<>();
		for (Map.Entry<Item, Integer> e : counts.entrySet())
		{
			entries.add(new MaterialEntry(e.getKey(), e.getValue()));
		}
		entries.sort((a, b) -> Integer.compare(b.count, a.count));

		cachedCalcKey = key;
		cachedCalcResult = entries;
		return entries;
	}

	public static class MaterialEntry
	{
		public final Item item;
		public final int count;

		MaterialEntry(Item item, int count)
		{
			this.item = item;
			this.count = count;
		}

		public String formatted()
		{
			return (count / 64) + " * 64 + " + (count % 64);
		}
	}
}
