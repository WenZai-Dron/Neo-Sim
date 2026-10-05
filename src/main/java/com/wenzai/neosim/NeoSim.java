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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
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

	// 内容表热重载计时（每 100 tick 检查一次 NeoSim/Json/）
	private int contentCheckTimer = 0;

	// 模组类的构造方法是模组加载时最先运行的代码
	// FML 会识别一些参数类型（如 IEventBus 或 ModContainer）并自动传入
	public NeoSim(IEventBus modEventBus, ModContainer modContainer)
	{
		// 注册用于模组加载的 commonSetup 方法
		modEventBus.addListener(this::commonSetup);

		// 注册物理模组适配器（Sable 等）：仅加载已安装模组的适配器，保持零硬依赖
		PhysicsAdapterRegistry.init();

		ModItems.register(modEventBus);
		ModBlocks.register(modEventBus);
		Entity.register(modEventBus);
		CreativeModeTabs.register(modEventBus);

		// 注册实体属性
		modEventBus.addListener(this::registerEntityAttributes);

		// 将自身注册到服务器及其他感兴趣的游戏事件
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

		// 配置热重载后重新应用视距 / 模拟距离
		modEventBus.addListener(this::onConfigReloading);
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
		// 修复重进存档时 runGuiSent/joinedPlayers 已置位而跳过 GUI 的问题
		// ① 游玩模式（全服全局）必须先选择：mode==0（未选）→ 打开 Run；
		// ② 玩家必须先入城：已选模式但会话无城市 → 打开 City
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

		// 内容表热重载：每 5 秒比对一次 NeoSim/Json/ 的修改时间，变化才重建
		contentCheckTimer++;
		if (contentCheckTimer >= 100)
		{
			contentCheckTimer = 0;
			com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
		}

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

			// 生活系统每日结算入口（Phase 5+）
			LifeSystem.onDayStart(level, data.getDayOfWeek());
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

		// 客户端语言上报（新建 NPC 命名池）
		registrar.playToServer(
				ClientToServerPayloads.ClientLocalePayload.TYPE,
				ClientToServerPayloads.ClientLocalePayload.STREAM_CODEC,
				ClientToServerPayloads.ClientLocalePayload::handle
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

		// 快递站材料（快递盒 + 相连箱链）
		registrar.playToServer(
				ClientToServerPayloads.StationItemsRequestPayload.TYPE,
				ClientToServerPayloads.StationItemsRequestPayload.STREAM_CODEC,
				ClientToServerPayloads.StationItemsRequestPayload::handle
		);
		registrar.playToClient(
				ServerToClientPayloads.StationItemsResponsePayload.TYPE,
				ServerToClientPayloads.StationItemsResponsePayload.STREAM_CODEC,
				ServerToClientPayloads.StationItemsResponsePayload::handle
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
		LOGGER.info("NeoSim-Config: initialCredit={}", Config.INITIAL_CREDIT.get());
		LOGGER.info("NeoSim-Config: maxPopulation={}", Config.MAX_POPULATION.get());

		LOGGER.info("NeoSim-Config: npcAgeRange=[{}, {}]", Config.NPC_MIN_AGE.get(), Config.NPC_MAX_AGE.get());
	}

	// 将示例方块物品添加到建筑方块标签页
	private void addCreative(BuildCreativeModeTabContentsEvent event)
	{

	}

	private void registerEntityAttributes(EntityAttributeCreationEvent event)
	{
		event.put(Entity.NPC.get(), Entity.createAttributes().build());
	}

	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event)
	{
		// 预热模组作物注册表（懒加载扫描放启动时，避免首个农业盒放置时卡顿）
		com.wenzai.neosim.compat.crops.CropRegistry.all();

		// 模组依赖性方块扫描：启动时定一次「哪些方块来自已装模组」，建造时按方块 id 直接查
		com.wenzai.neosim.compat.modded.ModBlockRegistry.ensureLoaded();

		// 加载内容表（NeoSim/Json/）：首次运行落盘模板，扫描出的模组作物写回 crops.json
		com.wenzai.neosim.json.ContentReloader.reloadAll();

		// 服务器初始化蓝图
		com.wenzai.neosim.schematic.SchematicRegistry.getInstance().initializeAsync();

		// 应用 neo-sim.toml 的视距 / 模拟距离（0 = 关闭，保持 server.properties）
		applyServerDistances(event.getServer());
	}

	// 视距 / 模拟距离：0 = 关闭（保持 server.properties），1-16 覆盖服务器设置
	// simulation 不能大于 view，否则原版会告警并自行截断，这里主动取小
	private static void applyServerDistances(MinecraftServer server)
	{
		if (server == null || server.getPlayerList() == null) return;
		var list = server.getPlayerList();
		int view = Config.VIEW_DISTANCE.get();
		int sim = Config.SIMULATION_DISTANCE.get();

		if (view > 0)
		{
			list.setViewDistance(view);
		}
		int effectiveView = view > 0 ? view : list.getViewDistance();

		int appliedSim = list.getSimulationDistance();
		if (sim > 0)
		{
			appliedSim = Math.min(sim, effectiveView);
			list.setSimulationDistance(appliedSim);
		}

		LOGGER.info("NeoSim-Config: viewDistance={} (effective {}), simulationDistance={} (effective {})",
				view > 0 ? view : "off", effectiveView,
				sim > 0 ? sim : "off", appliedSim);
	}

	// 配置热重载：只重新应用视距 / 模拟距离
	private void onConfigReloading(ModConfigEvent.Reloading event)
	{
		if (event.getConfig().getSpec() != Config.SPEC) return;
		applyServerDistances(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
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

		// 工人分配静态表跨存档/跨会话残留 → 统一 clear
		WORKER_MAP.clear();

		// 临产目标缓存跨存档残留 → 统一 clear
		com.wenzai.neosim.life.ReproductionSystem.clearAllBirthTargets();

		// 客户端语言上报缓存跨存档残留 → 统一 clear
		com.wenzai.neosim.npc.PlayerLocales.clear();

		// 单例持 ServerLevel，关档后钉住旧世界 → 置空
		com.wenzai.neosim.storage.ModSavedData.resetInstance();

		// player.json 成员缓存跨存档残留 → 清空
		com.wenzai.neosim.storage.FileCreater.clearPlayerCache();

		// 合并窗口强制 flush（城市数据 + NPC 写盘去抖 + 关系缓存），随后清缓存防跨存档残留
		com.wenzai.neosim.storage.SimData.CityData.flushAndClear();
		com.wenzai.neosim.storage.NpcData.flushDirty();
		com.wenzai.neosim.life.RelationshipPersistence.flushAndClear();
		LOGGER.info("NeoSim: activeCityName reset on server stopping");
	}

	// 金额文本：三位分节 + 两位小数（HUD、城市信息页、指令回执共用同一份实现）
	public static String amount(double value)
	{
		return String.format("%,.2f", value);
	}

	// 蓝图名组件：用于服务端发出的公告/提示
	// 中文客户端显示汉化名（blueprint.neosim.* 键由 datagen 从 zh_cn_names.json 生成），
	// 其他语言（以及未收录的自定义蓝图）回落到蓝图自带英文名
	public static Component blueprintName(String schematicName)
	{
		if (schematicName == null || schematicName.isEmpty()) return Component.literal("");
		return Component.translatableWithFallback("blueprint.neosim." + schematicName, schematicName);
	}
}
