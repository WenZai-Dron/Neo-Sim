package com.wenzai.neosim;

import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.MarkerManager;
import com.wenzai.neosim.block.ModBlocks;
import com.wenzai.neosim.client.gui.HUD;
import com.wenzai.neosim.compat.sable.PhysicsAdapterRegistry;
import com.wenzai.neosim.life.LifeSystem;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.network.ServerToClientPayloads;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.CityManager;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.level.LevelEvent;
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
	// 在公共位置定义 mod id，供所有地方引用
	public static final String MOD_ID = "neo_sim";
	public static final Logger LOGGER = LogUtils.getLogger();

	// 共享工作分配表
	public static final java.util.concurrent.ConcurrentHashMap<net.minecraft.core.BlockPos, String> WORKER_MAP = new java.util.concurrent.ConcurrentHashMap<>();

	// 用于day++和dayOfWeek++
	private long lastDayTime = -1;

	// 用于定期清理因玩家非正常退出（崩溃、断线）而永久冻结的NPC
	private int frozenCleanupTimer = 0;

	// 持久化合并窗口 flush 计时（每 100 tick = 5 秒：CityData 缓存 + NPC 写盘去抖）
	private int persistFlushTimer = 0;

	// 模组类的构造方法是模组加载时最先运行的代码。
	// FML 会识别一些参数类型（如 IEventBus 或 ModContainer）并自动传入。
	public NeoSim(IEventBus modEventBus, ModContainer modContainer)
	{
		// 注册用于模组加载的 commonSetup 方法
		modEventBus.addListener(this::commonSetup);

		// 注册物理模组适配器（Sable 等）：仅加载已安装模组的适配器，保持零硬依赖
		PhysicsAdapterRegistry.init();

		// 此处进行注册
		ModItems.register(modEventBus);
		ModBlocks.register(modEventBus);
		Entity.register(modEventBus);
		CreativeModeTabs.register(modEventBus);

		// 注册实体属性
		modEventBus.addListener(this::registerEntityAttributes);

		// 将自身注册到服务器及其他感兴趣的游戏事件。
		NeoForge.EVENT_BUS.register(this);

		// 注册命令
		NeoForge.EVENT_BUS.register(Command.class);

		// 将物品注册到创造模式标签页
		modEventBus.addListener(this::addCreative);

		// 注册本模组的 ModConfigSpec，让 FML 能创建并加载配置文件
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
			try
			{
				handlePlayerJoin(player);
			}
			catch (Exception e)
			{
				// 文件被删改导致的任何遗漏异常都不允许阻止玩家登录
				NeoSim.LOGGER.error("NeoSim-onPlayerJoin: unhandled error for {}, skipped", player.getName().getString(), e);
			}
		}
	}

	private void handlePlayerJoin(ServerPlayer player)
	{
		ModSavedData data = ModSavedData.get(player.serverLevel());
		String playerName = player.getName().getString();

		// 会话登记（服务端权威，按玩家档案解析城市）
		CityManager.onPlayerJoin(player.serverLevel(), player);
		String cityName = CityManager.getCity(player.getUUID());

		// 只有 player.json 中含有的玩家才能读取 data.json
		if (!cityName.isEmpty())
		{
			boolean authorized = FileCreater.isPlayerInCity(player.serverLevel(), cityName, playerName);
			if (authorized)
			{
				ServerToClientPayloads.SyncDataPayload payload =
						new ServerToClientPayloads.SyncDataPayload(data.getData(cityName), cityName);
				PacketDistributor.sendToPlayer(player, payload);
			}
			else
			{
				NeoSim.LOGGER.info("NeoSim-onPlayerJoin: {} not authorized for city {}", playerName, cityName);
			}
		}
		else
		{
			// 无城市时正常同步（空城市名）
			ServerToClientPayloads.SyncDataPayload payload =
					new ServerToClientPayloads.SyncDataPayload(data.getData(""), "");
			PacketDistributor.sendToPlayer(player, payload);
		}

		// 强制向导：每次加入都按"需求是否满足"判断，不依赖"是否已显示过"——
		// 修复重进存档时 runGuiSent/joinedPlayers 已置位而跳过 GUI 的问题。
		// ① 游玩模式（全服全局）必须先选择：mode==0（未选）→ 打开 Run；
		// ② 玩家必须先入城：已选模式但会话无城市 → 打开 City。
		if (data.getMode() == 0)
		{
			PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.OpenGuiPayload(ServerToClientPayloads.OpenGuiPayload.GuiType.RUN));
			NeoSim.LOGGER.info("NeoSim-onPlayerJoin: open Run for {} (mode not selected)", playerName);
		}
		else if (CityManager.getCity(player.getUUID()).isEmpty())
		{
			PacketDistributor.sendToPlayer(player, new ServerToClientPayloads.OpenGuiPayload(ServerToClientPayloads.OpenGuiPayload.GuiType.CITY));
			NeoSim.LOGGER.info("NeoSim-onPlayerJoin: open City for {} (no city)", playerName);
		}

		// 同步标记棒全局状态，后加入者也能看到光束
		MarkerManager.syncTo(player);
	}

	// 玩家断线时清理其打开的NPC-GUI，防止NPC永久冻结
	@SubscribeEvent
	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event)
	{
		if (event.getEntity() instanceof ServerPlayer player)
		{
			java.util.UUID playerUUID = player.getUUID();

			// 遍历所有维度的所有已加载NPC，确保每个被该玩家冻结的NPC都被解冻
			// （不用无限AABB查询：Sable 会拦截并中止无限范围的实体查询）
			if (player.getServer() != null)
			{
				for (ServerLevel serverLevel : player.getServer().getAllLevels())
				{
					for (net.minecraft.world.entity.Entity e : serverLevel.getEntities().getAll())
					{
						if (e instanceof com.wenzai.neosim.npc.Entity neosimNpc)
						{
							neosimNpc.unfreezeBy(playerUUID);
						}
					}
				}
			}
			// 会话清理（玩家→城市表）
			CityManager.onPlayerLogout(playerUUID);
			NeoSim.LOGGER.debug("NeoSim-onPlayerLogout: cleaned up GUI refs for player={}", playerUUID);
		}
	}

	// day++和dayOfWeek++
	@SubscribeEvent
	public void onServerTick(ServerTickEvent.Post event)
	{
		try
		{
			tickServer(event);
		}
		catch (Exception e)
		{
			// 文件被删改导致的任何遗漏异常都不允许崩溃服务端，记日志后继续下一tick
			NeoSim.LOGGER.error("NeoSim-onServerTick: unhandled error, skipped tick", e);
		}
	}

	private void tickServer(ServerTickEvent.Post event)
	{
		// 定期清理因玩家非正常退出（崩溃、断线）而永久冻结的NPC
		frozenCleanupTimer++;
		if (frozenCleanupTimer >= 200)
		{
			frozenCleanupTimer = 0;
			for (Entity npc : com.wenzai.neosim.npc.NpcRegistry.allLoaded())
			{
				npc.cleanupStaleOpeners();
			}
		}

		ServerLevel level = event.getServer().overworld();

		// 生活系统
		LifeSystem.onServerTick(level);

		// 标记棒定时对账（非玩家破坏的角点即时剔除，光幕不残留）
		MarkerManager.tick(level);

		// 持久化合并窗口：脏城市数据/NPC/关系 每 5 秒统一落盘
		persistFlushTimer++;
		if (persistFlushTimer >= 100)
		{
			persistFlushTimer = 0;
			com.wenzai.neosim.storage.SimData.CityData.flushDirty();
			com.wenzai.neosim.storage.NpcData.flushDirty();
			com.wenzai.neosim.life.RelationshipPersistence.flushDirty();
		}

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
		registrar.playToClient(
				ServerToClientPayloads.TerraformCompletePacket.TYPE,
				ServerToClientPayloads.TerraformCompletePacket.STREAM_CODEC,
				ServerToClientPayloads.TerraformCompletePacket::handle
		);

		// 通知打开Run或City
		registrar.playToClient(
				ServerToClientPayloads.OpenGuiPayload.TYPE,
				ServerToClientPayloads.OpenGuiPayload.STREAM_CODEC,
				ServerToClientPayloads.OpenGuiPayload::handle
		);

		// 标记棒全局状态同步
		registrar.playToClient(
				ServerToClientPayloads.MarkerSyncPayload.TYPE,
				ServerToClientPayloads.MarkerSyncPayload.STREAM_CODEC,
				ServerToClientPayloads.MarkerSyncPayload::handle
		);

		// 控制箱管理动作完成通知（S→C）
		registrar.playToClient(
				ServerToClientPayloads.ControlBoxAckPayload.TYPE,
				ServerToClientPayloads.ControlBoxAckPayload.STREAM_CODEC,
				ServerToClientPayloads.ControlBoxAckPayload::handle
		);

		// 族谱数据响应（S→C）
		registrar.playToClient(
				ServerToClientPayloads.FamilyDataPayload.TYPE,
				ServerToClientPayloads.FamilyDataPayload.STREAM_CODEC,
				ServerToClientPayloads.FamilyDataPayload::handle
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

		// 灵魂出窍结束，传送回原位置
		registrar.playToServer(
				ClientToServerPayloads.SoulReturnPayload.TYPE,
				ClientToServerPayloads.SoulReturnPayload.STREAM_CODEC,
				ClientToServerPayloads.SoulReturnPayload::handle
		);

		// 工作盒选择页确认：农业作物/矿业丢弃设置
		registrar.playToServer(
				ClientToServerPayloads.WorkBoxApplyPayload.TYPE,
				ClientToServerPayloads.WorkBoxApplyPayload.STREAM_CODEC,
				ClientToServerPayloads.WorkBoxApplyPayload::handle
		);

		// 控制箱管理住户：驱逐/清空/安排入住
		registrar.playToServer(
				ClientToServerPayloads.ControlBoxManagePayload.TYPE,
				ClientToServerPayloads.ControlBoxManagePayload.STREAM_CODEC,
				ClientToServerPayloads.ControlBoxManagePayload::handle
		);

		// 请求族谱数据（C→S）
		registrar.playToServer(
				ClientToServerPayloads.FamilyRequestPayload.TYPE,
				ClientToServerPayloads.FamilyRequestPayload.STREAM_CODEC,
				ClientToServerPayloads.FamilyRequestPayload::handle
		);

		// 确认整地
		registrar.playToServer(
				ClientToServerPayloads.TerraformStartPayload.TYPE,
				ClientToServerPayloads.TerraformStartPayload.STREAM_CODEC,
				ClientToServerPayloads.TerraformStartPayload::handle
		);

		// 城市创建/加入/列表
		registrar.playToServer(
				ClientToServerPayloads.CreateCityPayload.TYPE,
				ClientToServerPayloads.CreateCityPayload.STREAM_CODEC,
				ClientToServerPayloads.CreateCityPayload::handle
		);
		registrar.playToServer(
				ClientToServerPayloads.JoinCityPayload.TYPE,
				ClientToServerPayloads.JoinCityPayload.STREAM_CODEC,
				ClientToServerPayloads.JoinCityPayload::handle
		);
		registrar.playToServer(
				ClientToServerPayloads.CityListRequestPayload.TYPE,
				ClientToServerPayloads.CityListRequestPayload.STREAM_CODEC,
				ClientToServerPayloads.CityListRequestPayload::handle
		);
		registrar.playToClient(
				ServerToClientPayloads.CityListResponsePayload.TYPE,
				ServerToClientPayloads.CityListResponsePayload.STREAM_CODEC,
				ServerToClientPayloads.CityListResponsePayload::handle
		);

		// 雇佣列表
		registrar.playToServer(
				ClientToServerPayloads.HireListRequestPayload.TYPE,
				ClientToServerPayloads.HireListRequestPayload.STREAM_CODEC,
				ClientToServerPayloads.HireListRequestPayload::handle
		);
		registrar.playToClient(
				ServerToClientPayloads.HireListResponsePayload.TYPE,
				ServerToClientPayloads.HireListResponsePayload.STREAM_CODEC,
				ServerToClientPayloads.HireListResponsePayload::handle
		);

		// 雇佣/解雇
		registrar.playToServer(
				ClientToServerPayloads.HirePayload.TYPE,
				ClientToServerPayloads.HirePayload.STREAM_CODEC,
				ClientToServerPayloads.HirePayload::handle
		);
		registrar.playToServer(
				ClientToServerPayloads.FirePayload.TYPE,
				ClientToServerPayloads.FirePayload.STREAM_CODEC,
				ClientToServerPayloads.FirePayload::handle
		);
		registrar.playToClient(
				ServerToClientPayloads.WorkerUpdatePayload.TYPE,
				ServerToClientPayloads.WorkerUpdatePayload.STREAM_CODEC,
				ServerToClientPayloads.WorkerUpdatePayload::handle
		);

		// 缺料扫描
		registrar.playToServer(
				ClientToServerPayloads.MissingScanRequestPayload.TYPE,
				ClientToServerPayloads.MissingScanRequestPayload.STREAM_CODEC,
				ClientToServerPayloads.MissingScanRequestPayload::handle
		);
		registrar.playToClient(
				ServerToClientPayloads.MissingScanResponsePayload.TYPE,
				ServerToClientPayloads.MissingScanResponsePayload.STREAM_CODEC,
				ServerToClientPayloads.MissingScanResponsePayload::handle
		);

		// 无家 NPC 名单（缺陷 C 结构性）
		registrar.playToServer(
				ClientToServerPayloads.HomelessListRequestPayload.TYPE,
				ClientToServerPayloads.HomelessListRequestPayload.STREAM_CODEC,
				ClientToServerPayloads.HomelessListRequestPayload::handle
		);
		registrar.playToClient(
				ServerToClientPayloads.HomelessListResponsePayload.TYPE,
				ServerToClientPayloads.HomelessListResponsePayload.STREAM_CODEC,
				ServerToClientPayloads.HomelessListResponsePayload::handle
		);

	}

	private void commonSetup(FMLCommonSetupEvent event)
	{
		LOGGER.info("TOML: initialCredit={}", Config.INITIAL_CREDIT.get());
		LOGGER.info("maxPopulation={}", Config.MAX_POPULATION.get());

		LOGGER.info("npcAgeRange=[{}, {}]", Config.NPC_MIN_AGE.get(), Config.NPC_MAX_AGE.get());
	}

	// 将示例方块物品添加到建筑方块标签页
	private void addCreative(BuildCreativeModeTabContentsEvent event)
	{

	}

	private void registerEntityAttributes(EntityAttributeCreationEvent event)
	{
		event.put(Entity.NPC.get(), Entity.createAttributes().build());
	}

	// 你可以使用 SubscribeEvent，让事件总线自动发现要调用的方法
	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event)
	{
		// 在服务器启动时执行一些操作
		LOGGER.info("HELLO from server starting");

		// 预热模组作物注册表（懒加载扫描放启动时，避免首个农业盒放置时卡顿）
		com.wenzai.neosim.compat.crops.CropRegistry.all();

		// 服务器初始化蓝图
		com.wenzai.neosim.schematic.SchematicRegistry.getInstance().initializeAsync();
	}

	// 世界加载：从存档恢复标记位置
	@SubscribeEvent
	public void onLevelLoad(LevelEvent.Load event)
	{
		if (event.getLevel() instanceof ServerLevel serverLevel)
		{
			MarkerManager.loadFrom(serverLevel);
		}
	}

	// 服务端停止时重置静态变量，防止下一个存档读到残留数据
	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event)
	{
		ModSavedData.setActiveCityName("");
		MarkerManager.clear();
		CityManager.clear();
		com.wenzai.neosim.npc.NpcRegistry.clear();
		// L1：工人分配静态表跨存档/跨会话残留 → 统一 clear
		WORKER_MAP.clear();
		// L2：临产目标缓存跨存档残留 → 统一 clear
		com.wenzai.neosim.life.ReproductionSystem.clearAllBirthTargets();
		// L3：单例持 ServerLevel，关档后钉住旧世界 → 置空
		com.wenzai.neosim.storage.ModSavedData.resetInstance();
		// player.json 成员缓存跨存档残留 → 清空
		com.wenzai.neosim.storage.FileCreater.clearPlayerCache();

		// 合并窗口强制 flush（城市数据 + NPC 写盘去抖 + 关系缓存），随后清缓存防跨存档残留
		com.wenzai.neosim.storage.SimData.CityData.flushAndClear();
		com.wenzai.neosim.storage.NpcData.flushDirty();
		com.wenzai.neosim.life.RelationshipPersistence.flushAndClear();
		LOGGER.info("NeoSim: activeCityName reset on server stopping");
	}
}
