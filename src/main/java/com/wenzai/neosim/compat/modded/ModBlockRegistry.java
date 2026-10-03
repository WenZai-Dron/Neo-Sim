package com.wenzai.neosim.compat.modded;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.json.BlockMatcher;
import com.wenzai.neosim.json.JsonContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

// 模组依赖性方块表：先判"这个方块是不是某个已装模组带来的"，再判"它是不是依赖性方块"，最后决定排第几轮。
//
// 第一步 · 是不是模组方块（依赖性来源）
//   命名空间不是 minecraft 也不是本模组 neo_sim —— 方块在注册表里就说明它所属的模组已经加载；
//   模组一旦卸载，它的方块连注册表都进不去（蓝图里的 id 会解析成空气），所以"注册表里有 = 模组装了"。
//
// 第二步 · 是不是"依赖性"方块（只挑真正需要的，不是全部模组方块）
//   用原版自己的方法声明判定，不猜模组语义：
//     ① canSurvive 在本方块或它的模组父类里声明   → 它的存在依赖邻居（作物 / 火把 / 梯子 / 藤蔓 / 甘蔗 …）
//     ② getStateForPlacement 在本方块或它的模组父类里声明 → 它按放置环境决定状态（依附朝向 / 半砖形态 …）
//   命中原版"声明了却不需要支撑"的父类时不算（NOT_DEPENDENT_PARENTS：台阶 / 楼梯 / 墙 / 栅栏 / 树叶 /
//   草方块 / 原木 …）—— 模组扩展这些做建材，不该被拖到第二轮。名单来自对本版本全部
//   net.minecraft.world.level.block 类的方法声明扫描（javap），不是猜的。
//
// 第三步 · 轮次
//   命中前两步 → 第二轮（与依附方块、流体同轮）：先让第一轮把原版支撑放完，模组方块再落，缺件明显更少。
//   其余模组方块（纯建材、纯装饰、由原版 LadderBlock 这类父类已经覆盖的）照旧留在第一轮。
//   玩家要某个方块回到第一轮，在 NeoSim/Json/compat/modded_blocks.json 里加一条
//   { "id": "create:casing", "exclude": true } 即可（id / namespace / tag / regex 四种匹配）。
//
// 扫描统计写在 _state/loaded-compat-modded_blocks.json，不往玩家文件里灌几千条 id。
public final class ModBlockRegistry
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final String CATEGORY = "compat";
	public static final String FILE_NAME = "modded_blocks.json";

	// 原版命名空间：不算模组方块
	private static final String VANILLA_NS = "minecraft";

	// 原版"声明了 canSurvive / getStateForPlacement，却本质上不需要支撑"的父类。
	private static final Set<String> NOT_DEPENDENT_PARENTS = Set.of(
			"net.minecraft.world.level.block.Block",
			"net.minecraft.world.level.block.SlabBlock",
			"net.minecraft.world.level.block.StairBlock",
			"net.minecraft.world.level.block.WallBlock",
			"net.minecraft.world.level.block.FenceBlock",
			"net.minecraft.world.level.block.FenceGateBlock",
			"net.minecraft.world.level.block.IronBarsBlock",
			"net.minecraft.world.level.block.RotatedPillarBlock",
			"net.minecraft.world.level.block.LeavesBlock",
			"net.minecraft.world.level.block.SnowyDirtBlock",
			"net.minecraft.world.level.block.WaterloggedTransparentBlock",
			"net.minecraft.world.level.block.HugeMushroomBlock",
			"net.minecraft.world.level.block.MangroveRootsBlock",
			"net.minecraft.world.level.block.GlazedTerracottaBlock",
			"net.minecraft.world.level.block.NoteBlock",
			"net.minecraft.world.level.block.RedstoneLampBlock",
			"net.minecraft.world.level.block.InfestedRotatedPillarBlock",
			"net.minecraft.world.level.block.ChestBlock",
			"net.minecraft.world.level.block.BarrelBlock",
			"net.minecraft.world.level.block.EndRodBlock");

	// 判定理由（GUI / 日志用）
	public static final String REASON_SURVIVE = "canSurvive";
	public static final String REASON_PLACEMENT = "getStateForPlacement";

	// 一条排除规则：命中即把该方块移回第一轮（默认 exclude 缺省为 true）
	public record ConfigRule(BlockMatcher matcher, boolean exclude, String note)
	{
		public String expression()
		{
			return matcher.expression();
		}

		@Nullable
		public static ConfigRule fromJson(JsonObject obj, int index)
		{
			BlockMatcher matcher = BlockMatcher.fromJson(obj);
			if (matcher == null)
			{
				LOGGER.warn("NeoSim-ModBlockRegistry: rule #{} has no match key (id/namespace/tag/regex) — skipped", index);
				return null;
			}
			boolean exclude = !obj.has("exclude") || obj.get("exclude").getAsBoolean();
			String note = obj.has("note") && obj.get("note").isJsonPrimitive() ? obj.get("note").getAsString() : "";
			return new ConfigRule(matcher, exclude, note);
		}

		public JsonObject toJson()
		{
			JsonObject obj = new JsonObject();
			matcher.writeTo(obj);
			obj.addProperty("exclude", exclude);
			if (!note.isEmpty()) obj.addProperty("note", note);
			return obj;
		}
	}

	// 扫描统计（GUI 状态行 / _state）
	public record Stats(int moddedBlocks, int dependentBlocks, int excludedDeferred, int namespaces,
						int configRules, long scannedAtMillis) {}

	private static final List<ConfigRule> RULES = new ArrayList<>();

	// Class -> 判定理由（null = 不是依赖性方块）：一次判定覆盖整类方块，建造主循环每格只取一次表
	private static final IdentityHashMap<Class<?>, String> CLASS_REASON_CACHE = new IdentityHashMap<>();

	private static volatile Stats stats = new Stats(0, 0, 0, 0, 0, 0L);
	private static volatile boolean loaded;

	private ModBlockRegistry()
	{
	}

	// ---- 查询 ----

	public static void ensureLoaded()
	{
		if (!loaded) reload();
	}

	// 是否模组方块：命名空间非原版、非本模组
	public static boolean isModded(@Nullable Block block)
	{
		return isModdedNamespace(namespaceOf(block));
	}

	// 某个命名空间是否属于模组方块（原版 / 本模组 / 空一律 false）
	public static boolean isModdedNamespace(@Nullable String namespace)
	{
		if (namespace == null || namespace.isEmpty()) return false;
		if (namespace.equals(VANILLA_NS)) return false;
		return !namespace.equals(NeoSim.MOD_ID);
	}

	// 本轮判定主入口：依赖性模组方块且未被排除 → 排第二轮（按方块类缓存理由）
	public static boolean isDeferredBlock(Block block)
	{
		return reason(block) != null && !isExcluded(block);
	}

	// 依赖性判定：返回命中的理由（canSurvive / getStateForPlacement），不是依赖性方块返回 null
	@Nullable
	public static String reason(@Nullable Block block)
	{
		if (block == null) return null;
		if (!isModded(block)) return null;
		ensureLoaded();
		Class<?> type = block.getClass();
		IdentityHashMap<Class<?>, String> cache = CLASS_REASON_CACHE;
		if (cache.containsKey(type)) return cache.get(type);
		String value = classify(type);
		cache.put(type, value);
		return value;
	}

	// 是否被玩家写进 modded_blocks.json 排除（回到第一轮）
	public static boolean isExcluded(Block block)
	{
		for (ConfigRule rule : RULES)
		{
			if (rule.exclude() && rule.matcher().matches(block)) return true;
		}
		return false;
	}

	public static Stats stats()
	{
		ensureLoaded();
		return stats;
	}

	// 全部配置规则（内置 + 外部）
	public static synchronized List<ConfigRule> rules()
	{
		ensureLoaded();
		return List.copyOf(RULES);
	}

	// ---- 判定 ----

	// 从方块类往上走：只认"自己声明的"，走到原版类就停，并用 NOT_DEPENDENT_PARENTS 把建材父类挡掉
	@Nullable
	private static String classify(Class<?> type)
	{
		for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass())
		{
			boolean modClass = isModClass(t);
			// 走到原版建材父类（台阶 / 楼梯 / 树叶 …）就收工：它们声明的 canSurvive / getStateForPlacement
			// 是「形态 / 朝向」性质，不需要邻居支撑，模组扩展它们做建材不该被拖进第二轮
			if (!modClass && NOT_DEPENDENT_PARENTS.contains(t.getName())) return null;
			if (declares(t, "canSurvive", net.minecraft.world.level.LevelReader.class, net.minecraft.core.BlockPos.class))
			{
				return REASON_SURVIVE;
			}
			if (declares(t, "getStateForPlacement", net.minecraft.world.item.context.BlockPlaceContext.class))
			{
				return REASON_PLACEMENT;
			}
			if (!modClass) return null;
		}
		return null;
	}

	// 类 t 自己是否声明了该签名的方法（javap 口径：只看声明，不查继承）
	private static boolean declares(Class<?> t, String name, Class<?>... params)
	{
		try
		{
			// getDeclaredMethod 抛 NoSuchMethodException = 本类没声明，正是我们想知道的
			t.getDeclaredMethod(name, params);
			return true;
		}
		catch (Throwable ignored)
		{
			return false;
		}
	}

	// 模组类：不在 net.minecraft 包下的实现类（反射代理 / 匿名类按调用方包名算，同样算模组）
	private static boolean isModClass(Class<?> t)
	{
		String name = t.getName();
		return !name.startsWith("net.minecraft.");
	}

	// ---- 加载与重载 ----

	public static boolean isLoaded()
	{
		return loaded;
	}

	public static boolean reloadIfChanged()
	{
		return com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
	}

	public static synchronized void reload()
	{
		// 首次运行落盘模板：玩家打开 NeoSim/Json/compat/modded_blocks.json 即可排除某些方块
		JsonContent.ensureExternal(CATEGORY, FILE_NAME);
		JsonContent.clearErrors();

		RULES.clear();
		CLASS_REASON_CACHE.clear();
		int builtinCount = parseRules(JsonContent.readBuiltin(CATEGORY, FILE_NAME));
		int externalCount = parseRules(JsonContent.readExternal(CATEGORY, FILE_NAME));

		// 扫描注册表：模组方块总数 / 其中真正有依赖的数量 / 被规则排除掉的依赖性方块数
		int total = 0;
		int modded = 0;
		int dependent = 0;
		int excludedDeferred = 0;
		HashSet<String> namespaces = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK)
		{
			total++;
			String ns = namespaceOf(block);
			if (!isModdedNamespace(ns)) continue;
			modded++;
			namespaces.add(ns);
			String why = reason(block);
			if (why == null) continue;
			dependent++;
			if (isExcluded(block)) excludedDeferred++;
		}

		loaded = true;
		stats = new Stats(modded, dependent, excludedDeferred, namespaces.size(),
				builtinCount + externalCount, System.currentTimeMillis());
		writeState();
		LOGGER.info("NeoSim-ModBlockRegistry: {} modded block(s) in {} namespace(s); {} of them are dependent "
						+ "(custom canSurvive / getStateForPlacement) and go to build round 2 ({} excluded) — {} rule(s)",
				modded, namespaces.size(), dependent, excludedDeferred, builtinCount + externalCount);
	}

	private static int parseRules(@Nullable JsonObject root)
	{
		if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) return 0;
		JsonArray arr = root.getAsJsonArray("rules");
		int count = 0;
		for (int i = 0; i < arr.size(); i++)
		{
			if (!arr.get(i).isJsonObject()) continue;
			ConfigRule rule = ConfigRule.fromJson(arr.get(i).getAsJsonObject(), i);
			if (rule == null) continue;
			if (!rule.exclude()) continue;
			RULES.add(rule);
			count++;
		}
		return count;
	}

	// 注册名（解析失败返回 null）
	@Nullable
	private static String namespaceOf(@Nullable Block block)
	{
		if (block == null) return null;
		try
		{
			ResourceLocation id = block.builtInRegistryHolder().key().location();
			return id.getNamespace().toLowerCase(Locale.ROOT);
		}
		catch (Throwable t)
		{
			return null;
		}
	}

	// ---- GUI 保存 ----

	// 用 GUI 中的外部规则整体覆盖外部文件，随后重载
	public static synchronized boolean saveExternalRules(List<ConfigRule> externalRules, String comment)
	{
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("_comment", comment);
		JsonArray arr = new JsonArray();
		for (ConfigRule rule : externalRules)
		{
			arr.add(rule.toJson());
		}
		root.add("rules", arr);

		boolean ok = JsonContent.save(CATEGORY, FILE_NAME, root);
		reload();
		return ok;
	}

	// GUI 用：给某个方块补一条"排除"草稿规则
	public static ConfigRule draftExcludeRule(String blockId)
	{
		JsonObject obj = new JsonObject();
		BlockMatcher.ofId(blockId).writeTo(obj);
		obj.addProperty("exclude", true);
		return ConfigRule.fromJson(obj, 0);
	}

	private static void writeState()
	{
		Stats s = stats;
		JsonObject state = new JsonObject();
		state.addProperty("file", "NeoSim/Json/" + CATEGORY + "/" + FILE_NAME);
		state.addProperty("moddedBlocks", s.moddedBlocks());
		state.addProperty("dependentBlocks", s.dependentBlocks());
		state.addProperty("excludedDeferred", s.excludedDeferred());
		state.addProperty("namespaces", s.namespaces());
		state.addProperty("configRules", s.configRules());
		state.addProperty("scannedAtMillis", s.scannedAtMillis());
		JsonContent.writeState(state, "compat-modded_blocks");
	}
}
