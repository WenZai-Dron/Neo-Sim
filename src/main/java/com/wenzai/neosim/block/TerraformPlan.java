package com.wenzai.neosim.block;

import com.wenzai.neosim.Config;
import com.wenzai.neosim.building.InventoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

// 整地方案：扫描/判定/变换/材料/文案 全在枚举内；执行时逐块复核判定
public enum TerraformPlan
{
	// 推平：挖除基线以上 Y∈(baseline, baseline+6] 的地形方块，掉落物尝试入箱
	FLATTEN("gui.neosim.BuildingConstructor.plan.FLATTEN", "gui.neosim.BuildingConstructor.plan.FLATTEN.desc", null)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				for (int y = baselineY + 1; y <= baselineY + 6; y++)
				{
					BlockPos p = new BlockPos(x, y, z);
					if (isTerrain(level.getBlockState(p))) out.add(p);
				}
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			return isTerrain(level.getBlockState(pos));
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			removeInto(level, pos, chests);
			return true;
		}
	},

	// 铺泥土：基线层铺一层泥土（空气/可替换地面）
	VALUE_PACK("gui.neosim.BuildingConstructor.plan.VALUE_PACK", "gui.neosim.BuildingConstructor.plan.VALUE_PACK.desc", Items.DIRT)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				BlockPos p = new BlockPos(x, baselineY, z);
				if (isReplaceableGround(level.getBlockState(p))) out.add(p);
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			return isReplaceableGround(level.getBlockState(pos));
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
			return true;
		}
	},

	// 铺草皮：基线层铺一层草方块
	GRASS_PACK("gui.neosim.BuildingConstructor.plan.GRASS_PACK", "gui.neosim.BuildingConstructor.plan.GRASS_PACK.desc", Items.GRASS_BLOCK)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				BlockPos p = new BlockPos(x, baselineY, z);
				if (isReplaceableGround(level.getBlockState(p))) out.add(p);
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			return isReplaceableGround(level.getBlockState(pos));
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			return true;
		}
	},

	// 填水：每列水方块自底向上填泥土（防再生成）
	SEALAND("gui.neosim.BuildingConstructor.plan.SEALAND", "gui.neosim.BuildingConstructor.plan.SEALAND.desc", Items.DIRT)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			int depth = Config.TERRAFORM_WATER_DEPTH.get();
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				List<BlockPos> column = new ArrayList<>();
				for (int y = baselineY; y >= baselineY - depth; y--)
				{
					BlockPos p = new BlockPos(x, y, z);
					if (level.getFluidState(p).is(Fluids.WATER))
					{
						column.add(p);
					}
					else if (!column.isEmpty())
					{
						break; // 水面以下遇非水即列底
					}
				}
				// 底→顶，保证整列封死不回流
				for (int i = column.size() - 1; i >= 0; i--)
				{
					out.add(column.get(i));
				}
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			return level.getFluidState(pos).is(Fluids.WATER);
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
			return true;
		}
	},

	// 割草：清除草/花/蕨，掉落物尝试入箱
	LAWNMOWER("gui.neosim.BuildingConstructor.plan.LAWNMOWER", "gui.neosim.BuildingConstructor.plan.LAWNMOWER.desc", null)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				for (int y = baselineY; y <= baselineY + 1; y++)
				{
					BlockPos p = new BlockPos(x, y, z);
					if (isPlant(level.getBlockState(p))) out.add(p);
				}
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			return isPlant(level.getBlockState(pos));
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			removeInto(level, pos, chests);
			return true;
		}
	},

	// 植树：地表种树苗/花（每 15 块 1 树苗，仿 Sim-U-Kraft）
	NATURE("gui.neosim.BuildingConstructor.plan.NATURE", "gui.neosim.BuildingConstructor.plan.NATURE.desc", null)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				BlockPos p = new BlockPos(x, baselineY + 1, z);
				BlockState below = level.getBlockState(new BlockPos(x, baselineY, z));
				if (level.getBlockState(p).isAir()
						&& (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT)))
				{
					out.add(p);
				}
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			BlockState below = level.getBlockState(pos.below());
			return level.getBlockState(pos).isAir()
					&& (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT));
		}

		@Override
		public boolean needsMaterial(int processedIndex)
		{
			return processedIndex % 15 == 0;
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			if (processedIndex % 15 == 0 && !material.isEmpty())
			{
				level.setBlock(pos, Block.byItem(material.getItem()).defaultBlockState(), 3);
			}
			else
			{
				level.setBlock(pos, randomFlower(level), 3);
			}
			return true;
		}
	},

	// 除雪：清除雪层，掉落物尝试入箱
	DEICER("gui.neosim.BuildingConstructor.plan.DEICER", "gui.neosim.BuildingConstructor.plan.DEICER.desc", null)
	{
		@Override
		public void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out)
		{
			scanArea(minX, minZ, maxX, maxZ, (x, z) ->
			{
				for (int y = baselineY; y <= baselineY + 1; y++)
				{
					BlockPos p = new BlockPos(x, y, z);
					if (level.getBlockState(p).is(Blocks.SNOW)) out.add(p);
				}
			});
		}

		@Override
		public boolean matches(ServerLevel level, BlockPos pos)
		{
			return level.getBlockState(pos).is(Blocks.SNOW);
		}

		@Override
		public boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests)
		{
			removeInto(level, pos, chests);
			return true;
		}
	};

	private final String labelKey;
	private final String descKey;
	private final Item materialItem;

	TerraformPlan(String labelKey, String descKey, Item materialItem)
	{
		this.labelKey = labelKey;
		this.descKey = descKey;
		this.materialItem = materialItem;
	}

	public String labelKey()
	{
		return labelKey;
	}

	public String descKey()
	{
		return descKey;
	}

	// 该方案是否需要从箱子取料（null=免费方案）
	public Item materialItem()
	{
		return materialItem;
	}

	// 默认：有材料则每块都取；NATURE 覆盖为每 15 块取 1 树苗
	public boolean needsMaterial(int processedIndex)
	{
		return materialItem != null;
	}

	// 扫描地块收集目标方块列表
	public abstract void scan(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int baselineY, List<BlockPos> out);

	// 执行时复核判定（物理联动后可能已失效）
	public abstract boolean matches(ServerLevel level, BlockPos pos);

	// 执行变换；返回是否实际改变了方块（用于扣款）
	public abstract boolean apply(ServerLevel level, BlockPos pos, int processedIndex, ItemStack material, List<ChestBlockEntity> chests);

	// 非法名返回 null
	public static TerraformPlan valueOfSafe(String name)
	{
		if (name == null || name.isEmpty()) return null;
		for (TerraformPlan p : values())
		{
			if (p.name().equals(name)) return p;
		}
		return null;
	}

	// 与农业/矿业盒一致：标记所在的矩形边框（min/max 那一圈）不纳入整地范围，
	// 内缩 1 格；跨度 ≤2（差 ≤1）时退化为无内缩，避免小地块被扫空
	protected static void scanArea(int minX, int minZ, int maxX, int maxZ,
			java.util.function.BiConsumer<Integer, Integer> each)
	{
		int ix = (maxX - minX > 1) ? 1 : 0;
		int iz = (maxZ - minZ > 1) ? 1 : 0;
		for (int x = minX + ix; x <= maxX - ix; x++)
		{
			for (int z = minZ + iz; z <= maxZ - iz; z++)
			{
				each.accept(x, z);
			}
		}
	}

	// 地形方块白名单（推平判定）
	private static boolean isTerrain(BlockState state)
	{
		Block b = state.getBlock();
		return b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.COARSE_DIRT
				|| b == Blocks.PODZOL || b == Blocks.STONE || b == Blocks.GRANITE
				|| b == Blocks.DIORITE || b == Blocks.ANDESITE || b == Blocks.DEEPSLATE
				|| b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.GRAVEL
				|| b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE || b == Blocks.TUFF
				|| b == Blocks.CALCITE;
	}

	// 铺地判定：空气或可替换方块，但排除水/岩浆
	private static boolean isReplaceableGround(BlockState state)
	{
		return (state.isAir() || state.canBeReplaced())
				&& !state.getFluidState().is(Fluids.WATER)
				&& !state.getFluidState().is(Fluids.LAVA);
	}

	// 植被判定（割草）
	private static boolean isPlant(BlockState state)
	{
		Block b = state.getBlock();
		return b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS
				|| b == Blocks.FERN || b == Blocks.LARGE_FERN
				|| b instanceof FlowerBlock;
	}

	// 随机花（植树）
	private static BlockState randomFlower(ServerLevel level)
	{
		Block[] flowers = {
				Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.AZURE_BLUET,
				Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP,
				Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.ALLIUM
		};
		return flowers[level.random.nextInt(flowers.length)].defaultBlockState();
	}

	// 挖除方块：掉落物尝试入箱，放不下则自然掉落（防物品丢失）
	private static void removeInto(ServerLevel level, BlockPos pos, List<ChestBlockEntity> chests)
	{
		BlockState state = level.getBlockState(pos);
		List<ItemStack> drops = InventoryManager.getBlockDrops(level, pos, state);
		level.destroyBlock(pos, false);
		for (ItemStack drop : drops)
		{
			if (drop.isEmpty()) continue;
			if (!chests.isEmpty() && InventoryManager.canDeposit(chests, drop))
			{
				InventoryManager.depositItems(chests, drop);
			}
			else
			{
				Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
			}
		}
	}
}
