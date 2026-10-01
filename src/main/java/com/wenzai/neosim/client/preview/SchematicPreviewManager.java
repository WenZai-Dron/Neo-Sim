package com.wenzai.neosim.client.preview;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.schematic.SchematicData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;

// 预览管理
public class SchematicPreviewManager
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final SchematicPreviewManager INSTANCE = new SchematicPreviewManager();
	private final ClientPreviewState state = new ClientPreviewState();
	private BlockPos constructorPos;

	private SchematicPreviewManager()
	{
	}

	public static SchematicPreviewManager getInstance()
	{
		return INSTANCE;
	}

	public ClientPreviewState getState()
	{
		return state;
	}

	public BlockPos getConstructorPos()
	{
		return constructorPos;
	}

	// 进入预览模式
	public void enterPreview(SchematicData schematic, BlockPos constructorPos)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;

		// 控制盒在建筑前角，建筑沿玩家面朝方向延伸
		Direction facing = mc.player.getDirection();
		int ox = constructorPos.getX();
		int oy = constructorPos.getY();
		int oz = constructorPos.getZ();

		// 起点＝模盒沿玩家面朝方向的相邻一格（与原版 Sim-U-Kraft 的"站在模盒某一侧"一致）
		switch (facing)
		{
			case SOUTH -> oz = oz + 1;
			case NORTH -> oz = oz - 1;
			case EAST  -> ox = ox + 1;
			case WEST  -> ox = ox - 1;
			default -> {}
		}

		this.constructorPos = constructorPos;

		// state 是单例字段，跨蓝图复用：不清掉上一次预览残留的旋转/镜像，新预览会一直带着转
		state.setRotation(Rotation.NONE);
		state.setMirror(Mirror.NONE);
		state.setSchematic(schematic);
		state.setFacing(facing);
		state.setOrigin(new BlockPos(ox, oy, oz));
		state.setActive(true);

		// 诊断：把这次预览用的映射结果打出来，便于与投影的粘贴范围对照
		BlockPos c0 = state.blueprintToWorld(0, 0, 0);
		BlockPos c1 = state.blueprintToWorld(
				schematic.getSizeX() - 1, schematic.getSizeY() - 1, schematic.getSizeZ() - 1);
		LOGGER.info("NeoSim-Preview: '{}' format={} frame={} size={}x{}x{} facing={} rot={} mirror={} origin={} span={}..{}",
				schematic.getName(), schematic.getFormat(), state.frame(),
				schematic.getSizeX(), schematic.getSizeY(), schematic.getSizeZ(),
				state.getFacing(), state.getRotation(), state.getMirror(),
				state.getOrigin(), c0, c1);
	}

	// 确认放置并创建建造任务
	public void confirmPlacement()
	{
		Minecraft mc = Minecraft.getInstance();
		if (state.getSchematic() != null)
		{
			if (mc.hasSingleplayerServer())
			{
				ServerLevel level = mc.getSingleplayerServer().overworld();

				// 整地进行中：拒绝建造（与建造任务互斥）
				if (com.wenzai.neosim.block.TerraformEngine.findTask(constructorPos) != null)
				{
					if (mc.player != null)
					{
						mc.player.displayClientMessage(
								net.minecraft.network.chat.Component.translatable(
										"msg.neosim.terraform.running"), false);
					}
					return;
				}

				com.wenzai.neosim.building.BuildingInstance building =
						com.wenzai.neosim.building.ConstructionEngine.createBuilding(
								state.getSchematic(), state, level,
								mc.player != null ? mc.player.getName().getString() : null,
								constructorPos);
				if (building == null)
				{
					// 区域与已有建筑重叠：提示并保持预览激活
					if (mc.player != null)
					{
						mc.player.displayClientMessage(
								net.minecraft.network.chat.Component.translatable(
										"msg.neosim.preview.overlap"), false);
					}
					return;
				}
			}
			else
			{
				net.neoforged.neoforge.network.PacketDistributor.sendToServer(
						new com.wenzai.neosim.network.ClientToServerPayloads.ConfirmPlacementPayload(
								state.getSchematic().getName(),
								state.getOrigin(),
								state.getRotation(),
								state.getMirror(),
								constructorPos,
								state.getFacing()));
			}
		}
		state.setActive(false);
		constructorPos = null;
		FreeCamera.exit();
	}

	public void cancelPreview()
	{
		state.setActive(false);
		constructorPos = null;
		FreeCamera.exit();
	}
}
