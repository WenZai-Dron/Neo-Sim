package com.wenzai.neosim.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

// 蓝图落地：位置与朝向的**唯一一条链**。
// 顺序固定为：格式基础映射（SchematicFrame）-> 玩家镜像 -> 玩家旋转 -> 落点对齐。
// 世界幽灵预览、实际建造、包围盒、区块加载窗口全都从这里取，
// 这样「预览里看到的」和「建出来的」不可能再各走各的。
public final class BlueprintPlacement
{
	private final SchematicFrame frame;
	private final BlockPos origin;
	private final Direction facing;
	private final Mirror mirror;
	private final Rotation rotation;
	// 落点对齐偏移：只有世界帧需要算（.txt 的四套映射天生就把区域推在模盒外侧，偏移恒为 0）
	private final BlockPos landing;

	public BlueprintPlacement(SchematicFrame frame, int sizeX, int sizeZ, BlockPos origin,
							  Direction facing, Mirror mirror, Rotation rotation)
	{
		this.frame = frame;
		this.origin = origin == null ? BlockPos.ZERO : origin;
		this.facing = facing;
		this.mirror = mirror == null ? Mirror.NONE : mirror;
		this.rotation = rotation == null ? Rotation.NONE : rotation;
		this.landing = frame == SchematicFrame.WORLD
				? frame.landingOffset(sizeX, sizeZ, this.facing, this.mirror, this.rotation)
				: BlockPos.ZERO;
	}

	// 蓝图局部坐标 -> 世界坐标
	public BlockPos pos(int x, int y, int z)
	{
		BlockPos base = frame.toWorld(x, y, z, facing);
		BlockPos transformed = CoordTransform.transformPos(base, mirror, rotation);
		return origin.offset(transformed).offset(landing);
	}

	// 蓝图方块状态 -> 世界朝向（与 pos 同一条变换，绝不会出现"位置转了、朝向没转"）
	public BlockState state(BlockState s)
	{
		if (s == null) return null;
		BlockState out = frame.toWorldState(s, facing);
		if (out == null) return s;
		if (mirror != Mirror.NONE) out = out.mirror(mirror);
		if (rotation != Rotation.NONE) out = out.rotate(rotation);
		return out;
	}

	public SchematicFrame frame()
	{
		return frame;
	}

	public BlockPos origin()
	{
		return origin;
	}
}