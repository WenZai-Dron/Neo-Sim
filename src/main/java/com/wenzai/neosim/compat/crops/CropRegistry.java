package com.wenzai.neosim.compat.crops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.json.BlockMatcher;
import com.wenzai.neosim.json.JsonContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

// 模组作物表：扫描已装模组的注册表自动发现可种植作物，并把结果写进 NeoSim/Json/compat/crops.json。
// 判定：种子为 BlockItem && 方块可骨粉催熟 && 方块状态含 age 生长属性；排除原版命名空间（原版作物走 FarmTask 枚举）。
// 覆盖规则（成熟目标 / 需水 / 排除 / 停用）来自 jar 内置 JSON + 外部 JSON，外部与后写者优先。
public final class CropRegistry
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final String CATEGORY = "compat";
	public static final String FILE_NAME = "crops.json";

	// 生效的作物条目（已应用规则；被排除的不在内）
	private static volatile List<CropEntry> cached = null;

	// 扫描结果（原始检测，未应用规则）——用于 GUI 展示与写回 JSON
	public static volatile List<Detected> detected = List.of();

	private static final List<CropRule> RULES = new ArrayList<>();

	public record Detected(String blockId, String seedId, String namespace, Block block, Item seed,
						   String matureId, boolean needsWater, boolean excluded, boolean enabled) {}

	public record Stats(int builtinRules, int externalRules, int detectedCrops, int effectiveCrops, long loadedAtMillis) {}

	private static volatile Stats stats = new Stats(0, 0, 0, 0, 0L);

	private CropRegistry()
	{
	}

	// ---- 查询 ----

	public static synchronized List<CropEntry> all()
	{
		if (cached == null) rebuild();
		return cached;
	}

	// 可种植作物（排除需水与已排除条目）
	public static List<CropEntry> plantable()
	{
		return all().stream().filter(e -> !e.needsWater()).toList();
	}

	@Nullable
	public static CropEntry findByPlantBlock(Block block)
	{
		if (block == null) return null;
		for (CropEntry e : all())
		{
			if (e.plantBlock() == block) return e;
		}
		return null;
	}

	// 按成熟目标方块查找（两阶段作物的成株）
	@Nullable
	public static CropEntry findByMatureBlock(Block block)
	{
		if (block == null) return null;
		for (CropEntry e : all())
		{
			if (e.matureBlock() == block) return e;
		}
		return null;
	}

	@Nullable
	public static CropEntry findBySeed(Item seed)
	{
		if (seed == null) return null;
		for (CropEntry e : all())
		{
			if (e.seed() == seed) return e;
		}
		return null;
	}

	// 注册名是否已检测（持久化 token 校验用）
	public static boolean isKnown(String plantBlockId)
	{
		return findByPlantBlockId(plantBlockId) != null;
	}

	@Nullable
	public static CropEntry findByPlantBlockId(String plantBlockId)
	{
		ResourceLocation id = ResourceLocation.tryParse(plantBlockId);
		if (id == null) return null;
		return findByPlantBlock(BuiltInRegistries.BLOCK.get(id));
	}

	public static Stats stats()
	{
		all();
		return stats;
	}

	// 全部覆盖规则（内置 + 外部，按生效顺序）
	public static synchronized List<CropRule> rules()
	{
		all();
		return List.copyOf(RULES);
	}

	// 仅外部规则（GUI 编辑对象）
	public static synchronized List<CropRule> externalRules()
	{
		all();
		List<CropRule> out = new ArrayList<>();
		for (CropRule rule : RULES)
		{
			if (rule.source() == CropRule.Source.EXTERNAL) out.add(rule);
		}
		return out;
	}

	// ---- 加载与重载 ----

	// 数据包/文件变化后强制重扫
	public static synchronized void invalidate()
	{
		cached = null;
	}

	// 强制重载（统一热重载入口调用）
	public static synchronized void reload()
	{
		rebuild();
	}

	// 统一走 ContentReloader：目录变化时两张表一起重载
	public static boolean reloadIfChanged()
	{
		return com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
	}

	// 手动重新扫描并写回 JSON（GUI「重新扫描」按钮）
	public static synchronized void rescan()
	{
		rebuild();
	}

	private static void rebuild()
	{
		JsonContent.ensureExternal(CATEGORY, FILE_NAME);

		RULES.clear();
		int builtinCount = parseRules(JsonContent.readBuiltin(CATEGORY, FILE_NAME), CropRule.Source.BUILTIN);
		int externalCount = parseRules(JsonContent.readExternal(CATEGORY, FILE_NAME), CropRule.Source.EXTERNAL);

		List<Detected> found = scan();
		List<Detected> applied = new ArrayList<>();
		List<CropEntry> entries = new ArrayList<>();
		int needsWaterCount = 0;
		for (Detected raw : found)
		{
			Detected result = applyRules(raw);
			applied.add(result);
			if (result.excluded()) continue;
			Block mature = raw.block();
			if (result.matureId() != null)
			{
				ResourceLocation matureId = ResourceLocation.tryParse(result.matureId());
				Block resolved = matureId != null ? BuiltInRegistries.BLOCK.get(matureId) : null;
				if (resolved != null && resolved != net.minecraft.world.level.block.Blocks.AIR) mature = resolved;
			}
			if (result.needsWater()) needsWaterCount++;
			entries.add(new CropEntry(raw.seed(), raw.block(), mature, result.needsWater(), raw.namespace()));
		}
		entries.sort(Comparator.comparing(e -> e.plantBlockId().toString()));

		// detected：应用规则后的生效状态（GUI 直接展示）；写回 JSON 的是原始扫描结果
		detected = List.copyOf(applied);
		cached = List.copyOf(entries);
		stats = new Stats(builtinCount, externalCount, found.size(), entries.size(), System.currentTimeMillis());

		// 自动检测结果写回外部 JSON（仅在内容变化时写，避免自我触发重载循环）
		writeDetected(found);
		writeState();

		LOGGER.info("NeoSim-CropRegistry: discovered {} mod crops ({} plantable / {} need water),{} rules from file",
				found.size(), entries.size() - needsWaterCount, needsWaterCount, builtinCount + externalCount);
	}

	// 逐条应用规则：后写覆盖先写；未声明的字段保持原值
	private static Detected applyRules(Detected raw)
	{
		String matureId = null;
		boolean needsWater = false;
		boolean excluded = false;
		boolean enabled = true;

		for (CropRule rule : RULES)
		{
			if (!rule.matches(raw.block())) continue;
			if (rule.matureId() != null) matureId = rule.matureId();
			if (rule.needsWater() != null) needsWater = rule.needsWater();
			if (rule.excluded() != null) excluded = rule.excluded();
			if (rule.enabled() != null) enabled = rule.enabled();
		}

		// 玩家停用的作物（enabled=false）按"排除"处理
		return new Detected(raw.blockId(), raw.seedId(), raw.namespace(), raw.block(), raw.seed(),
				matureId, needsWater, excluded || !enabled, enabled);
	}

	private static int parseRules(@Nullable JsonObject root, CropRule.Source source)
	{
		if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) return 0;
		JsonArray arr = root.getAsJsonArray("rules");
		int count = 0;
		for (int i = 0; i < arr.size(); i++)
		{
			if (!arr.get(i).isJsonObject()) continue;
			CropRule rule = CropRule.fromJson(arr.get(i).getAsJsonObject(), source, i);
			if (rule == null) continue;
			RULES.add(rule);
			count++;
		}
		return count;
	}

	// 扫描注册表：BlockItem && 可骨粉催熟 && 含 age 生长属性 && 非原版
	private static List<Detected> scan()
	{
		List<Detected> out = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM)
		{
			if (!(item instanceof BlockItem blockItem)) continue;
			Block block = blockItem.getBlock();
			if (block == null) continue;
			ResourceLocation id = block.builtInRegistryHolder().key().location();
			if (id.getNamespace().equals("minecraft")) continue;
			if (!(block instanceof BonemealableBlock)) continue;
			if (!hasAgeProperty(block)) continue;

			out.add(new Detected(id.toString(), item.builtInRegistryHolder().key().location().toString(),
					id.getNamespace(), block, item, null, false, false, true));
		}
		out.sort(Comparator.comparing(Detected::blockId));
		return out;
	}

	// 方块状态是否含 age 生长属性（CropBlock / BuddingBushBlock / RiceBlock 等均带）
	private static boolean hasAgeProperty(Block block)
	{
		return block.defaultBlockState().getProperties().stream()
				.anyMatch(p -> p.getName().equals("age"));
	}

	// ---- 写回 JSON ----

	// 检测列表写回外部文件的 detected 段；内容未变化时不写（避免自我触发重载）
	private static synchronized void writeDetected(List<Detected> found)
	{
		JsonObject existing = JsonContent.readExternal(CATEGORY, FILE_NAME);
		JsonObject root = existing != null ? existing : new JsonObject();

		if (root.has("detected") && root.get("detected").isJsonArray()
				&& sameDetected(root.getAsJsonArray("detected"), found))
		{
			return;
		}

		JsonArray arr = new JsonArray();
		for (Detected d : found)
		{
			JsonObject obj = new JsonObject();
			obj.addProperty("id", d.blockId());
			obj.addProperty("seed", d.seedId());
			obj.addProperty("namespace", d.namespace());
			arr.add(obj);
		}

		root.addProperty("version", root.has("version") ? root.get("version").getAsInt() : 1);
		if (!root.has("_comment"))
		{
			root.addProperty("_comment",
					"模组作物表：detected 由模组自动扫描写入（新增模组后自动更新，玩家不必手写）；"
							+ "rules 是覆盖规则：mature 成熟目标 / needsWater 需水 / excluded 排除 / enabled 停用。规则后写覆盖先写。");
		}
		if (!root.has("rules")) root.add("rules", new JsonArray());
		root.add("detected", arr);

		JsonContent.save(CATEGORY, FILE_NAME, root);
		LOGGER.info("NeoSim-CropRegistry: wrote {} detected crop(s) to {}",
				found.size(), "NeoSim/Json/" + CATEGORY + "/" + FILE_NAME);
	}

	private static boolean sameDetected(JsonArray arr, List<Detected> found)
	{
		if (arr.size() != found.size()) return false;
		for (int i = 0; i < arr.size(); i++)
		{
			if (!arr.get(i).isJsonObject()) return false;
			JsonObject obj = arr.get(i).getAsJsonObject();
			Detected d = found.get(i);
			if (!d.blockId().equals(getString(obj, "id"))) return false;
			if (!d.seedId().equals(getString(obj, "seed"))) return false;
		}
		return true;
	}

	private static String getString(JsonObject obj, String key)
	{
		return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
	}

	// GUI 保存：用给定外部规则整体覆盖文件的 rules 段（detected 段保留）
	public static synchronized boolean saveExternalRules(List<CropRule> externalRules, String comment)
	{
		JsonObject root = JsonContent.readExternal(CATEGORY, FILE_NAME);
		if (root == null) root = new JsonObject();
		root.addProperty("version", root.has("version") ? root.get("version").getAsInt() : 1);
		root.addProperty("_comment", comment);

		JsonArray arr = new JsonArray();
		for (CropRule rule : externalRules) arr.add(rule.toJson());
		root.add("rules", arr);

		boolean ok = JsonContent.save(CATEGORY, FILE_NAME, root);
		rebuild();
		return ok;
	}

	// GUI 用：为某个作物方块补一条外部规则（默认全部字段未声明，交由玩家逐个开关）
	public static synchronized CropRule draftRule(String blockId, int index)
	{
		JsonObject obj = new JsonObject();
		BlockMatcher.ofId(blockId).writeTo(obj);
		return CropRule.fromJson(obj, CropRule.Source.EXTERNAL, index);
	}

	private static void writeState()
	{
		JsonObject state = new JsonObject();
		state.addProperty("file", "NeoSim/Json/" + CATEGORY + "/" + FILE_NAME);
		state.addProperty("builtinRules", stats.builtinRules());
		state.addProperty("externalRules", stats.externalRules());
		state.addProperty("detectedCrops", stats.detectedCrops());
		state.addProperty("effectiveCrops", stats.effectiveCrops());
		state.addProperty("loadedAtMillis", stats.loadedAtMillis());
		JsonContent.writeState(state, "crops");
	}
}
