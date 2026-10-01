package com.wenzai.neosim.compat.attached;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.json.JsonContent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

// 依附性方块表：
//   ① 内置类型规则（instanceof 注册）
//   ② 数据规则（jar 内置 JSON + NeoSim/Json/compat/attached_blocks.json 外部覆盖）
// 合并结果按方块注册 id 缓存成字节数组，重载时整体重建
public final class AttachedBlockTable
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final String CATEGORY = "compat";
	public static final String FILE_NAME = "attached_blocks.json";

	// 内置类型规则：label 供 GUI 展示，顺序即优先级（先命中先算）
	public record TypeRule(String label, Predicate<Block> test, AttachMode mode, boolean connective) {}

	public record Stats(int builtinRules, int externalRules, int typeRules, int attachedBlocks,
						int connectiveBlocks, int totalBlocks, long loadedAtMillis) {}

	private record Decision(AttachMode mode, int phase, boolean connective) {}

	private static final List<TypeRule> TYPE_RULES = buildTypeRules();

	private static final List<AttachedRule> DATA_RULES = new ArrayList<>();

	// 贴墙类默认朝向属性名
	private static final List<String> DEFAULT_FACING = List.of("facing", "horizontal_facing");

	private static volatile byte[] modeById = new byte[0];
	private static volatile byte[] phaseById = new byte[0];
	private static volatile BitSet connectiveById = new BitSet();
	private static volatile Stats stats = new Stats(0, 0, TYPE_RULES.size(), 0, 0, 0, 0L);
	private static volatile boolean loaded;

	private AttachedBlockTable()
	{
	}

	// ---- 查询 ----

	public static void ensureLoaded()
	{
		if (!loaded) reload();
	}

	public static AttachMode mode(BlockState state)
	{
		return mode(state.getBlock());
	}

	public static AttachMode mode(@Nullable Block block)
	{
		ensureLoaded();
		if (block == null) return AttachMode.AUTO;
		byte[] table = modeById;
		int id = idOf(block);
		if (id < 0 || id >= table.length) return AttachMode.AUTO;
		AttachMode[] values = AttachMode.values();
		int ord = table[id] - 1;
		return ord >= 0 && ord < values.length ? values[ord] : AttachMode.AUTO;
	}

	public static int phase(BlockState state)
	{
		ensureLoaded();
		byte[] table = phaseById;
		int id = idOf(state.getBlock());
		if (id < 0 || id >= table.length) return AttachMode.AUTO.phase();
		return table[id] == 2 ? 2 : 1;
	}

	// 依附性方块（第二轮放置）
	public static boolean isAttached(BlockState state)
	{
		return phase(state) == 2;
	}

	// 连接性方块（放置时按实际相邻方块重算连接）
	public static boolean isConnective(BlockState state)
	{
		ensureLoaded();
		int id = idOf(state.getBlock());
		return id >= 0 && connectiveById.get(id);
	}

	// 是否做 canSurvive 兜底预检
	public static boolean precheck(BlockState state)
	{
		return mode(state).precheck();
	}

	// 贴墙类方块的朝向属性名：命中规则自带 facing 优先，否则用默认集
	public static List<String> facingProperties(@Nullable Block block)
	{
		ensureLoaded();
		if (block != null)
		{
			List<String> hit = null;
			for (AttachedRule rule : DATA_RULES)
			{
				if (rule.matches(block) && !rule.facing().isEmpty()) hit = rule.facing();
			}
			if (hit != null) return hit;
		}
		return DEFAULT_FACING;
	}

	public static List<TypeRule> typeRules()
	{
		return TYPE_RULES;
	}

	// 全部数据规则（内置 + 外部，按生效顺序）
	public static synchronized List<AttachedRule> rules()
	{
		ensureLoaded();
		return List.copyOf(DATA_RULES);
	}

	// 仅外部规则（GUI 编辑对象）
	public static synchronized List<AttachedRule> externalRules()
	{
		ensureLoaded();
		List<AttachedRule> out = new ArrayList<>();
		for (AttachedRule rule : DATA_RULES)
		{
			if (rule.source() == AttachedRule.Source.EXTERNAL) out.add(rule);
		}
		return out;
	}

	public static Stats stats()
	{
		ensureLoaded();
		return stats;
	}

	// ---- 加载与重载 ----

	public static boolean isLoaded()
	{
		return loaded;
	}

	// 统一走 ContentReloader：目录变化时两张表一起重载，避免各自消费 mtime
	public static boolean reloadIfChanged()
	{
		return com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
	}

	public static synchronized void reload()
	{
		// 首次运行落盘模板：玩家打开 NeoSim/Json/compat/attached_blocks.json 即可编辑
		JsonContent.ensureExternal(CATEGORY, FILE_NAME);
		JsonContent.clearErrors();

		DATA_RULES.clear();
		int builtinCount = parseRules(JsonContent.readBuiltin(CATEGORY, FILE_NAME), AttachedRule.Source.BUILTIN);
		int externalCount = parseRules(JsonContent.readExternal(CATEGORY, FILE_NAME), AttachedRule.Source.EXTERNAL);

		Registry<Block> registry = BuiltInRegistries.BLOCK;
		int size = registry.size();
		byte[] modes = new byte[size];
		byte[] phases = new byte[size];
		BitSet connective = new BitSet(size);
		Arrays.fill(modes, (byte) (AttachMode.AUTO.ordinal() + 1));

		int attached = 0;
		int connectiveCount = 0;
		for (Block block : registry)
		{
			int id = idOf(block);
			if (id < 0 || id >= size) continue;
			Decision decision = classify(block);
			modes[id] = (byte) (decision.mode().ordinal() + 1);
			phases[id] = (byte) decision.phase();
			if (decision.phase() == 2) attached++;
			if (decision.connective())
			{
				connective.set(id);
				connectiveCount++;
			}
		}

		modeById = modes;
		phaseById = phases;
		connectiveById = connective;
		stats = new Stats(builtinCount, externalCount, TYPE_RULES.size(), attached, connectiveCount,
				size, System.currentTimeMillis());
		loaded = true;
		writeState();

		LOGGER.info("NeoSim-AttachedBlockTable: loaded {} builtin + {} external rules ({} type rules) — "
						+ "{} attached / {} connective of {} blocks",
				builtinCount, externalCount, TYPE_RULES.size(), attached, connectiveCount, size);
	}

	private static int parseRules(@Nullable JsonObject root, AttachedRule.Source source)
	{
		if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) return 0;
		JsonArray arr = root.getAsJsonArray("rules");
		int count = 0;
		for (int i = 0; i < arr.size(); i++)
		{
			if (!arr.get(i).isJsonObject()) continue;
			AttachedRule rule = AttachedRule.fromJson(arr.get(i).getAsJsonObject(), source, i);
			if (rule == null) continue;
			DATA_RULES.add(rule);
			count++;
		}
		return count;
	}

	private static Decision classify(Block block)
	{
		// 内置类型规则：给默认值与连接性
		AttachMode typeMode = null;
		boolean typeConnective = false;
		for (TypeRule type : TYPE_RULES)
		{
			if (type.test().test(block))
			{
				typeMode = type.mode();
				typeConnective = type.connective();
				break;
			}
		}

		// 数据规则：后写覆盖先写
		AttachMode dataMode = null;
		int dataPhase = 0;
		boolean dataConnective = false;
		for (AttachedRule rule : DATA_RULES)
		{
			if (rule.matches(block))
			{
				dataMode = rule.mode();
				dataPhase = rule.phase();
				dataConnective = rule.connective();
			}
		}

		AttachMode mode = dataMode != null ? dataMode : (typeMode != null ? typeMode : AttachMode.AUTO);
		int phase = dataPhase > 0 ? dataPhase : mode.phase();
		return new Decision(mode, phase, dataConnective || typeConnective);
	}

	// ---- GUI 保存 ----

	// 用 GUI 中的外部规则整体覆盖外部文件，随后重载
	public static synchronized boolean saveExternalRules(List<AttachedRule> externalRules, String comment)
	{
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("_comment", comment);
		JsonArray arr = new JsonArray();
		for (AttachedRule rule : externalRules)
		{
			arr.add(rule.toJson());
		}
		root.add("rules", arr);

		boolean ok = JsonContent.save(CATEGORY, FILE_NAME, root);
		reload();
		return ok;
	}

	private static void writeState()
	{
		JsonObject state = new JsonObject();
		state.addProperty("file", "NeoSim/Json/" + CATEGORY + "/" + FILE_NAME);
		state.addProperty("builtinRules", stats.builtinRules());
		state.addProperty("externalRules", stats.externalRules());
		state.addProperty("typeRules", stats.typeRules());
		state.addProperty("attachedBlocks", stats.attachedBlocks());
		state.addProperty("connectiveBlocks", stats.connectiveBlocks());
		state.addProperty("totalBlocks", stats.totalBlocks());
		state.addProperty("loadedAtMillis", stats.loadedAtMillis());
		JsonContent.writeState(state, "compat-attached_blocks");
	}

	private static int idOf(@Nullable Block block)
	{
		if (block == null) return -1;
		try
		{
			return BuiltInRegistries.BLOCK.getId(block);
		}
		catch (Throwable t)
		{
			return -1;
		}
	}

	// 内置类型规则：两张 instanceof 清单（贴墙类在前，地面类在后，含连接性方块）
	private static List<TypeRule> buildTypeRules()
	{
		List<TypeRule> rules = new ArrayList<>();

		// 贴墙类（需要水平支撑 + FACING 修正）
		rules.add(new TypeRule("WallTorchBlock", b -> b instanceof WallTorchBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("RedstoneWallTorchBlock", b -> b instanceof RedstoneWallTorchBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("LadderBlock", b -> b instanceof LadderBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("WallSignBlock", b -> b instanceof WallSignBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("ButtonBlock", b -> b instanceof ButtonBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("LeverBlock", b -> b instanceof LeverBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("TripWireHookBlock", b -> b instanceof TripWireHookBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("CocoaBlock", b -> b instanceof CocoaBlock, AttachMode.WALL, false));
		rules.add(new TypeRule("VineBlock", b -> b instanceof VineBlock, AttachMode.WALL, false));

		// 地面类（需要下方支撑）
		rules.add(new TypeRule("DoorBlock", b -> b instanceof DoorBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("BaseRailBlock", b -> b instanceof BaseRailBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("TorchBlock", b -> b instanceof TorchBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("StandingSignBlock", b -> b instanceof StandingSignBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("PressurePlateBlock", b -> b instanceof PressurePlateBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("RedStoneWireBlock", b -> b instanceof RedStoneWireBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("CropBlock", b -> b instanceof CropBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("StemBlock", b -> b instanceof StemBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("AttachedStemBlock", b -> b instanceof AttachedStemBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("SaplingBlock", b -> b instanceof SaplingBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("BushBlock", b -> b instanceof BushBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("SugarCaneBlock", b -> b instanceof SugarCaneBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("FlowerPotBlock", b -> b instanceof FlowerPotBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("BannerBlock", b -> b instanceof BannerBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("BedBlock", b -> b instanceof BedBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("TripWireBlock", b -> b instanceof TripWireBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("SnowLayerBlock", b -> b instanceof SnowLayerBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("AnvilBlock", b -> b instanceof AnvilBlock, AttachMode.GROUND, false));
		rules.add(new TypeRule("CarpetBlock", b -> b instanceof CarpetBlock, AttachMode.GROUND, false));

		// 连接性方块（墙/栅栏/铁栏杆/玻璃板及其模组子类）
		rules.add(new TypeRule("WallBlock", b -> b instanceof WallBlock, AttachMode.GROUND, true));
		rules.add(new TypeRule("CrossCollisionBlock", b -> b instanceof CrossCollisionBlock, AttachMode.GROUND, true));
		rules.add(new TypeRule("FenceBlock", b -> b instanceof FenceBlock, AttachMode.GROUND, true));
		rules.add(new TypeRule("IronBarsBlock", b -> b instanceof IronBarsBlock, AttachMode.GROUND, true));
		rules.add(new TypeRule("StainedGlassPaneBlock", b -> b instanceof StainedGlassPaneBlock, AttachMode.GROUND, true));

		return List.copyOf(rules);
	}
}
