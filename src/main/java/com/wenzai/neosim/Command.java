package com.wenzai.neosim;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.building.BuildingInstance;
import com.wenzai.neosim.building.BuildingPersistence;
import com.wenzai.neosim.building.ConstructionEngine;
import com.wenzai.neosim.building.ConstructionTask;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.npc.Manage;
import com.wenzai.neosim.npc.NpcRegistry;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import com.wenzai.neosim.storage.CityManager;
import com.wenzai.neosim.storage.FileCreater;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import com.wenzai.neosim.storage.SimData.CityData;
import com.wenzai.neosim.util.JsonUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

// 指令根：注册 + 公共件（校验 / 回执 / 补全 /summon 拦截）；五个模块是末尾的内嵌类
public class Command
{
	// 工具类：只有静态成员，禁止实例化
	private Command()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();

	// 失败文案键前缀（跨模块复用）
	private static final String ERROR = "msg.neosim.command.error.";

	@SubscribeEvent
	public static void register(RegisterCommandsEvent event)
	{
		CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

		dispatcher.register(
			Commands.literal("neosim")
				.then(City.node())
				.then(Credit.node())
				.then(Npc.node())
				.then(Building.node())
				.then(Blueprint.node()));
	}

	// ---- 公共件 ----

	// 城市不存在时回失败消息
	static boolean requireCity(CommandSourceStack source, ServerLevel level, String cityName)
	{
		if (Manage.cityExists(level, cityName)) return true;

		source.sendFailure(Component.translatable("msg.neosim.command.cityNotFound", cityName));
		return false;
	}

	// 成功回执：只发给执行者，不广播
	static int succeed(CommandSourceStack source, String key, Object... args)
	{
		source.sendSuccess(() -> Component.translatable(key, args), false);
		return 1;
	}

	// 失败回执
	static int fail(CommandSourceStack source, String key, Object... args)
	{
		source.sendFailure(Component.translatable(key, args));
		return 0;
	}

	// Tab 补全：已有城市名
	static CompletableFuture<Suggestions> suggestCities(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder)
	{
		for (String city : FileCreater.listCities(ctx.getSource().getLevel()))
		{
			builder.suggest(city);
		}
		return builder.buildFuture();
	}

	// Tab 补全：居民名（跨城市取档案名，去重排序）
	static CompletableFuture<Suggestions> suggestNpcs(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder)
	{
		ServerLevel level = ctx.getSource().getLevel();
		Set<String> names = new TreeSet<>();
		for (String city : FileCreater.listCities(level))
		{
			names.addAll(NpcData.listNpcNames(level, city));
		}
		for (String name : names)
		{
			builder.suggest(name);
		}
		return builder.buildFuture();
	}

	// ---- 禁 /summon ----

	// 禁止 /summon 生成本模组实体
	@SubscribeEvent
	public static void onCommand(CommandEvent event)
	{
		ParseResults<CommandSourceStack> results = event.getParseResults();
		if (!summonsNeoSimEntity(results)) return;

		event.setCanceled(true);
		results.getContext().getSource().sendFailure(Component.translatable("msg.neosim.command.summonBlocked"));
	}

	// 只看 summon 的实体参数节点：/execute ... run summon 同样命中，且不会误伤别处出现的 neo_sim: 文本
	private static boolean summonsNeoSimEntity(ParseResults<CommandSourceStack> results)
	{
		boolean summon = false;
		String command = results.getReader().getString();
		for (var node : results.getContext().getNodes())
		{
			String name = node.getNode().getName();
			if (name.equals("summon"))
			{
				summon = true;
				continue;
			}
			if (summon && name.equals("entity") && node.getRange().get(command).contains("neo_sim:"))
			{
				return true;
			}
		}
		return false;
	}

	// ---- 模块：city ----

	// /neosim city：列表、摘要、建城、入城、退城、删城
	private static final class City
	{
		private static final String MSG = "msg.neosim.command.city.";

		private City()
		{
		}

		static LiteralArgumentBuilder<CommandSourceStack> node()
		{
			return Commands.literal("city")
				.then(Commands.literal("list")
					.requires(City::admin)
					.executes(City::list))
				.then(Commands.literal("info")
					.requires(City::admin)
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.suggests(Command::suggestCities)
						.executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "cityName")))))
				.then(Commands.literal("create")
					.requires(City::admin)
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "cityName")))))
				.then(Commands.literal("join")
					.requires(City::canJoin)
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.suggests(Command::suggestCities)
						.executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "cityName")))))
				.then(Commands.literal("leave")
					.requires(City::canLeave)
					.executes(City::leave))
				.then(Commands.literal("remove")
					.requires(City::admin)
					.then(Commands.literal("confirm")
						.then(Commands.argument("cityName", StringArgumentType.greedyString())
							.suggests(Command::suggestCities)
							.executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "cityName"))))));
		}

		// 管理指令固定 2 级
		private static boolean admin(CommandSourceStack source)
		{
			return source.hasPermission(2);
		}

		// 已在城市的玩家：join 不可用
		private static boolean canJoin(CommandSourceStack source)
		{
			return source.getEntity() instanceof ServerPlayer player && CityManager.getCity(player).isEmpty();
		}

		// 没有城市的玩家：leave 不可用
		private static boolean canLeave(CommandSourceStack source)
		{
			return source.getEntity() instanceof ServerPlayer player && !CityManager.getCity(player).isEmpty();
		}

		// 全部城市 + 各自的人口 / 天数 / 资金
		private static int list(CommandContext<CommandSourceStack> ctx)
		{
			ServerLevel level = ctx.getSource().getLevel();
			List<String> cities = FileCreater.listCities(level);
			ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list", cities.size()), false);

			for (String cityName : cities)
			{
				CityData data = CityData.read(level, cityName);
				ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "line",
						cityName, data.population(), data.day(), NeoSim.amount(data.credit())), false);
			}
			return 1;
		}

		// 单城摘要：人口 / 天数 / 资金 / 在线人数 / 建筑数
		private static int info(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			CityData data = CityData.read(level, cityName);
			int online = countOnline(level, cityName);
			int buildings = BuildingPersistence.loadFromCity(level, cityName).size();
			return succeed(ctx.getSource(), MSG + "info",
					cityName, data.population(), data.day(), NeoSim.amount(data.credit()), online, buildings);
		}

		// 建城：与城市 GUI 的「新增城市」同一条路（失败文案复用 CityManager 的既有键）
		private static int create(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player == null) return fail(ctx.getSource(), ERROR + "notPlayer");

			Component error = CityManager.createCity(ctx.getSource().getLevel(), player, cityName);
			if (error != null)
			{
				ctx.getSource().sendFailure(error);
				return 0;
			}
			LOGGER.info("NeoSim-Command.city: created city '{}' by {}", cityName, player.getName().getString());
			return succeed(ctx.getSource(), MSG + "create", cityName);
		}

		// 入城：可用性已由 canJoin 保证（玩家且没有城市）
		private static int join(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player == null) return fail(ctx.getSource(), ERROR + "notPlayer");

			Component error = CityManager.joinCity(ctx.getSource().getLevel(), player, cityName);
			if (error != null)
			{
				ctx.getSource().sendFailure(error);
				return 0;
			}
			LOGGER.info("NeoSim-Command.city: '{}' joined city '{}'", player.getName().getString(), cityName);
			return succeed(ctx.getSource(), MSG + "join", cityName);
		}

		// 退城：可用性已由 canLeave 保证（玩家且已有城市）
		private static int leave(CommandContext<CommandSourceStack> ctx)
		{
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player == null) return fail(ctx.getSource(), ERROR + "notPlayer");

			String city = CityManager.getCity(player);
			String left = CityManager.leaveCity(ctx.getSource().getLevel(), player);
			if (left == null) return fail(ctx.getSource(), "msg.neosim.command.cityNotFound", city);

			return succeed(ctx.getSource(), MSG + "leave", left);
		}

		// 删城：整目录备份后移走
		private static int remove(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			Path backup = FileCreater.deleteCity(level, cityName);
			if (backup == null) return fail(ctx.getSource(), ERROR + "cityDeleteFail", cityName);

			LOGGER.info("NeoSim-Command.city: removed city '{}' by {}", cityName, ctx.getSource().getTextName());
			return succeed(ctx.getSource(), MSG + "remove", cityName, backup.getFileName().toString());
		}

		// 该城市的在线玩家人数
		private static int countOnline(ServerLevel level, String cityName)
		{
			int count = 0;
			for (ServerPlayer player : level.players())
			{
				if (cityName.equals(CityManager.getCity(player))) count++;
			}
			return count;
		}
	}

	// ---- 模块：credit ----

	// /neosim credit：设置 / 增加城市资金
	private static final class Credit
	{
		private static final String MSG = "msg.neosim.command.credit.";

		private Credit()
		{
		}

		static LiteralArgumentBuilder<CommandSourceStack> node()
		{
			return Commands.literal("credit")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("set")
					.then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
						.then(Commands.argument("cityName", StringArgumentType.greedyString())
							.suggests(Command::suggestCities)
							.executes(ctx -> set(ctx, DoubleArgumentType.getDouble(ctx, "value"),
									StringArgumentType.getString(ctx, "cityName"))))))
				.then(Commands.literal("add")
					.then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
						.then(Commands.argument("cityName", StringArgumentType.greedyString())
							.suggests(Command::suggestCities)
							.executes(ctx -> add(ctx, DoubleArgumentType.getDouble(ctx, "value"),
									StringArgumentType.getString(ctx, "cityName"))))));
		}

		// 覆盖为给定值
		private static int set(CommandContext<CommandSourceStack> ctx, double value, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			CityData city = CityData.read(level, cityName);
			save(ctx.getSource(), level, cityName, city, value);
			return succeed(ctx.getSource(), MSG + "set", cityName, NeoSim.amount(value));
		}

		// 在现有资金上累加
		private static int add(CommandContext<CommandSourceStack> ctx, double value, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			CityData city = CityData.read(level, cityName);
			double credit = city.credit() + value;
			save(ctx.getSource(), level, cityName, city, credit);
			return succeed(ctx.getSource(), MSG + "add", cityName, NeoSim.amount(value), NeoSim.amount(credit));
		}

		// 资金写回的唯一入口：两位小数由 CityData.withCredit 统一处理，写回后同步给该城市在线玩家刷新 HUD
		private static void save(CommandSourceStack source, ServerLevel level, String cityName, CityData city, double credit)
		{
			CityData updated = city.withCredit(credit);
			CityData.write(level, cityName, updated);
			ModSavedData.get(level).syncCityToClients(level, cityName);
			LOGGER.info("NeoSim-Command.credit: credit={} city={} by {}", updated.credit(), cityName, source.getTextName());
		}
	}

	// ---- 模块：npc ----

	// /neosim npc：名单、详情、生成、移除、传送、冻结、回收
	private static final class Npc
	{
		private static final String MSG = "msg.neosim.command.npc.";

		// 落点：偏移半径与重掷上限（用尽则退回执行者脚下）
		private static final int OFFSET_RADIUS = 2;
		private static final int SAFE_SPOT_TRIES = 8;

		private Npc()
		{
		}

		static LiteralArgumentBuilder<CommandSourceStack> node()
		{
			return Commands.literal("npc")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("list")
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.suggests(Command::suggestCities)
						.executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "cityName")))))
				.then(Commands.literal("info")
					.then(Commands.argument("npcName", StringArgumentType.string())
						.suggests(Command::suggestNpcs)
						.then(Commands.argument("cityName", StringArgumentType.greedyString())
							.suggests(Command::suggestCities)
							.executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "npcName"),
									StringArgumentType.getString(ctx, "cityName"))))))
				.then(Commands.literal("spawn")
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.suggests(Command::suggestCities)
						.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "cityName")))))
				.then(Commands.literal("remove")
					.then(Commands.literal("confirm")
						.then(Commands.argument("npcName", StringArgumentType.string())
							.suggests(Command::suggestNpcs)
							.then(Commands.argument("cityName", StringArgumentType.greedyString())
								.suggests(Command::suggestCities)
								.executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "npcName"),
										StringArgumentType.getString(ctx, "cityName")))))))
				.then(Commands.literal("tp")
					.then(Commands.argument("npcName", StringArgumentType.string())
						.suggests(Command::suggestNpcs)
						.then(Commands.argument("cityName", StringArgumentType.greedyString())
							.suggests(Command::suggestCities)
							.executes(ctx -> tp(ctx, StringArgumentType.getString(ctx, "npcName"),
									StringArgumentType.getString(ctx, "cityName"))))))
				.then(Commands.literal("freeze")
					.then(Commands.argument("npcName", StringArgumentType.string())
						.suggests(Command::suggestNpcs)
						.then(Commands.literal("true")
							.then(Commands.argument("cityName", StringArgumentType.greedyString())
								.suggests(Command::suggestCities)
								.executes(ctx -> freeze(ctx, StringArgumentType.getString(ctx, "npcName"),
										StringArgumentType.getString(ctx, "cityName"), true))))
						.then(Commands.literal("false")
							.then(Commands.argument("cityName", StringArgumentType.greedyString())
								.suggests(Command::suggestCities)
								.executes(ctx -> freeze(ctx, StringArgumentType.getString(ctx, "npcName"),
										StringArgumentType.getString(ctx, "cityName"), false))))))
				.then(Commands.literal("recall")
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.suggests(Command::suggestCities)
						.executes(ctx -> recall(ctx, StringArgumentType.getString(ctx, "cityName")))));
		}

		// 档案名单 + 在线 / 住房状态
		private static int list(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			List<String> names = NpcData.listNpcNames(level, cityName);
			ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list", cityName, names.size()), false);

			for (String npcName : names)
			{
				boolean online = Manage.findLoaded(cityName, npcName) != null;
				boolean hasHome = NpcData.homeStatus(level, cityName, npcName) > 0;
				Component state = Component.translatable(MSG + (online ? "online" : "offline"));
				Component home = Component.translatable(MSG + (hasHome ? "hasHome" : "noHome"));
				ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "line", npcName, state, home), false);
			}
			return 1;
		}

		// 详情：年龄 / 性别 / 四个职业等级 / 住房 / 配偶 / 子女（在线读实体，离线读档案）
		private static int info(CommandContext<CommandSourceStack> ctx, String npcName, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			Entity npc = Manage.findLoaded(cityName, npcName);
			JsonObject json = NpcData.load(level, cityName, npcName);
			if (npc == null && json == null) return fail(ctx.getSource(), ERROR + "npcNotFound", npcName);

			JsonObject job = json != null ? JsonUtil.getObject(json, "job") : null;
			short age = npc != null ? npc.getAge() : JsonUtil.getShort(json, "age", (short) 0);
			String sex = npc != null ? npc.getSex() : JsonUtil.getString(json, "sex", "-");
			int architect = npc != null ? npc.getJobLevel(Entity.JobKind.ARCHITECT) : jobLevel(job, "architect");
			int farmer = npc != null ? npc.getJobLevel(Entity.JobKind.FARMER) : jobLevel(job, "farmer");
			int miner = npc != null ? npc.getJobLevel(Entity.JobKind.MINER) : jobLevel(job, "miner");
			int courier = npc != null ? npc.getJobLevel(Entity.JobKind.COURIER) : jobLevel(job, "courier");
			String home = npc != null ? npc.getHomeBuilding() : JsonUtil.getString(json, "homeBuilding", "");
			String partner = npc != null ? npc.getPartner() : JsonUtil.getString(json, "partner", "");
			String children = npc != null ? String.join("/", npc.getChildren()) : "";

			return succeed(ctx.getSource(), MSG + "info", npcName, sex, age,
					architect, farmer, miner, courier, blank(home), blank(partner), blank(children));
		}

		// 生成一名居民：落点取执行者周围 1~2 格的可站立点
		private static int spawn(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player == null) return fail(ctx.getSource(), ERROR + "notPlayer");
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			Entity npc = Manage.spawnAt(level, safeSpot(level, player), cityName, player.getUUID());
			if (npc == null) return fail(ctx.getSource(), ERROR + "populationMax", Config.MAX_POPULATION.get());

			return succeed(ctx.getSource(), MSG + "spawn", cityName, npc.getNpcName());
		}

		// 移除居民（退房 + 摘族谱 + 删档 + 人口 -1）
		private static int remove(CommandContext<CommandSourceStack> ctx, String npcName, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;
			if (!Manage.removeNpc(level, cityName, npcName)) return fail(ctx.getSource(), ERROR + "npcNotFound", npcName);

			LOGGER.info("NeoSim-Command.npc: removed '{}' from '{}' by {}", npcName, cityName, ctx.getSource().getTextName());
			return succeed(ctx.getSource(), MSG + "remove", npcName);
		}

		// 传送到执行者身边（未加载的按档案在玩家处恢复）
		private static int tp(CommandContext<CommandSourceStack> ctx, String npcName, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player == null) return fail(ctx.getSource(), ERROR + "notPlayer");
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			if (!Manage.teleportToPlayer(level, cityName, npcName, player))
			{
				return fail(ctx.getSource(), ERROR + "npcNotFound", npcName);
			}
			return succeed(ctx.getSource(), MSG + "tp", npcName);
		}

		// 冻结 / 解冻（与 GUI 冻结同一入口，只对已加载的居民有效）
		private static int freeze(CommandContext<CommandSourceStack> ctx, String npcName, String cityName, boolean frozen)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			Entity npc = Manage.findLoaded(cityName, npcName);
			if (npc == null) return fail(ctx.getSource(), ERROR + "npcNotFound", npcName);

			npc.setFrozen(frozen);
			return succeed(ctx.getSource(), MSG + "freeze", npcName,
					Component.translatable(MSG + (frozen ? "frozen" : "unfrozen")));
		}

		// 回收远端、拉回近端
		private static int recall(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			int before = NpcRegistry.byCity(cityName).size();
			Manage.despawnFarFromPlayers(level, cityName);
			Manage.respawnNearPlayers(level, cityName);
			int removed = Math.max(0, before - NpcRegistry.byCity(cityName).size());

			LOGGER.info("NeoSim-Command.npc: recall '{}' unloaded {} NPCs", cityName, removed);
			return succeed(ctx.getSource(), MSG + "recall", cityName, removed);
		}

		// 落点：执行者周围偏 1~2 格的可站立点；重掷上限用尽则退回执行者脚下
		private static BlockPos safeSpot(ServerLevel level, ServerPlayer player)
		{
			BlockPos origin = BlockPos.containing(player.position());
			for (int i = 0; i < SAFE_SPOT_TRIES; i++)
			{
				BlockPos pos = origin.offset(
						ThreadLocalRandom.current().nextInt(-OFFSET_RADIUS, OFFSET_RADIUS + 1), 0,
						ThreadLocalRandom.current().nextInt(-OFFSET_RADIUS, OFFSET_RADIUS + 1));
				if (pos.equals(origin)) continue;
				if (isStandable(level, pos)) return pos;
			}
			return origin;
		}

		// 可站立：下方是实体方块，本格与上方都能通行
		private static boolean isStandable(ServerLevel level, BlockPos pos)
		{
			BlockPos below = pos.below();
			BlockState floor = level.getBlockState(below);
			if (floor.isAir() || !floor.isFaceSturdy(level, below, Direction.UP)) return false;

			return standableAt(level, pos) && standableAt(level, pos.above());
		}

		// 单格可通行：非火焰、非流体、无碰撞体积（草丛 / 花这类可站）
		private static boolean standableAt(ServerLevel level, BlockPos pos)
		{
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.FIRE)) return false;
			if (!level.getFluidState(pos).isEmpty()) return false;
			return state.getCollisionShape(level, pos).isEmpty();
		}

		// 离线档案里的职业等级（无 job 段给默认 1）
		private static int jobLevel(JsonObject job, String key)
		{
			return job == null ? 1 : JsonUtil.getByte(job, key, (byte) 1);
		}

		// 空值统一显示为 —
		private static String blank(String text)
		{
			return text == null || text.isEmpty() ? "—" : text;
		}
	}

	// ---- 模块：building ----

	// /neosim building：列表、暂停、恢复、取消
	private static final class Building
	{
		private static final String MSG = "msg.neosim.command.building.";

		private Building()
		{
		}

		static LiteralArgumentBuilder<CommandSourceStack> node()
		{
			return Commands.literal("building")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("list")
					.then(Commands.argument("cityName", StringArgumentType.greedyString())
						.suggests(Command::suggestCities)
						.executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "cityName")))))
				.then(Commands.literal("pause")
					.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(ctx -> pause(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
				.then(Commands.literal("resume")
					.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(ctx -> resume(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
				.then(Commands.literal("stop")
					.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(ctx -> stop(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))));
		}

		// 已建 + 在建：蓝图名 / 状态 / 进度 / 工人
		private static int list(CommandContext<CommandSourceStack> ctx, String cityName)
		{
			ServerLevel level = ctx.getSource().getLevel();
			if (!requireCity(ctx.getSource(), level, cityName)) return 0;

			// 档案里的记录 + 进行中的任务，按任务键去重（在建的最新状态覆盖档案）
			Map<BlockPos, BuildingInstance> byPos = new LinkedHashMap<>();
			for (BuildingInstance building : BuildingPersistence.loadFromCity(level, cityName))
			{
				byPos.put(keyOf(building), building);
			}
			for (BuildingInstance building : ConstructionEngine.getActiveBuildings())
			{
				if (cityName.equals(ConstructionTask.cityOf(building, level))) byPos.put(keyOf(building), building);
			}

			int total = byPos.size();
			ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list", cityName, total), false);
			for (BuildingInstance building : byPos.values())
			{
				ConstructionTask task = ConstructionEngine.findTask(keyOf(building));
				Component state = Component.translatable(MSG + "state." + stateOf(building));
				String progress = task != null
						? task.getProgress() + "/" + task.getTotal()
						: String.valueOf(building.getBuildProgress());
				String worker = building.getWorkerName() == null || building.getWorkerName().isEmpty()
						? "—"
						: building.getWorkerName();
				ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "line",
						building.getSchematicName(), state, progress, worker), false);
			}
			return 1;
		}

		private static int pause(CommandContext<CommandSourceStack> ctx, BlockPos pos)
		{
			ConstructionTask task = ConstructionEngine.findTask(pos);
			if (task == null) return fail(ctx.getSource(), ERROR + "taskNotFound");
			if (task.isPaused()) return fail(ctx.getSource(), ERROR + "alreadyPaused");

			ConstructionEngine.setPausedAt(ctx.getSource().getLevel(), pos, true);
			LOGGER.info("NeoSim-Command.building: paused at {} by {}", pos, ctx.getSource().getTextName());
			return succeed(ctx.getSource(), MSG + "pause");
		}

		private static int resume(CommandContext<CommandSourceStack> ctx, BlockPos pos)
		{
			ConstructionTask task = ConstructionEngine.findTask(pos);
			if (task == null) return fail(ctx.getSource(), ERROR + "taskNotFound");
			if (!task.isPaused()) return fail(ctx.getSource(), ERROR + "notPaused");

			ConstructionEngine.setPausedAt(ctx.getSource().getLevel(), pos, false);
			LOGGER.info("NeoSim-Command.building: resumed at {} by {}", pos, ctx.getSource().getTextName());
			return succeed(ctx.getSource(), MSG + "resume");
		}

		private static int stop(CommandContext<CommandSourceStack> ctx, BlockPos pos)
		{
			if (ConstructionEngine.findTask(pos) == null) return fail(ctx.getSource(), ERROR + "taskNotFound");

			ConstructionEngine.cancelTaskAt(pos, ctx.getSource().getLevel());
			LOGGER.info("NeoSim-Command.building: canceled at {} by {}", pos, ctx.getSource().getTextName());
			return succeed(ctx.getSource(), MSG + "stop");
		}

		// 状态键：完工 / 暂停 / 在建
		private static String stateOf(BuildingInstance building)
		{
			if (building.isBuildingComplete()) return "complete";
			return building.isPaused() ? "paused" : "building";
		}

		// 任务键：优先建筑模盒坐标，缺失时退回控制箱坐标
		private static BlockPos keyOf(BuildingInstance building)
		{
			return building.getConstructorPos() != null ? building.getConstructorPos() : building.getControlBoxPos();
		}
	}

	// ---- 模块：blueprint ----

	// /neosim blueprint：数量、按类型列出、搜索、重扫
	private static final class Blueprint
	{
		private static final String MSG = "msg.neosim.command.blueprint.";

		// 搜索结果最多回这么多条，避免刷屏
		private static final int SEARCH_LIMIT = 20;

		private Blueprint()
		{
		}

		static LiteralArgumentBuilder<CommandSourceStack> node()
		{
			return Commands.literal("blueprint")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("list")
					.executes(ctx -> list(ctx, null))
					.then(typeNode("residential", BuildingType.RESIDENTIAL))
					.then(typeNode("commercial", BuildingType.COMMERCIAL))
					.then(typeNode("industrial", BuildingType.INDUSTRIAL))
					.then(typeNode("other", BuildingType.OTHER))
					.then(typeNode("custom", BuildingType.CUSTOM)))
				.then(Commands.literal("search")
					.then(Commands.argument("query", StringArgumentType.string())
						.executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "query")))))
				.then(Commands.literal("reload")
					.executes(Blueprint::reload));
		}

		// 单类型分支
		private static LiteralArgumentBuilder<CommandSourceStack> typeNode(String literal, BuildingType type)
		{
			return Commands.literal(literal).executes(ctx -> list(ctx, type));
		}

		// 蓝图总数；带类型时只报该类数量
		private static int list(CommandContext<CommandSourceStack> ctx, BuildingType type)
		{
			SchematicRegistry registry = SchematicRegistry.getInstance();
			if (type == null) return succeed(ctx.getSource(), MSG + "list", registry.size());

			return succeed(ctx.getSource(), MSG + "listType", type.getDisplayName(), registry.getByType(type).size());
		}

		// 按名搜索，最多列 SEARCH_LIMIT 条
		private static int search(CommandContext<CommandSourceStack> ctx, String query)
		{
			List<SchematicData> hits = SchematicRegistry.getInstance().search(query);
			ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "search", hits.size()), false);

			int shown = 0;
			for (SchematicData hit : hits)
			{
				if (shown++ >= SEARCH_LIMIT) break;
				ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "entry", hit.getName()), false);
			}
			return 1;
		}

		// 重扫自定义蓝图目录
		private static int reload(CommandContext<CommandSourceStack> ctx)
		{
			SchematicRegistry.getInstance().refreshCustom();
			LOGGER.info("NeoSim-Command.blueprint: reloaded by {}", ctx.getSource().getTextName());
			return succeed(ctx.getSource(), MSG + "reload");
		}
	}
}
