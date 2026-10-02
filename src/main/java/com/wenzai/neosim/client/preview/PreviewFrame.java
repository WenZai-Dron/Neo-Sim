package com.wenzai.neosim.client.preview;

import com.wenzai.neosim.building.PlacementSupport;
import com.wenzai.neosim.schematic.LightweightBlockContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

// 预览帧容器：把蓝图容器按一条映射（位置 + 状态）整块铺进预览自己的坐标系，并在帧内重算连接性方块
// GUI 缩略图与世界幽灵预览共用这一步，差别只在各自传进来的映射：
// 缩略图传蓝图帧映射（认楼用，见 BuildingPreviewRenderer.BlueprintView），幽灵预览传落地链（BlueprintPlacement）
public final class PreviewFrame
{
	// 帧内容器 + 帧在映射坐标系里的最小角（已平移到 0 基）
	public record Framed(LightweightBlockContainer container, BlockPos min,
						 int sizeX, int sizeY, int sizeZ) {}

	// 蓝图局部坐标 -> 预览坐标
	public interface PosMapper
	{
		BlockPos map(int x, int y, int z);
	}

	// 蓝图方块状态 -> 预览状态
	public interface StateMapper
	{
		BlockState map(BlockState state);
	}

	private PreviewFrame()
	{
	}

	// GUI 缩略图：蓝图没有落点，帧内空气格与帧外都按空气算
	public static Framed build(LightweightBlockContainer source, PosMapper posMapper, StateMapper stateMapper)
	{
		return build(source, posMapper, stateMapper, null);
	}

	// 世界幽灵预览：真实世界参与取邻居——实际建造跳过蓝图空气格、也不清场，
	// 蓝图边缘的栅栏会连到旁边的既有方块，预览得算同一份邻居才对得上
	public static Framed build(LightweightBlockContainer source, PosMapper posMapper, StateMapper stateMapper,
							   @Nullable BlockAndTintGetter outsideWorld)
	{
		int sx = source.getSizeX();
		int sy = source.getSizeY();
		int sz = source.getSizeZ();

		// 映射都是轴对轴的（旋转/镜像/平移，没有缩放），包围盒取源区域的 8 个角就够
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < 2; i++)
		{
			for (int k = 0; k < 2; k++)
			{
				for (int m = 0; m < 2; m++)
				{
					BlockPos p = posMapper.map(i * (sx - 1), k * (sy - 1), m * (sz - 1));
					minX = Math.min(minX, p.getX());
					minY = Math.min(minY, p.getY());
					minZ = Math.min(minZ, p.getZ());
					maxX = Math.max(maxX, p.getX());
					maxY = Math.max(maxY, p.getY());
					maxZ = Math.max(maxZ, p.getZ());
				}
			}
		}

		int dimX = maxX - minX + 1;
		int dimY = maxY - minY + 1;
		int dimZ = maxZ - minZ + 1;

		LightweightBlockContainer framed = new LightweightBlockContainer(dimX, dimY, dimZ);
		for (int y = 0; y < sy; y++)
		{
			for (int z = 0; z < sz; z++)
			{
				for (int x = 0; x < sx; x++)
				{
					BlockState state = source.get(x, y, z);
					if (state.isAir()) continue;

					BlockPos p = posMapper.map(x, y, z);
					framed.set(p.getX() - minX, p.getY() - minY, p.getZ() - minZ, stateMapper.map(state));
				}
			}
		}

		// 连接性方块：邻居此时已在帧内就位，按原版 updateShape 重算连接臂
		// .txt 只存方块 id + meta，映射出来的是默认状态（四个连接臂全 false），不重算就画成一根根孤立的柱子；
		// 重算放在帧内（而不是蓝图局部坐标）是因为位置与朝向可能各转了一套，只有帧内邻居才是真的挨着
		BlockPos min = new BlockPos(minX, minY, minZ);
		BlueprintLevelView level = new BlueprintLevelView(framed, min, outsideWorld);
		for (int y = 0; y < dimY; y++)
		{
			for (int z = 0; z < dimZ; z++)
			{
				for (int x = 0; x < dimX; x++)
				{
					BlockState state = framed.get(x, y, z);
					if (state.isAir() || !PlacementSupport.isConnective(state)) continue;

					framed.set(x, y, z, PlacementSupport.fixConnectiveConnections(
							level, new BlockPos(x, y, z), state));
				}
			}
		}

		return new Framed(framed, min, dimX, dimY, dimZ);
	}
}
