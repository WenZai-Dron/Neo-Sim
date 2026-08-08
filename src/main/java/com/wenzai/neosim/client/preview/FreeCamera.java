package com.wenzai.neosim.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

// 开启创造飞行，退出时传送回原位。
public class FreeCamera
{
    private static Vec3 savedPos;
    private static float savedYaw, savedPitch;
    private static boolean wasFlying, wasMayFly, wasInvulnerable;
    private static Object hudListener;
    private static boolean active;

    public static boolean isActive() { return active; }

    public static void enter()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        savedPos = mc.player.position();
        savedYaw = mc.player.getYRot();
        savedPitch = mc.player.getXRot();
        wasFlying = mc.player.getAbilities().flying;
        wasMayFly = mc.player.getAbilities().mayfly;
        wasInvulnerable = mc.player.getAbilities().invulnerable;

        mc.player.getAbilities().mayfly = true;
        mc.player.getAbilities().flying = true;
        
        // 灵魂出窍期间玩家无敌
        mc.player.getAbilities().invulnerable = true;
        mc.player.onUpdateAbilities();

        // 注册HUD隐藏
        hudListener = new Object()
        {
            @net.neoforged.bus.api.SubscribeEvent
            public void onHud(net.neoforged.neoforge.client.event.RenderGuiEvent.Pre e)
            {
                FreeCamera.onRenderHud(e);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(hudListener);

        active = true;
    }

    public static void exit()
    {
        if (!active) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.getAbilities().mayfly = wasMayFly;
        mc.player.getAbilities().flying = wasFlying;
        mc.player.getAbilities().invulnerable = wasInvulnerable;
        mc.player.onUpdateAbilities();

        if (savedPos != null)
        {
            mc.player.setPos(savedPos.x, savedPos.y, savedPos.z);
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
            mc.player.setDeltaMovement(Vec3.ZERO);

            // 同步服务端位置
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                    savedPos.x, savedPos.y, savedPos.z, savedYaw, savedPitch, true));
        }

        if (hudListener != null)
        {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(hudListener);
            hudListener = null;
        }
        savedPos = null;
        active = false;
    }

    // 隐藏HUD
    static void onRenderHud(net.neoforged.neoforge.client.event.RenderGuiEvent.Pre event)
    {
        if (active)
        {
            event.setCanceled(true);
        }
    }

    // ESC返回预览GUI
    public static void returnToGui()
    {
        exit();
        SchematicPreviewManager mgr = SchematicPreviewManager.getInstance();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mgr.getState().getSchematic() != null)
        {
            mc.setScreen(new com.wenzai.neosim.client.gui.PreviewAdjustGui(
                    mgr.getState().getSchematic(), mgr.getConstructorPos()));
        }
    }
}
