package com.wenzai.neosim.client.preview;

import com.wenzai.neosim.schematic.SchematicData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

// 预览管理
public class SchematicPreviewManager
{
    private static final SchematicPreviewManager INSTANCE = new SchematicPreviewManager();
    private final PreviewState state = new PreviewState();
    private BlockPos constructorPos;

    private SchematicPreviewManager() {}

    public static SchematicPreviewManager getInstance() { return INSTANCE; }
    public PreviewState getState() { return state; }
    public BlockPos getConstructorPos() { return constructorPos; }

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

        switch (facing)
        {
            case SOUTH -> oz = oz + 1;
            case NORTH -> oz = oz - 1;
            case EAST  -> ox = ox + 1;
            case WEST  -> ox = ox - 1;
            default -> {}
        }

        this.constructorPos = constructorPos;
        state.setSchematic(schematic);
        state.setFacing(facing);
        state.setOrigin(new BlockPos(ox, oy, oz));
        state.setActive(true);
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
                                net.minecraft.network.chat.Component.literal(
                                        "§cCannot place: area overlaps an existing building."), false);
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
