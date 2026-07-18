package com.wenzai.neosim;

import com.wenzai.neosim.gui.City;
import com.wenzai.neosim.gui.NPC;
import com.wenzai.neosim.gui.Run;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Model;
import com.wenzai.neosim.npc.Renderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = NeoSim.MOD_ID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class NeoSimClient
{
    private static int openRunGuiTimer = -1;
    private static int openCityGuiTimer = -1;

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

    // 注册模型
    @SubscribeEvent
    static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event)
    {
        event.registerLayerDefinition(Renderer.LAYER, Model::createBodyLayer);
    }

    public static void scheduleOpenRunGui()
    {
        openRunGuiTimer = 200; // 20 ticks/s * 10s = 200 ticks
    }

    public static void scheduleOpenCityGui()
    {
        openCityGuiTimer = 200; // 20 ticks/s * 10s = 200 ticks
    }

    public static int getOpenRunGuiTimer()
    {
        return openRunGuiTimer;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event)
    {
        if (openRunGuiTimer > 0)
        {
            openRunGuiTimer--;
            if (openRunGuiTimer == 0)
            {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.level != null)
                {
                    mc.setScreen(new Run());
                }
                openRunGuiTimer = -1;
            }
        }

        if (openCityGuiTimer > 0)
        {
            openCityGuiTimer--;
            if (openCityGuiTimer == 0)
            {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.level != null)
                {
                    mc.setScreen(new City());
                }
                openCityGuiTimer = -1;
            }
        }
    }

    // 右键NPC时打开GUI
    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getTarget() instanceof Entity npc)
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null)
            {
                mc.setScreen(new NPC(npc));
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
        }
    }
}
