package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

// 蓝图坐标系算法：一种蓝图格式对应一套，位置和方块状态共用同一个函数，
// 避免"位置转过去了、朝向没转"这类口径不一致
public enum SchematicFrame
{
	// Sim-U-Kraft 旧版 .txt：数据在作者上传时的蓝图帧里，
	// 必须走 Suk 的 buildDirection 映射（已与 0.12.1 原版源码逐行核对一致）
	SUKRAFT,

	// 世界坐标帧：.litematic 等（投影是照世界导出的）
	WORLD;

	// ---- .litematic 算法的可调参数（只在这里改）----
	// 投影蓝图（.litematic/.schem）存的是**保存那一刻的世界坐标**，文件里没有"朝向"字段，
	// 所以世界帧本身推不出固定朝向：这里按玩家的面朝方向把蓝图转过去（跟 .txt 一样"跟着人转"），
	// 基准角 = 朝南放置时相对"保存时的朝向"把蓝图转几次：
	// NONE=不转、CLOCKWISE_90=转 1 次、CLOCKWISE_180=转 2 次、COUNTERCLOCKWISE_90=转 3 次
	// 现在选「转 3 次」（270°）。注意：转 1 次时落点才会和同名 .txt 完全重合；
	// 转 2/3 次会把投影甩到 origin 的另一侧（和 .txt 不重叠，可能把模盒圈进地基）
	private static final Rotation LITEMATIC_BASE = Rotation.COUNTERCLOCKWISE_90;

	// 是否跟随玩家面朝方向：真 = 南按基准角、东=基准+90°、西=基准-90°、北=基准+180°
	private static final boolean LITEMATIC_FOLLOW_FACING = true;


	// ---- GUI 缩略图算法（只影响蓝图库里的那张小图，不影响世界预览与实际放置）----
	// .txt 的数据本来就存在「作者帧」里（作者上传时那一帧），缩略图照原样画才对；
	// .litematic 的数据是照世界坐标导出的，照原样画会和 .txt 蓝图库差 90° + 镜像，
	// 所以缩略图先把它转进 .txt 那套蓝图帧（四套映射本身就是"转 N 次 + 镜像"）：
	// NORTH=转 0 次、EAST=转 1 次、SOUTH=转 2 次、WEST=转 3 次，各自都带那一次镜像
	// 现在是「转 3 次 + 镜像」，和世界那边同一个转数（世界那边是转 3 次、不带镜像）
	private static final Direction BLUEPRINT_VIEW = Direction.WEST;

	// 蓝图帧坐标：GUI 缩略图专用
	public BlockPos toBlueprintView(int x, int y, int z)
	{
		if (this == SUKRAFT) return new BlockPos(x, y, z);
		return CoordTransform.simukraftPos(x, y, z, BLUEPRINT_VIEW);
	}

	// 蓝图帧朝向：GUI 缩略图专用
	public BlockState toBlueprintViewState(@Nullable BlockState state)
	{
		if (state == null) return null;
		if (this == SUKRAFT) return state;

		// 位置用 simukraftPos（旋转 + 镜像的映射），状态就用与之严格配对的原版 rotate/mirror：
		// 手改 HORIZONTAL_FACING 会漏掉告示牌的 ROTATION（非朝向属性）和楼梯/门的附属属性
		return CoordTransform.transformStateByFrame(state, BLUEPRINT_VIEW);
	}

	// 按格式取算法；蓝图缺失时退回 .txt 口径（保守，和改动前行为一致）
	public static SchematicFrame of(@Nullable SchematicData schematic)
	{
		if (schematic == null) return SUKRAFT;
		return schematic.getFormat() == SchematicFormat.SIM_UKRAFT_TXT ? SUKRAFT : WORLD;
	}

	// 蓝图局部坐标 -> 世界坐标（不含起点偏移、不含玩家自己调的镜像/旋转）
	public BlockPos toWorld(int x, int y, int z, @Nullable Direction facing)
	{
		if (this == SUKRAFT)
		{
			return facing != null ? CoordTransform.simukraftPos(x, y, z, facing) : new BlockPos(x, y, z);
		}

		// WORLD：绕 Y 轴纯旋转（基准角 + 玩家面朝方向），不做反射
		BlockPos pos = rotatePos(x, y, z, LITEMATIC_BASE);
		if (LITEMATIC_FOLLOW_FACING)
		{
			pos = rotatePos(pos.getX(), pos.getY(), pos.getZ(), facingRotation(facing));
		}
		return pos;
	}

	// 落点对齐（只给世界帧用）：把"映射 + 玩家镜像/旋转"之后的整块区域平移到模盒外侧那一象限，
	// 规则和 .txt 天生做出来的落点一致 —— 南：区域在 origin 的 x 小/z 大侧，北：x 大/z 小，
	// 东：x 小/z 小，西：x 大/z 大（也就是把这张象限里"最贴 origin"的那个包围盒角钉在 origin 上）
	// 这样不管基准角转了几次、玩家又按了几次 R，投影都落在模盒前方那一侧，不会把模盒圈进地基
	public BlockPos landingOffset(int sizeX, int sizeZ, @Nullable Direction facing,
								Mirror mirror, Rotation rotation)
	{
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < 2; i++)
		{
			for (int k = 0; k < 2; k++)
			{
				BlockPos p = CoordTransform.transformPos(
								toWorld(i * (sizeX - 1), 0, k * (sizeZ - 1), facing), mirror, rotation);
				minX = Math.min(minX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxX = Math.max(maxX, p.getX());
				maxZ = Math.max(maxZ, p.getZ());
			}
		}
		Direction f = facing == null ? Direction.SOUTH : facing;

		// 南/西 把 x 大的那个角贴 origin；北/西 把 z 大的那个角贴 origin
		boolean anchorMaxX = f == Direction.SOUTH || f == Direction.WEST;
		boolean anchorMaxZ = f == Direction.NORTH || f == Direction.WEST;
		return new BlockPos(anchorMaxX ? -maxX : -minX, 0, anchorMaxZ ? -maxZ : -minZ);
	}

	// 蓝图方块状态 -> 世界朝向（与 toWorld 用同一条变换）
	public BlockState toWorldState(@Nullable BlockState state, @Nullable Direction facing)
	{
		if (state == null) return null;
		if (this == SUKRAFT)
		{
			return CoordTransform.transformState(state, facing);
		}

		BlockState rotated = LITEMATIC_BASE == Rotation.NONE ? state : state.rotate(LITEMATIC_BASE);
		if (LITEMATIC_FOLLOW_FACING)
		{
			Rotation r = facingRotation(facing);
			if (r != Rotation.NONE) rotated = rotated.rotate(r);
		}
		return rotated;
	}

	// 绕 Y 轴旋转蓝图坐标（MC 的 Rotation 语义：CLOCKWISE_90 是 (x,z)->(-z,x)）
	private static BlockPos rotatePos(int x, int y, int z, Rotation rotation)
	{
		return switch (rotation)
		{
			case CLOCKWISE_90 -> new BlockPos(-z, y, x);
			case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
			case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
			default -> new BlockPos(x, y, z);
		};
	}

	// 玩家面朝方向对应的纯旋转
	private static Rotation facingRotation(@Nullable Direction facing)
	{
		return switch (facing == null ? Direction.SOUTH : facing)
		{
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
			case WEST -> Rotation.CLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}
}
