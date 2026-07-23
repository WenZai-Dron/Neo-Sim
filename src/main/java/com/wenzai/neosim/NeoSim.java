package com.wenzai.neosim;

import com.wenzai.neosim.block.ModBlocks;
import com.wenzai.neosim.gui.HUD;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.FreezeNpcPayload;
import com.wenzai.neosim.storage.OpenGuiPayload;
import com.wenzai.neosim.storage.SyncDataPayload;
import com.wenzai.neosim.storage.UpdatePayload;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.world.phys.AABB;
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
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

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
        Entity.register(modEventBus);
        CreativeModeTabs.register(modEventBus);

        // 注册实体属性
        modEventBus.addListener(this::registerEntityAttributes);

        // Register ourselves for server and other game events we are interested in.
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

    // 玩家加入自动同步数据，触发对应界面
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            ModSavedData data = ModSavedData.get(player.serverLevel());
            String playerName = player.getName().getString();
            String cityName = ModSavedData.getActiveCityName();

            // 根据player.json查找玩家所属城市
            if (cityName.isEmpty())
            {
                boolean isDedicated = player.serverLevel().getServer().isDedicatedServer();
                if (isDedicated)
                {
                    cityName = FileCreater.findPlayerCity(playerName);
                }
                else
                {
                    String saveName = player.serverLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
                    cityName = FileCreater.findPlayerCity(saveName, playerName);
                }
                if (!cityName.isEmpty())
                {
                    ModSavedData.setActiveCityName(cityName);
                    NeoSim.LOGGER.info("NeoSim-onPlayerJoin: found city {} for player {}", cityName, playerName);
                }
            }

            // 只有player.json中含有的玩家才能读取data.json
            if (!cityName.isEmpty())
            {
                boolean isDedicated = player.serverLevel().getServer().isDedicatedServer();
                boolean authorized;
                if (isDedicated)
                {
                    authorized = FileCreater.isPlayerInCity(cityName, playerName);
                }
                else
                {
                    String saveName = player.serverLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
                    authorized = FileCreater.isPlayerInCity(cityName, saveName, playerName);
                }
                if (authorized)
                {
                    SyncDataPayload payload = new SyncDataPayload(data.getData(), cityName);
                    PacketDistributor.sendToPlayer(player, payload);
                }
                else
                {
                    NeoSim.LOGGER.info("NeoSim-onPlayerJoin: {} not authorized for city {}", playerName, cityName);
                }
            }
            else
            {
                // 无城市时正常同步
                SyncDataPayload payload = new SyncDataPayload(data.getData(), "");
                PacketDistributor.sendToPlayer(player, payload);
            }

            // 第一个玩家第一次进入，打开Run
            if (!data.isRunGuiSent() && data.getMode() == 0)
            {
                data.setRunGuiSent(true);
                data.markPlayerJoined(player.getUUID());
                PacketDistributor.sendToPlayer(player, new OpenGuiPayload(OpenGuiPayload.GuiType.RUN));
                NeoSim.LOGGER.info("NeoSim-onPlayerJoin: open Run for {}", playerName);
            }

            // 其他玩家第一次进入，打开City
            else if (!data.isPlayerJoined(player.getUUID()))
            {
                data.markPlayerJoined(player.getUUID());
                PacketDistributor.sendToPlayer(player, new OpenGuiPayload(OpenGuiPayload.GuiType.CITY));
                NeoSim.LOGGER.info("NeoSim-onPlayerJoin: open City for {}", playerName);
            }
        }
    }

    // 玩家断线时清理其打开的NPC-GUI，防止NPC永久冻结
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            java.util.UUID playerUUID = player.getUUID();
            
            // 遍历玩家所在维度的所有NPC，移除该玩家的GUI引用
            for (Entity npc : player.serverLevel().getEntitiesOfClass(Entity.class,
                    new AABB(player.blockPosition()).inflate(256.0)))
            {
                npc.unfreezeBy(playerUUID);
            }
            NeoSim.LOGGER.debug("NeoSim-onPlayerLogout: cleaned up GUI refs for player={}", playerUUID);
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

        // 服务端→客户端：通知打开Run或City
        registrar.playToClient(
                OpenGuiPayload.TYPE,
                OpenGuiPayload.STREAM_CODEC,
                OpenGuiPayload::handle
        );

        // 客户端→服务端
        registrar.playToServer(
                UpdatePayload.TYPE,
                UpdatePayload.STREAM_CODEC,
                UpdatePayload::handle
        );

        // 客户端→服务端：冻结/解冻NPC
        registrar.playToServer(
                FreezeNpcPayload.TYPE,
                FreezeNpcPayload.STREAM_CODEC,
                FreezeNpcPayload::handle
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

    private void registerEntityAttributes(EntityAttributeCreationEvent event)
    {
        event.put(Entity.NPC.get(), Entity.createAttributes().build());
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    // 服务端停止时重置静态变量，防止下一个存档读到残留数据
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        ModSavedData.setActiveCityName("");
        LOGGER.info("NeoSim: activeCityName reset on server stopping");
    }
}
