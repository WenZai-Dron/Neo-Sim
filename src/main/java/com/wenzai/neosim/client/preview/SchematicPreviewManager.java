package com.wenzai.neosim.client.preview;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.TerraformEngine;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.network.ClientToServerPayloads.ConfirmPlacementPayload;
import com.wenzai.neosim.schematic.PreviewState;
import com.wenzai.neosim.schematic.SchematicData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

// 预览管理：本类同时就是客户端预览状态（在 common PreviewState 之上附加 VBO 网格缓存）
public class SchematicPreviewManager extends PreviewState
{
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final SchematicPreviewManager INSTANCE = new SchematicPreviewManager();
	private BlockPos constructorPos;

	// 幽灵预览的 VBO 网格缓存（GPU 资源，预览结束即释放）
	private GhostBlockRenderer.GhostMeshCache meshCache;

	private SchematicPreviewManager()
	{
	}

	public static SchematicPreviewManager getInstance()
	{
		return INSTANCE;
	}

	public SchematicPreviewManager getState()
	{
		return this;
	}

	public BlockPos getConstructorPos()
	{
		return constructorPos;
	}

	// 仅客户端渲染路径调用
	public GhostBlockRenderer.GhostMeshCache getMeshCache()
	{
		if (meshCache == null) meshCache = new GhostBlockRenderer.GhostMeshCache();
		return meshCache;
	}

	@Override
	public void setActive(boolean v)
	{
		super.setActive(v);

		// 预览结束：释放缓存的GPU显存
		if (!v && meshCache != null) meshCache.invalidate();
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

		// 预览状态跨蓝图复用：不清掉上一次残留的旋转/镜像，新预览会一直带着转
		setRotation(Rotation.NONE);
		setMirror(Mirror.NONE);
		setSchematic(schematic);
		setFacing(facing);
		setOrigin(new BlockPos(ox, oy, oz));
		setActive(true);

		// 诊断：把这次预览用的映射结果打出来，便于与投影的粘贴范围对照
		BlockPos c0 = blueprintToWorld(0, 0, 0);
		BlockPos c1 = blueprintToWorld(
				schematic.getSizeX() - 1, schematic.getSizeY() - 1, schematic.getSizeZ() - 1);
		LOGGER.info("NeoSim-Preview: '{}' format={} frame={} size={}x{}x{} facing={} rot={} mirror={} origin={} span={}..{}",
				schematic.getName(), schematic.getFormat(), frame(),
				schematic.getSizeX(), schematic.getSizeY(), schematic.getSizeZ(),
				getFacing(), getRotation(), getMirror(),
				getOrigin(), c0, c1);
	}

	// 确认放置并创建建造任务
	public void confirmPlacement()
	{
		Minecraft mc = Minecraft.getInstance();
		if (getSchematic() != null)
		{
			if (mc.hasSingleplayerServer())
			{
				ServerLevel level = mc.getSingleplayerServer().overworld();

				// 整地进行中：拒绝建造（与建造任务互斥）
				if (TerraformEngine.findTask(constructorPos) != null)
				{
					if (mc.player != null)
					{
						mc.player.displayClientMessage(Component.translatable("msg.neosim.terraform.running"), false);
					}
					return;
				}

				BuildingInstance building = ConstructionEngine.createBuilding(
						getSchematic(), this, level,
						mc.player != null ? mc.player.getName().getString() : null,
						constructorPos);
				if (building == null)
				{
					// 区域与已有建筑重叠：提示并保持预览激活
					if (mc.player != null)
					{
						mc.player.displayClientMessage(Component.translatable("msg.neosim.preview.overlap"), false);
					}
					return;
				}
			}
			else
			{
				PacketDistributor.sendToServer(new ConfirmPlacementPayload(
						getSchematic().getName(),
						getOrigin(),
						getRotation(),
						getMirror(),
						constructorPos,
						getFacing()));
			}
		}
		setActive(false);
		constructorPos = null;
		FreeCamera.exit();
	}

	public void cancelPreview()
	{
		setActive(false);
		constructorPos = null;
		FreeCamera.exit();
	}
}
