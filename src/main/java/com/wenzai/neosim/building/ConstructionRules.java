package com.wenzai.neosim.building;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.compat.attached.AttachedBlockTable;
import com.wenzai.neosim.json.JsonContent;
import com.wenzai.neosim.schematic.MaterialCalculator;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;

// 建造顺序表（NeoSim/Json/building/rules.json）：决定两轮建造里哪些方块算第二轮专属。
//
// 轮次归属：
//   依附性方块（门 / 火把 / 梯子 / 灯笼 / 藤蔓…）与流体（水 / 岩浆 / 含水方块的水面）默认排第二轮；
//   其余方块排第一轮。第一轮把池底 / 池壁围好，水最后落进容器，不在建造途中先漫开。
//
// 每轮内部的推进顺序与归属无关，固定按 SchematicScanOrder：层 低→高，
// 层内从离玩家最近的一排开始、一排排往面向方向推，每排从左往右。
// 第一轮放不下去的方块（没支撑 / canSurvive 不过 / 材料不够）跳过，由第二轮重试。
public final class ConstructionRules
{
	// 工具类：只有静态成员，禁止实例化
	private ConstructionRules()
	{
	}

	private static final Logger LOGGER = LogUtils.getLogger();

	public static final String CATEGORY = "building";
	public static final String FILE_NAME = "rules.json";

	private static volatile boolean deferAttached = true;
	private static volatile boolean deferFluids = true;
	private static volatile boolean loaded;

	public static void ensureLoaded()
	{
		if (!loaded) reload();
	}

	// 依附性方块是否排第二轮（默认 true）
	public static boolean deferAttachedToSecondPass()
	{
		ensureLoaded();
		return deferAttached;
	}

	// 流体是否排第二轮（默认 true：与"池壁先围好再落水"的建造设计一致）
	public static boolean deferFluidsToSecondPass()
	{
		ensureLoaded();
		return deferFluids;
	}

	// 该方块是否属于第二轮专属（第一轮跳过、第二轮才收）：依附性方块与流体各看各的开关
	public static boolean deferredToSecondPass(BlockState state)
	{
		if (deferFluidsToSecondPass() && AttachedBlockTable.isFluid(state)) return true;
		return deferAttachedToSecondPass() && MaterialCalculator.isAttachedBlock(state);
	}

	public static boolean isLoaded()
	{
		return loaded;
	}

	// 统一走 ContentReloader：目录变化时全部内容表一起重载
	public static boolean reloadIfChanged()
	{
		return com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
	}

	public static synchronized void reload()
	{
		// 首次运行落盘模板：玩家打开 NeoSim/Json/building/rules.json 即可编辑
		JsonContent.ensureExternal(CATEGORY, FILE_NAME);
		JsonContent.clearErrors();

		JsonObject builtin = JsonContent.readBuiltin(CATEGORY, FILE_NAME);
		JsonObject external = JsonContent.readExternal(CATEGORY, FILE_NAME);

		// 外部文件写了就用外部的，没写就按内置，两者都没有时默认 true（与内容表"外部优先"一致）
		deferAttached = pick(readBool(external, "deferAttachedToSecondPass"),
				readBool(builtin, "deferAttachedToSecondPass"), true);
		deferFluids = pick(readBool(external, "deferFluidsToSecondPass"),
				readBool(builtin, "deferFluidsToSecondPass"), true);
		loaded = true;
		writeState();

		LOGGER.info("NeoSim-ConstructionRules: deferAttachedToSecondPass={}, deferFluidsToSecondPass={}",
				deferAttached, deferFluids);
	}

	// 读取某个开关；文件里没这个键时返回 null（交给调用方决定回落）
	@Nullable
	private static Boolean readBool(@Nullable JsonObject root, String key)
	{
		if (root == null || !root.has(key)) return null;
		try
		{
			return root.get(key).getAsBoolean();
		}
		catch (Throwable t)
		{
			LOGGER.warn("NeoSim-ConstructionRules: bad '{}' value — ignored", key);
			return null;
		}
	}

	private static boolean pick(@Nullable Boolean external, @Nullable Boolean builtin, boolean fallback)
	{
		if (external != null) return external;
		if (builtin != null) return builtin;
		return fallback;
	}

	private static void writeState()
	{
		JsonObject state = new JsonObject();
		state.addProperty("file", "NeoSim/Json/" + CATEGORY + "/" + FILE_NAME);
		state.addProperty("deferAttachedToSecondPass", deferAttached);
		state.addProperty("deferFluidsToSecondPass", deferFluids);
		state.addProperty("order", "layer low->high; near->far toward facing; left->right");
		state.addProperty("loadedAtMillis", System.currentTimeMillis());
		JsonContent.writeState(state, "building-rules");
	}
}
