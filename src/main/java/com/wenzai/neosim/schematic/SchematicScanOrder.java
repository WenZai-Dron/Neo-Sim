package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

// 建造扫描顺序：按真实落点坐标排序，决定 resumeIndex 对应的蓝图格子。
//
// 每层里每个蓝图格子都算出落地后的世界坐标，然后按两个键排序：
//   主键 = 沿玩家面向方向的投影（升序 → 离玩家最近的排先建，一排排往远处推）
//   次键 = 沿玩家右手方向的投影（升序 → 每排从左往右铺）
// 排好的下标数组存起来，建造时照数组取格子；层永远是从低到高（y 递增）。
// 水平顺序对所有层都一样，所以只排一层（sizeX*sizeZ），各层共用。
// 排序只看落点，蓝图怎么转、怎么镜像、玩家按 R 转几次都成立。
public final class SchematicScanOrder
{
	private final int sizeX;
	private final int sizeZ;

	// 层内第几格 → 蓝图格子的 z*sizeX+x
	private final int[] order;

	private SchematicScanOrder(int sizeX, int sizeZ, int[] order)
	{
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		this.order = order;
	}

	// resumeIndex → 蓝图局部坐标：层 低→高，层内按"离玩家近 → 远、左 → 右"
	public void pos(int index, BlockPos.MutableBlockPos out)
	{
		// 防御：空蓝图（任一水平尺寸为 0）直接给原点，别除零
		if (sizeX <= 0 || sizeZ <= 0 || order.length == 0)
		{
			out.set(0, Math.max(0, index), 0);
			return;
		}

		int perLayer = Math.max(1, sizeX * sizeZ);
		int layer = index / perLayer;
		int within = index % perLayer;
		if (within >= order.length) within = order.length - 1;
		int cell = order[within];
		out.set(cell % sizeX, layer, cell / sizeX);
	}

	public static SchematicScanOrder of(BlueprintPlacement placement, @Nullable Direction facing,
										int sizeX, int sizeZ)
	{
		Direction f = facing == null ? Direction.SOUTH : facing;
		Direction right = f.getClockWise();

		// 防御：空蓝图直接给一个空顺序（pos() 会安全落到原点）
		if (sizeX <= 0 || sizeZ <= 0)
		{
			return new SchematicScanOrder(Math.max(1, sizeX), Math.max(1, sizeZ), new int[] { 0 });
		}

		int count = Math.max(1, sizeX * sizeZ);
		long[] keys = new long[count];
		int[] cells = new int[count];

		// 先收集：主键 = 面向方向上的投影，次键 = 右手方向的投影
		int minDepth = Integer.MAX_VALUE;
		int minRow = Integer.MAX_VALUE;
		int[] depth = new int[count];
		int[] row = new int[count];
		for (int z = 0; z < sizeZ; z++)
		{
			for (int x = 0; x < sizeX; x++)
			{
				int i = z * sizeX + x;
				BlockPos w = placement.pos(x, 0, z);
				depth[i] = w.getX() * f.getStepX() + w.getZ() * f.getStepZ();
				row[i] = w.getX() * right.getStepX() + w.getZ() * right.getStepZ();
				minDepth = Math.min(minDepth, depth[i]);
				minRow = Math.min(minRow, row[i]);
			}
		}

		// 归一到非负后打包成可比较的 long：主键在高位，次键在低位
		for (int i = 0; i < count; i++)
		{
			long d = depth[i] - minDepth;
			long r = row[i] - minRow;
			keys[i] = (d << 32) | r;
			cells[i] = i;
		}

		// 小规模插入排序即可（单层格子数通常几百到几万；O(n²) 对超大蓝图不合适，用归并思路的稳定排序）
		sortByKey(keys, cells);

		return new SchematicScanOrder(sizeX, sizeZ, cells);
	}

	// 按 keys 升序重排 cells（归并排序，稳定、无递归深度风险）
	private static void sortByKey(long[] keys, int[] cells)
	{
		int n = cells.length;
		if (n < 2) return;

		long[] tmpKeys = new long[n];
		int[] tmpCells = new int[n];
		for (int width = 1; width < n; width <<= 1)
		{
			for (int lo = 0; lo < n; lo += width << 1)
			{
				int mid = Math.min(lo + width, n);
				int hi = Math.min(lo + (width << 1), n);
				int i = lo, j = mid, k = lo;
				while (i < mid && j < hi)
				{
					if (keys[i] <= keys[j])
					{
						tmpKeys[k] = keys[i];
						tmpCells[k++] = cells[i++];
					}
					else
					{
						tmpKeys[k] = keys[j];
						tmpCells[k++] = cells[j++];
					}
				}
				while (i < mid)
				{
					tmpKeys[k] = keys[i];
					tmpCells[k++] = cells[i++];
				}
				while (j < hi)
				{
					tmpKeys[k] = keys[j];
					tmpCells[k++] = cells[j++];
				}
			}
			System.arraycopy(tmpKeys, 0, keys, 0, n);
			System.arraycopy(tmpCells, 0, cells, 0, n);
		}
	}

}
