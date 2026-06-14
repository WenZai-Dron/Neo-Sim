package com.wenzai.neosim;

import com.wenzai.neosim.block.ModBlocks;
import com.wenzai.neosim.gui.HUD;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(NeoSim.MOD_ID)
public class NeoSim
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "neo_sim";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // 用于day++和dayOfWeek++
    private long lastDayTime = -1;

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public NeoSim(IEventBus modEventBus, ModContainer modContainer)
    {
        // Register the commonSetup method for mod-loading
        modEventBus.addListener(this::commonSetup);

        // 此处进行注册
        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        CreativeModeTabs.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (NeoSim) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // 注册命令
        NeoForge.EVENT_BUS.register(Command.class);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 注册HUD
        if (FMLEnvironment.dist == Dist.CLIENT)
        {
            NeoForge.EVENT_BUS.register(new HUD());
        }

        // 注册网络包
        modEventBus.addListener(this::registerPayloads);
    }

    // 玩家加入自动同步数据，触发Run选择界面
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            ModSavedData data = ModSavedData.get(player.serverLevel());
            SyncDataPayload payload = new SyncDataPayload(data.getData());
            PacketDistributor.sendToPlayer(player, payload);

            // 客户端打开Run
            if (!data.isRunGuiSent() && data.getMode() == 0)
            {
                data.setRunGuiSent(true);
                PacketDistributor.sendToPlayer(player, new OpenRunGuiPayload());
                NeoSim.LOGGER.info("NeoSim-isRunGuiSent(): Succeed");
            }
        }
    }

    // day++和dayOfWeek++
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event)
    {
        ServerLevel level = event.getServer().overworld();
        long dayTime = level.getDayTime();
        long timeOfDay = dayTime % 24000;

        // 首次初始化
        if (lastDayTime == -1)
        {
            lastDayTime = dayTime;
            NeoSim.LOGGER.info("NeoSim: dayTime={}, timeOfDay={}", dayTime, timeOfDay);
            return;
        }

        long lastTimeOfDay = lastDayTime % 24000;
        if (dayTime > lastDayTime && timeOfDay < lastTimeOfDay)
        {
            ModSavedData data = ModSavedData.get(level);
            data.incrementDay(level);
            level.setDayTime(0);
            NeoSim.LOGGER.info("NeoSim: day={}, dayOfWeek={}", data.getDay(), data.getDayOfWeek());
        }
        lastDayTime = dayTime;
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event)
    {
        PayloadRegistrar registrar = event.registrar(MOD_ID).versioned("1.0");

        // 服务端→客户端
        registrar.playToClient(
                SyncDataPayload.TYPE,
                SyncDataPayload.STREAM_CODEC,
                SyncDataPayload::handle
        );

        // 服务端→客户端：通知打开Run
        registrar.playToClient(
                OpenRunGuiPayload.TYPE,
                OpenRunGuiPayload.STREAM_CODEC,
                OpenRunGuiPayload::handle
        );

        // 客户端→服务端
        registrar.playToServer(
                UpdatePayload.TYPE,
                UpdatePayload.STREAM_CODEC,
                UpdatePayload::handle
        );
    }

    private void commonSetup(FMLCommonSetupEvent event)
    {
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean())
        {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
