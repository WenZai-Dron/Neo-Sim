package com.wenzai.neosim.building;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.compat.attached.AttachMode;
import com.wenzai.neosim.compat.attached.AttachedBlockTable;
import com.wenzai.neosim.compat.sable.PhysicsWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import javax.annotation.Nullable;

// 蓝图落地收尾的唯一实现：建造模盒（ConstructionTask）与重建模盒（RebuildTask）共用同一份，
// 避免"依附朝向修正 / 连接性方块重算 / 双方块补齐 / 双开门配对 / 双箱合并"在两处各写一遍后行为漂移
public final class PlacementSupport
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private PlacementSupport()
	{
	}

	// 依附性方块朝向修正：按依附方式分派（贴墙 / 地面 / 悬挂 / 任意），找不到支撑返回 null（调用方计入缺件并跳过）
	public static BlockState fixAttachedFacing(ServerLevel level, BlockPos worldPos, BlockState state)
	{
		Block block = state.getBlock();
		AttachMode mode = AttachedBlockTable.mode(state);

		// 贴墙类
		if (mode == AttachMode.WALL)
		{
			if (block instanceof VineBlock)
			{
				for (Direction d : Direction.Plane.HORIZONTAL)
				{
					if (state.getValue(vineProperty(d)) && hasSupport(level, worldPos, d))
					{
						// 蓝图方向已贴墙
						return state;
					}
				}
				for (Direction d : Direction.Plane.HORIZONTAL)
				{
					if (hasSupport(level, worldPos, d))
					{
						return state.setValue(vineProperty(d), true);
					}
				}
				return null;
			}

			Property<Direction> facingProp = facingProperty(state);
			if (facingProp == null) return state;
			Direction facing = state.getValue(facingProp);

			if (hasSupport(level, worldPos, facing.getOpposite())) return state;

			for (Direction d : Direction.Plane.HORIZONTAL)
			{
				if (d != facing && hasSupport(level, worldPos, d.getOpposite()))
				{
					return state.setValue(facingProp, d);
				}
			}
			return null;
		}

		// 地面类：下方需支撑（不含空气，水/岩浆也算支撑，与原逻辑一致）
		if (mode == AttachMode.GROUND)
		{
			return PhysicsWorld.getBlockState(level, worldPos.below()).isAir() ? null : state;
		}

		// 悬挂类：上方需支撑（灯笼 / 锁链 / 挂式告示牌）
		if (mode == AttachMode.CEILING)
		{
			return PhysicsWorld.getBlockState(level, worldPos.above()).isAir() ? null : state;
		}

		// ANY / AUTO / NONE：不做朝向与支撑修正
		return state;
	}

	// 连接性方块：判定交给可编辑的 AttachedBlockTable（墙/栅栏/铁栏杆/玻璃板及其模组子类）
	public static boolean isConnective(BlockState state)
	{
		return AttachedBlockTable.isConnective(state);
	}

	// 连接性方块：放置时逐个方向按实际相邻方块重算连接，避免蓝图的连接臂指向空气
	public static BlockState fixConnectiveConnections(ServerLevel level, BlockPos pos, BlockState state)
	{
		for (Direction dir : Direction.values())
		{
			BlockPos neighborPos = pos.relative(dir);
			state = state.updateShape(dir, PhysicsWorld.getBlockState(level, neighborPos), level, pos, neighborPos);
		}
		return state;
	}

	// 放置前预检：方块自身判定能否在当前世界存活；模组实现可能抛异常，异常视为可放置，不阻断放置
	public static boolean canSurviveAt(ServerLevel level, BlockPos pos, BlockState state)
	{
		try
		{
			return state.canSurvive(level, pos);
		}
		catch (Throwable t)
		{
			LOGGER.debug("NeoSim-PlacementSupport: canSurvive check failed at {} — {}", pos, t.toString());
			return true;
		}
	}

	// 双箱合并：相邻同朝向的 SINGLE 箱子互设 LEFT/RIGHT（纯 setBlock 不触发原版合并逻辑）
	public static void mergeDoubleChest(ServerLevel level, BlockPos pos, BlockState state)
	{
		if (!(state.getBlock() instanceof ChestBlock)) return;
		if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return;
		Direction facing = state.getValue(ChestBlock.FACING);
		for (int i = 0; i < 2; i++)
		{
			Direction dir = i == 0 ? facing.getClockWise() : facing.getCounterClockWise();
			BlockPos partnerPos = pos.relative(dir);
			BlockState partner = PhysicsWorld.getBlockState(level, partnerPos);
			if (partner.is(state.getBlock())
					&& partner.getValue(ChestBlock.TYPE) == ChestType.SINGLE
					&& partner.getValue(ChestBlock.FACING) == facing)
			{
				ChestType thisType = i == 0 ? ChestType.LEFT : ChestType.RIGHT;
				PhysicsWorld.setBlock(level, pos, state.setValue(ChestBlock.TYPE, thisType), 3);
				PhysicsWorld.setBlock(level, partnerPos,
						partner.setValue(ChestBlock.TYPE, thisType.getOpposite()), 3);
				return;
			}
		}
	}

	// 门放置后：补另一半（仅当那一格是空气），再按相邻门配对成双开门
	public static void completeDoor(ServerLevel level, BlockPos pos, BlockState placed)
	{
		if (!(placed.getBlock() instanceof DoorBlock)) return;
		DoubleBlockHalf half = placed.getValue(DoorBlock.HALF);
		if (half == DoubleBlockHalf.LOWER)
		{
			// 放下半格，补上半格
			BlockState upper = placed.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
			if (PhysicsWorld.getBlockState(level, pos.above()).isAir())
			{
				PhysicsWorld.setBlock(level, pos.above(), upper, Block.UPDATE_ALL);
			}
		}
		else if (PhysicsWorld.getBlockState(level, pos.below()).isAir())
		{
			// 上半格先：补下半格
			BlockState lower = placed.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			PhysicsWorld.setBlock(level, pos.below(), lower, Block.UPDATE_ALL);
		}
		fixDoubleDoor(level, pos);
	}

	// 床放置后：补另一半（优先用蓝图相邻床格定位，缺失时按 FACING 推算）
	public static void completeBed(ServerLevel level, BlockPos pos, BlockState placed, @Nullable BlockPos otherHalfHint)
	{
		if (!(placed.getBlock() instanceof BedBlock)) return;
		net.minecraft.world.level.block.state.properties.BedPart part = placed.getValue(BedBlock.PART);
		BlockPos other = otherHalfHint;
		if (other == null)
		{
			Direction facing = placed.getValue(BedBlock.FACING);
			other = part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
					? pos.relative(facing.getOpposite())
					: pos.relative(facing);
		}
		BlockState otherState = placed.setValue(BedBlock.PART,
				part == net.minecraft.world.level.block.state.properties.BedPart.HEAD
						? net.minecraft.world.level.block.state.properties.BedPart.FOOT
						: net.minecraft.world.level.block.state.properties.BedPart.HEAD);
		if (!PhysicsWorld.getBlockState(level, other).equals(otherState))
		{
			PhysicsWorld.setBlock(level, other, otherState, Block.UPDATE_ALL);
		}
	}

	// 双开门：以该位置所在的下半格为准，先看垂直方向相邻门（铰链取反），再看同朝向相邻门（重定向成对开门）
	public static void fixDoubleDoor(ServerLevel level, BlockPos doorPos)
	{
		// 一律以下半格为准
		BlockPos lowerPos = doorPos;
		BlockState lower = PhysicsWorld.getBlockState(level, lowerPos);
		if (!(lower.getBlock() instanceof DoorBlock))
		{
			LOGGER.info("NeoSim-PlacementSupport: fixDoubleDoor skip {} — not a door", lowerPos);
			return;
		}
		if (lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
		{
			lowerPos = lowerPos.below();
			lower = PhysicsWorld.getBlockState(level, lowerPos);
			if (!(lower.getBlock() instanceof DoorBlock)) return;
		}

		Direction facing = lower.getValue(BlockStateProperties.HORIZONTAL_FACING);
		LOGGER.info("NeoSim-PlacementSupport: fixDoubleDoor lower={} facing={} hinge={}",
				lowerPos, facing, lower.getValue(BlockStateProperties.DOOR_HINGE));

		// 门在朝向的垂直方向相邻、同朝向 -> 铰链取反
		for (Direction side : new Direction[] { facing.getCounterClockWise(), facing.getClockWise() })
		{
			BlockPos neighborPos = lowerPos.relative(side);
			BlockState neighbor = PhysicsWorld.getBlockState(level, neighborPos);
			LOGGER.debug("NeoSim-PlacementSupport: fixDoubleDoor side={} at {} → {}",
					side, neighborPos, neighbor.getBlock().getDescriptionId());
			if (neighbor.getBlock() instanceof DoorBlock
					&& neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing
					&& neighbor.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
			{
				DoorHingeSide neighborHinge = neighbor.getValue(BlockStateProperties.DOOR_HINGE);

				// 取反铰链
				DoorHingeSide opposite = neighborHinge == DoorHingeSide.LEFT
						? DoorHingeSide.RIGHT
						: DoorHingeSide.LEFT;
				LOGGER.debug("NeoSim-PlacementSupport: fixDoubleDoor pair with {} hinge={} → set {} to {}",
						neighborPos, neighborHinge, lowerPos, opposite);
				if (lower.getValue(BlockStateProperties.DOOR_HINGE) == opposite)
				{
					// 已配对
					return;
				}

				// 本门两格一起改铰链，保持上下一致
				PhysicsWorld.setBlock(level, lowerPos,
						lower.setValue(BlockStateProperties.DOOR_HINGE, opposite), Block.UPDATE_ALL);
				BlockState upper = PhysicsWorld.getBlockState(level, lowerPos.above());
				if (upper.getBlock() instanceof DoorBlock)
				{
					PhysicsWorld.setBlock(level, lowerPos.above(),
							upper.setValue(BlockStateProperties.DOOR_HINGE, opposite), Block.UPDATE_ALL);
				}
				return;
			}
		}

		// txt蓝图的门重定向成双开门
		for (Direction side : new Direction[] { facing, facing.getOpposite() })
		{
			BlockPos neighborPos = lowerPos.relative(side);
			BlockState neighbor = PhysicsWorld.getBlockState(level, neighborPos);
			if (neighbor.getBlock() instanceof DoorBlock
					&& neighbor.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing
					&& neighbor.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
			{
				orientDoubleDoorPair(level, lowerPos, neighborPos, side);
				return;
			}
		}
	}

	// 把同朝向、沿朝向轴相邻的两扇门重定向成真正对开门
	private static void orientDoubleDoorPair(ServerLevel level, BlockPos posA, BlockPos posB, Direction side)
	{
		BlockState lowerA = PhysicsWorld.getBlockState(level, posA);
		Direction orig = lowerA.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction target = pickDoubleDoorFacing(level, posA, posB, orig);

		// 铰链朝外侧
		DoorHingeSide hingeA = side == target.getCounterClockWise() ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
		DoorHingeSide hingeB = hingeA == DoorHingeSide.LEFT ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;

		LOGGER.info("NeoSim-PlacementSupport: orientDoubleDoor {} + {} → facing={} hinge={}/{}",
				posA, posB, target, hingeA, hingeB);
		setDoorFacingHinge(level, posA, target, hingeA);
		setDoorFacingHinge(level, posB, target, hingeB);
	}

	// 目标朝向：垂直于邻接轴，优先选正前方开阔的一侧
	private static Direction pickDoubleDoorFacing(ServerLevel level, BlockPos posA, BlockPos posB, Direction orig)
	{
		Direction a = orig.getClockWise();
		Direction b = orig.getCounterClockWise();
		int solidsA = solidsInFront(level, posA, posB, a);
		int solidsB = solidsInFront(level, posA, posB, b);
		if (solidsA != solidsB)
		{
			return solidsA < solidsB ? a : b;
		}

		// 平局：取逆时针候选
		return b;
	}

	private static int solidsInFront(ServerLevel level, BlockPos posA, BlockPos posB, Direction dir)
	{
		int n = 0;
		if (!PhysicsWorld.getBlockState(level, posA.relative(dir)).isAir()) n++;
		if (!PhysicsWorld.getBlockState(level, posB.relative(dir)).isAir()) n++;
		return n;
	}

	// 改一扇门的朝向与铰链（上下两格一起改）
	private static void setDoorFacingHinge(ServerLevel level, BlockPos pos, Direction facing, DoorHingeSide hinge)
	{
		BlockState lower = PhysicsWorld.getBlockState(level, pos);
		if (!(lower.getBlock() instanceof DoorBlock)) return;
		PhysicsWorld.setBlock(level, pos, lower.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
				.setValue(BlockStateProperties.DOOR_HINGE, hinge), Block.UPDATE_ALL);
		BlockState upper = PhysicsWorld.getBlockState(level, pos.above());
		if (upper.getBlock() instanceof DoorBlock)
		{
			PhysicsWorld.setBlock(level, pos.above(), upper.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
					.setValue(BlockStateProperties.DOOR_HINGE, hinge), Block.UPDATE_ALL);
		}
	}

	// 贴墙方块的朝向属性：先按数据规则的 facing 名称匹配，再回退原版水平朝向
	private static Property<Direction> facingProperty(BlockState state)
	{
		for (String name : AttachedBlockTable.facingProperties(state.getBlock()))
		{
			for (Property<?> prop : state.getProperties())
			{
				if (prop.getName().equalsIgnoreCase(name) && prop.getValueClass() == Direction.class)
				{
					@SuppressWarnings("unchecked")
					Property<Direction> dirProp = (Property<Direction>) prop;
					return dirProp;
				}
			}
		}
		return null;
	}

	private static boolean hasSupport(ServerLevel level, BlockPos pos, Direction dir)
	{
		return !PhysicsWorld.getBlockState(level, pos.relative(dir)).isAir();
	}

	// 方向属性
	private static net.minecraft.world.level.block.state.properties.BooleanProperty vineProperty(Direction d)
	{
		return switch (d)
		{
			case NORTH -> VineBlock.NORTH;
			case SOUTH -> VineBlock.SOUTH;
			case EAST -> VineBlock.EAST;
			default -> VineBlock.WEST;
		};
	}
}
