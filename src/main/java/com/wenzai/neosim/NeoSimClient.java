package com.wenzai.neosim;

import com.wenzai.neosim.gui.City;
import com.wenzai.neosim.gui.NPC;
import com.wenzai.neosim.gui.Run;
import com.wenzai.neosim.npc.Entity;

import com.wenzai.neosim.storage.ClientDataHolder;
import com.wenzai.neosim.storage.FreezeNpcPayload;
import com.wenzai.neosim.storage.OpenGuiPayload;
import com.wenzai.neosim.npc.Model;
import com.wenzai.neosim.npc.Renderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = NeoSim.MOD_ID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class NeoSimClient
{
    private static int openGuiTimer = -1;
    private static OpenGuiPayload.GuiType pendingGuiType = null;
    
    // 检测存档切换，用于重置客户端缓存数据
    private static ClientLevel lastLevel = null;

    public NeoSimClient(ModContainer container)
    {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event)
    {
        // Some client setup code
        NeoSim.LOGGER.info("HELLO FROM CLIENT SETUP");
        NeoSim.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    // 注册实体渲染器
    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(Entity.NPC.get(), Renderer::new);
    }

    // 注册模型（宽臂和细臂）
    @SubscribeEvent
    static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event)
    {
        event.registerLayerDefinition(Renderer.LAYER, Model::createBodyLayer);
        event.registerLayerDefinition(Renderer.SLIM_LAYER, Model::createSlimBodyLayer);
    }

    public static void scheduleOpenGui(OpenGuiPayload.GuiType guiType)
    {
        openGuiTimer = 200;
        pendingGuiType = guiType;
    }

    public static int getOpenGuiTimer()
    {
        return openGuiTimer;
    }

    @SubscribeEvent
    static void onKeyInput(InputEvent.Key event)
    {
        if (event.getKey() == GLFW.GLFW_KEY_GRAVE_ACCENT && event.getAction() == GLFW.GLFW_PRESS)
        {
            if (openGuiTimer > 0)
            {
                openGuiTimer = Math.max(1, openGuiTimer - 20);
            }
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event)
    {
        // 检测存档切换：level引用变化时重置客户端缓存
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel)
        {
            lastLevel = mc.level;
            if (mc.level == null)
            {
                ClientDataHolder.getInstance().reset();
                Model.clearCache();
            }
        }

        if (openGuiTimer > 0)
        {
            openGuiTimer--;
            if (openGuiTimer == 0)
            {
                if (mc.player != null && mc.level != null && pendingGuiType != null)
                {
                    switch (pendingGuiType)
                    {
                        case RUN -> mc.setScreen(new Run());
                        case CITY -> mc.setScreen(new City());
                    }
                }
                openGuiTimer = -1;
                pendingGuiType = null;
            }
        }
    }

    // 右键NPC时打开GUI，同时发送冻结包
    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getTarget() instanceof Entity npc)
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null)
            {
                mc.execute(() -> mc.setScreen(new NPC(npc)));
                PacketDistributor.sendToServer(new FreezeNpcPayload(npc.getId(), true));
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
        }
    }
}
