package com.wenzai.neosim.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.compat.modded.ModBlockRegistry;
import com.wenzai.neosim.json.BlockMatcher;
import com.wenzai.neosim.json.JsonContent;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

// 材料消耗表（NeoSim/Json/building/materials.json）：
//  ① 创造模式（mode=2）：全部方块免费，本表与内置分类一律不生效（先给创造短路，规则表管不到它）
//  ② 自定义规则：命中的方块按规则声明决定"哪个模式耗材"，模式内后写的规则覆盖先写的（一个模式一条结论）
//  ③ 未命中任何规则的模组方块：普通 / 困难模式一律耗材——这就是内置规则的默认行为
//  ④ 既没命中规则、又不是模组方块（原版 / 本模组方块）：回落到 MaterialCalculator 的硬编码分类
//
// 与 compat/modded_blocks.json 的分工：那张表管"排第几轮建造"，这张表管"要不要材料"，互不影响。
public final class MaterialRules
{
	// 工具类：只有静态成员，禁止实例化
	private MaterialRules()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();

	public static final String CATEGORY = "building";
	public static final String FILE_NAME = "materials.json";

	// 模式 id（与 ModSavedData 的运行模式一致）：0 未选择 / 1 普通 / 2 创造 / 3 困难
	public static final byte MODE_NONE = 0;
	public static final byte MODE_NORMAL = 1;
	public static final byte MODE_CREATIVE = 2;
	public static final byte MODE_HARDCORE = 3;

	// 结果缓存覆盖的模式数（0~3 全覆盖）
	private static final int MODE_SLOTS = 4;

	// 一条材料规则：命中即对 modes 里的每个模式给出 requires 结论
	public record MaterialRule(BlockMatcher matcher, boolean[] modes, boolean requires, String note)
	{
		public String expression()
		{
			return matcher.expression();
		}

		public boolean appliesTo(byte mode)
		{
			return mode >= 0 && mode < modes.length && modes[mode];
		}

		// 按名称解析模式集合："all" / 单个 mode（normal|creative|hardcore|none）/ 上述逗号分隔
		// 不写 mode 键时默认 all（全部模式）
		@Nullable
		public static boolean[] parseModes(@Nullable String value)
		{
			if (value == null || value.isBlank()) return allModes();

			boolean[] flags = new boolean[MODE_SLOTS];
			for (String part : value.split(","))
			{
				String key = part.trim().toLowerCase(Locale.ROOT);
				if (key.isEmpty()) continue;
				switch (key)
				{
					case "all", "*" -> flags = allModes();
					case "none", "unset" -> flags[MODE_NONE] = true;
					case "normal" -> flags[MODE_NORMAL] = true;
					case "creative" -> flags[MODE_CREATIVE] = true;
					case "hardcore", "hard" -> flags[MODE_HARDCORE] = true;
					default -> LOGGER.warn("NeoSim-MaterialRules: unknown mode '{}' in mode list — ignored", key);
				}
			}
			return flags;
		}

		private static boolean[] allModes()
		{
			boolean[] flags = new boolean[MODE_SLOTS];
			Arrays.fill(flags, true);
			return flags;
		}

		public static boolean[] modesOf(@Nullable JsonObject obj)
		{
			if (obj == null || !obj.has("mode") || !obj.get("mode").isJsonPrimitive()) return allModes();
			return parseModes(obj.get("mode").getAsString());
		}

		@Nullable
		public static MaterialRule fromJson(JsonObject obj, int index)
		{
			BlockMatcher matcher = BlockMatcher.fromJson(obj);
			if (matcher == null)
			{
				LOGGER.warn("NeoSim-MaterialRules: rule #{} has no match key (id/namespace/tag/regex) — skipped", index);
				return null;
			}

			// requires 缺省为 true（规则写了就是为了让它耗材）
			boolean requires = !obj.has("requires") || !obj.get("requires").isJsonPrimitive()
					|| obj.get("requires").getAsBoolean();
			String note = obj.has("note") && obj.get("note").isJsonPrimitive() ? obj.get("note").getAsString() : "";
			return new MaterialRule(matcher, modesOf(obj), requires, note);
		}

		public JsonObject toJson()
		{
			JsonObject obj = new JsonObject();
			matcher.writeTo(obj);
			obj.addProperty("mode", modeText(modes));
			obj.addProperty("requires", requires);
			if (!note.isEmpty()) obj.addProperty("note", note);
			return obj;
		}
	}

	// 模式集合 → 文本（GUI / 回写用）
	public static String modeText(boolean[] modes)
	{
		List<String> names = new ArrayList<>();
		if (modes[MODE_NONE]) names.add("none");
		if (modes[MODE_NORMAL]) names.add("normal");
		if (modes[MODE_CREATIVE]) names.add("creative");
		if (modes[MODE_HARDCORE]) names.add("hardcore");
		if (names.size() == MODE_SLOTS) return "all";
		return String.join(",", names);
	}

	private record Stats(int builtinRules, int externalRules, int moddedBlocks, int forcedBlocks,
						 long loadedAtMillis) {}

	private static final List<MaterialRule> RULES = new ArrayList<>();

	// 每块缓存每个模式的结论：null = 本表对该模式没有结论（原版 / 本模组方块且未命中规则）
	// 用 Boolean 而不是 boolean：「表判定为免费」与「表没判定」是两种结果，混成一个 false 会让
	// 原版方块在普通 / 困难模式下全部免费；重载时整体清空，与 CLASS_CACHE 同步失效
	private static volatile Map<Block, Boolean[]> CACHE = new IdentityHashMap<>();

	private static volatile Stats stats = new Stats(0, 0, 0, 0, 0L);
	private static volatile boolean loaded;

	// ---- 查询 ----

	public static void ensureLoaded()
	{
		if (!loaded) reload();
	}

	// 该方块在这个模式下是否需要材料；返回 null 表示"本表没有结论，交给硬编码分类"
	@Nullable
	public static Boolean requires(@Nullable Block block, byte mode)
	{
		if (block == null) return null;
		ensureLoaded();

		Map<Block, Boolean[]> cache = CACHE;
		Boolean[] slots = cache.get(block);
		if (slots == null)
		{
			slots = compute(block);
			cache.put(block, slots);
		}
		return slots[mode >= 0 && mode < MODE_SLOTS ? mode : 0];
	}

	// 判定（顺序固定，顺序本身不敏感：同模式内后写覆盖先写）：
	//   创造模式：恒 false（全部免费），规则与兜底都不参与；
	//   其余模式：① 命中的规则给出该模式结论就返回；② 否则看是不是模组方块 → 耗材；③ 都不是 → null（本表无结论）
	private static Boolean[] compute(Block block)
	{
		Boolean[] byMode = new Boolean[MODE_SLOTS];

		for (MaterialRule rule : RULES)
		{
			if (!rule.matcher().matches(block)) continue;
			for (int m = 0; m < MODE_SLOTS; m++)
			{
				if (rule.appliesTo((byte) m)) byMode[m] = rule.requires();
			}
		}

		boolean modded = ModBlockRegistry.isModded(block);
		Boolean[] slots = new Boolean[MODE_SLOTS];

		// 创造模式：全部方块免费，规则与兜底都不参与
		slots[MODE_CREATIVE] = Boolean.FALSE;

		for (int m = 0; m < MODE_SLOTS; m++)
		{
			if (m == MODE_CREATIVE) continue;
			Boolean hit = byMode[m];

			// 规则没结论：模组方块兜底耗材；原版 / 本模组方块保持 null，交给 MaterialCalculator 的内置分类
			slots[m] = hit != null ? hit : (modded ? Boolean.TRUE : null);
		}
		return slots;
	}

	public static boolean isLoaded()
	{
		return loaded;
	}

	public static List<MaterialRule> rules()
	{
		ensureLoaded();
		return List.copyOf(RULES);
	}

	public static Stats stats()
	{
		ensureLoaded();
		return stats;
	}

	// 规则表重载后清空判定缓存（与 MaterialCalculator.invalidateClassification 一起调用）
	public static void invalidate()
	{
		CACHE = new IdentityHashMap<>();
	}

	// ---- 加载与重载 ----

	// 统一走 ContentReloader：目录变化时全部内容表一起重载
	public static boolean reloadIfChanged()
	{
		return com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
	}

	public static synchronized void reload()
	{
		// 首次运行落盘模板：玩家打开 NeoSim/Json/building/materials.json 即可编辑
		JsonContent.ensureExternal(CATEGORY, FILE_NAME);
		JsonContent.clearErrors();

		RULES.clear();
		int builtinCount = parseRules(JsonContent.readBuiltin(CATEGORY, FILE_NAME));
		int externalCount = parseRules(JsonContent.readExternal(CATEGORY, FILE_NAME));

		invalidate();
		loaded = true;

		// 统计：模组方块总数、其中"普通 / 困难模式耗材"的数量（只统计一次，不参与每格判定）。
		// 创造模式恒免费，不参与统计
		int modded = 0;
		int forced = 0;
		for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK)
		{
			if (!ModBlockRegistry.isModded(block)) continue;
			modded++;
			if (Boolean.TRUE.equals(requires(block, MODE_NORMAL))
					|| Boolean.TRUE.equals(requires(block, MODE_HARDCORE)))
			{
				forced++;
			}
		}

		stats = new Stats(builtinCount, externalCount, modded, forced, System.currentTimeMillis());
		writeState();
		LOGGER.info("NeoSim-MaterialRules: {} builtin + {} external rule(s); {}/{} modded block(s) require materials "
						+ "in normal / hardcore (creative mode is always free)",
				builtinCount, externalCount, forced, modded);
	}

	private static int parseRules(@Nullable JsonObject root)
	{
		if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) return 0;
		JsonArray arr = root.getAsJsonArray("rules");
		int count = 0;
		for (int i = 0; i < arr.size(); i++)
		{
			if (!arr.get(i).isJsonObject()) continue;
			MaterialRule rule = MaterialRule.fromJson(arr.get(i).getAsJsonObject(), i);
			if (rule == null) continue;

			// 创造模式恒免费：规则里声明的 creative 不生效，提示一次，避免玩家以为写错了
			if (rule.appliesTo(MODE_CREATIVE))
			{
				LOGGER.warn("NeoSim-MaterialRules: rule #{} includes 'creative', but creative mode is always free — "
						+ "that part is ignored", i);
			}
			RULES.add(rule);
			count++;
		}
		return count;
	}

	// ---- GUI 保存 ----

	// 用 GUI 中的外部规则整体覆盖外部文件，随后重载
	public static synchronized boolean saveExternalRules(List<MaterialRule> externalRules, String comment)
	{
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("_comment", comment);
		JsonArray arr = new JsonArray();
		for (MaterialRule rule : externalRules)
		{
			arr.add(rule.toJson());
		}
		root.add("rules", arr);

		boolean ok = JsonContent.save(CATEGORY, FILE_NAME, root);
		reload();
		return ok;
	}

	// GUI 用：给某个方块补一条草稿规则（requires=false 即"这个方块免费"）
	public static MaterialRule draftRule(String blockId, boolean requires)
	{
		JsonObject obj = new JsonObject();
		BlockMatcher.ofId(blockId).writeTo(obj);
		obj.addProperty("mode", "all");
		obj.addProperty("requires", requires);
		return MaterialRule.fromJson(obj, 0);
	}

	private static void writeState()
	{
		Stats s = stats;
		JsonObject state = new JsonObject();
		state.addProperty("file", "NeoSim/Json/" + CATEGORY + "/" + FILE_NAME);
		state.addProperty("builtinRules", s.builtinRules());
		state.addProperty("externalRules", s.externalRules());
		state.addProperty("moddedBlocks", s.moddedBlocks());
		state.addProperty("forcedBlocks", s.forcedBlocks());
		state.addProperty("loadedAtMillis", s.loadedAtMillis());
		JsonContent.writeState(state, "building-materials");
	}
}
