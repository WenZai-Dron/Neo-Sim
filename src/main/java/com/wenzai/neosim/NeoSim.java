package com.wenzai.neosim;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.ModBlocks;
import com.wenzai.neosim.client.gui.HUD;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.network.ServerToClientPayloads;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@Mod(NeoSim.MOD_ID)
public class NeoSim
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "neo_sim";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 共享工作分配表
    public static final java.util.concurrent.ConcurrentHashMap<net.minecraft.core.BlockPos, String> WORKER_MAP = new java.util.concurrent.ConcurrentHashMap<>();

    // 用于day++和dayOfWeek++
    private long lastDayTime = -1;

    // 用于定期清理因玩家非正常退出（崩溃、断线）而永久冻结的NPC
    private int frozenCleanupTimer = 0;

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
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "neo-sim.toml");

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
                    ServerToClientPayloads.SyncDataPayload payload = new ServerToClientPayloads.SyncDataPayload(data.getData(), cityName);
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
                ServerToClientPayloads.SyncDataPayload payload = new ServerToClientPayloads.SyncDataPayload(data.getData(), "");
                PacketDistributor.sendToPlayer(player, payload);
            }

            // 第一个玩家第一次进入，打开Run
            if (!data.isRunGuiSent() && data.getMode() == 0)
            {
                data.setRunGuiSent(true);
                data.markPlayerJoined(player.getUUID());
                PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.OpenGuiPayload(ServerToClientPayloads.OpenGuiPayload.GuiType.RUN));
                NeoSim.LOGGER.info("NeoSim-onPlayerJoin: open Run for {}", playerName);
            }

            // 其他玩家第一次进入，打开City
            else if (!data.isPlayerJoined(player.getUUID()))
            {
                data.markPlayerJoined(player.getUUID());
                PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.OpenGuiPayload(ServerToClientPayloads.OpenGuiPayload.GuiType.CITY));
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

            // 遍历所有维度的所有已加载NPC，确保每个被该玩家冻结的NPC都被解冻
            if (player.getServer() != null)
            {
                for (ServerLevel serverLevel : player.getServer().getAllLevels())
                {
                    for (Entity npc : serverLevel.getEntitiesOfClass(Entity.class,
                            new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                                     Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)))
                    {
                        npc.unfreezeBy(playerUUID);
                    }
                }
            }
            NeoSim.LOGGER.debug("NeoSim-onPlayerLogout: cleaned up GUI refs for player={}", playerUUID);
        }
    }

    // day++和dayOfWeek++
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event)
    {
        // 定期清理因玩家非正常退出（崩溃、断线）而永久冻结的NPC
        frozenCleanupTimer++;
        if (frozenCleanupTimer >= 200)
        {
            frozenCleanupTimer = 0;
            for (ServerLevel serverLevel : event.getServer().getAllLevels())
            {
                for (Entity npc : serverLevel.getEntitiesOfClass(Entity.class,
                        new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                                 Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)))
                {
                    npc.cleanupStaleOpeners();
                }
            }
        }

        ServerLevel level = event.getServer().overworld();

        // 生活系统
        LifeSystem.onServerTick(level);

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
            LifeSystem.onDayStart(level, data.getDayOfWeek());   // 生活系统每日结算入口（Phase 5+）
            level.setDayTime(0);
            NeoSim.LOGGER.info("NeoSim: day={}, dayOfWeek={}", data.getDay(), data.getDayOfWeek());
        }
        lastDayTime = dayTime;
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event)
    {
        PayloadRegistrar registrar = event.registrar(MOD_ID).versioned("1.0");

        registrar.playToClient(
                ServerToClientPayloads.SyncDataPayload.TYPE,
                ServerToClientPayloads.SyncDataPayload.STREAM_CODEC,
                ServerToClientPayloads.SyncDataPayload::handle
        );

        // 材料短缺/完工通知
        registrar.playToClient(
                ServerToClientPayloads.ResourceShortagePacket.TYPE,
                ServerToClientPayloads.ResourceShortagePacket.STREAM_CODEC,
                ServerToClientPayloads.ResourceShortagePacket::handle
        );
        registrar.playToClient(
                ServerToClientPayloads.BuildingCompletePacket.TYPE,
                ServerToClientPayloads.BuildingCompletePacket.STREAM_CODEC,
                ServerToClientPayloads.BuildingCompletePacket::handle
        );

        // 通知打开Run或City
        registrar.playToClient(
                ServerToClientPayloads.OpenGuiPayload.TYPE,
                ServerToClientPayloads.OpenGuiPayload.STREAM_CODEC,
                ServerToClientPayloads.OpenGuiPayload::handle
        );

        registrar.playToServer(
                ClientToServerPayloads.UpdatePayload.TYPE,
                ClientToServerPayloads.UpdatePayload.STREAM_CODEC,
                ClientToServerPayloads.UpdatePayload::handle
        );

        // 冻结/解冻NPC
        registrar.playToServer(
                ClientToServerPayloads.FreezeNpcPayload.TYPE,
                ClientToServerPayloads.FreezeNpcPayload.STREAM_CODEC,
                ClientToServerPayloads.FreezeNpcPayload::handle
        );

        // 确认建筑放置
        registrar.playToServer(
                ClientToServerPayloads.ConfirmPlacementPayload.TYPE,
                ClientToServerPayloads.ConfirmPlacementPayload.STREAM_CODEC,
                ClientToServerPayloads.ConfirmPlacementPayload::handle
        );

    }

    private void commonSetup(FMLCommonSetupEvent event)
    {
        LOGGER.info("TOML: initialCredit={}", Config.INITIAL_CREDIT.get());
        LOGGER.info("maxPopulation={}", Config.MAX_POPULATION.get());

        LOGGER.info("npcAgeRange=[{}, {}]", Config.NPC_MIN_AGE.get(), Config.NPC_MAX_AGE.get());
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
