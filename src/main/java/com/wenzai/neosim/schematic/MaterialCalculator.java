package com.wenzai.neosim.schematic;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 统计蓝图所需的全部方块数量
public class MaterialCalculator
{
	public static boolean isAttachedBlock(BlockState state)
	{
		Block block = state.getBlock();

		// 依附性方块：铁轨/火把/梯子/门/按钮/拉杆/藤蔓/立牌/墙牌/压力板/红石线/农作物/南瓜·西瓜茎/附着茎/树苗/花丛/甘蔗/花盆/旗帜/床/可可豆/绊线钩/绊线/雪层/铁砧/地毯（需下方支撑，第二阶段放）
		if (block instanceof BaseRailBlock) return true;
		if (block instanceof TorchBlock) return true;
		if (block instanceof LadderBlock) return true;
		if (block instanceof DoorBlock) return true;
		if (block instanceof ButtonBlock) return true;
		if (block instanceof LeverBlock) return true;
		if (block instanceof VineBlock) return true;
		if (block instanceof StandingSignBlock) return true;
		if (block instanceof WallSignBlock) return true;
		if (block instanceof PressurePlateBlock) return true;
		if (block instanceof RedStoneWireBlock) return true;
		if (block instanceof CropBlock) return true;
		if (block instanceof StemBlock) return true;
		if (block instanceof AttachedStemBlock) return true;
		if (block instanceof SaplingBlock) return true;
		if (block instanceof BushBlock) return true;
		if (block instanceof SugarCaneBlock) return true;
		if (block instanceof FlowerPotBlock) return true;
		if (block instanceof BannerBlock) return true;
		if (block instanceof BedBlock) return true;
		if (block instanceof CocoaBlock) return true;
		if (block instanceof TripWireHookBlock) return true;
		if (block instanceof TripWireBlock) return true;
		if (block instanceof SnowLayerBlock) return true;
		if (block instanceof AnvilBlock) return true;
		if (block instanceof CarpetBlock) return true;

		// 连接性方块：墙/栅栏/铁栏杆/玻璃板
		if (block instanceof WallBlock) return true;
		if (block instanceof FenceBlock) return true;
		if (block instanceof IronBarsBlock) return true;
		if (block instanceof StainedGlassPaneBlock) return true;
		return false;
	}

	// 方块注册名
	private static String blockName(BlockState state)
	{
		return state.getBlock().builtInRegistryHolder().key().location().getPath();
	}

	// 普通模式：仅基础建材消耗材料，装饰/功能/衍生方块一律免费
	private static boolean isNormalRequired(BlockState state)
	{
		String name = blockName(state);

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
	private static boolean isHardcoreFree(BlockState state)
	{
		// 免费清单
		String name = blockName(state);
		return name.contains("water") || name.contains("lava")
				|| name.contains("grass") || name.contains("bed") || name.contains("sign")
				|| name.contains("cake") || name.contains("door") || name.contains("slab")
				|| name.contains("farmland");
	}

	// 按运行模式判断该方块是否需要
	public static boolean requiresMaterial(BlockState state, byte mode)
	{
		if (state.isAir()) return false;
		return switch (mode)
		{
			case 2 -> false;
			case 3 -> !isHardcoreFree(state);
			default -> isNormalRequired(state);
		};
	}

	// 按数量降序排列的材料清单条目
	public static List<MaterialEntry> calculate(SchematicData schematic, byte mode)
	{
		LightweightBlockContainer container = schematic.getBlockContainer();
		Map<Item, Integer> counts = new LinkedHashMap<>();

		for (int y = 0; y < container.getSizeY(); y++)
		{
			for (int z = 0; z < container.getSizeZ(); z++)
			{
				for (int x = 0; x < container.getSizeX(); x++)
				{
					BlockState state = container.get(x, y, z);
					if (state.isAir()) continue;
					if (!requiresMaterial(state, mode)) continue;

					Item item = state.getBlock().asItem();
					counts.merge(item, 1, Integer::sum);
				}
			}
		}

		List<MaterialEntry> entries = new ArrayList<>();
		for (Map.Entry<Item, Integer> e : counts.entrySet())
		{
			entries.add(new MaterialEntry(e.getKey(), e.getValue()));
		}
		entries.sort((a, b) -> Integer.compare(b.count, a.count));
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
