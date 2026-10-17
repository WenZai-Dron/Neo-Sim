package com.wenzai.neosim.building;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.json.JsonContent;
import org.slf4j.Logger;

import javax.annotation.Nullable;

// 建造顺序表（NeoSim/Json/building/rules.json）：
// 两轮建造里，"哪些方块算第二轮专属"。每一轮内部的主序列都固定是 Sim-U-Kraft 的顺序 ——
// 层(低→高) → 深度 → 宽度，逐格推进（容器索引 y*(sizeX*sizeZ) + z*sizeX + x 就是这个次序）。
//
// deferAttachedToSecondPass = true（默认）：依附性方块（门 / 火把 / 梯子 / 灯笼 / 藤蔓…）排第二轮。
// deferFluidsToSecondPass  = true（默认）：流体（水 / 岩浆 / 含水方块的水面）排第二轮——
//   第一轮把池底 / 池壁围好，水最后落进容器里，不在建造途中先漫开把后面要放的方块冲掉。
//
// 两个都设 false 时第一轮会按主序列把所有方块一路放下去（顺序与 Sim-U-Kraft 完全一致，
// 代价是依附方块少了"支撑先就位"的保障、水会先漫开）。
//
// 无论怎么配，第一轮放不下去的方块（没支撑 / canSurvive 不过 / 材料不够）都会跳过，
// 由第二轮重试 —— 第二轮逻辑不随本表改变。
public final class ConstructionRules
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final String CATEGORY = "building";
	public static final String FILE_NAME = "rules.json";

	private static volatile boolean deferAttached = true;
	private static volatile boolean deferFluids = true;
	private static volatile boolean loaded;

	private ConstructionRules()
	{
	}

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
	public static boolean deferredToSecondPass(net.minecraft.world.level.block.state.BlockState state)
	{
		if (deferFluidsToSecondPass()
				&& com.wenzai.neosim.compat.attached.AttachedBlockTable.isFluid(state)) return true;
		return deferAttachedToSecondPass()
				&& com.wenzai.neosim.schematic.MaterialCalculator.isAttachedBlock(state);
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
		state.addProperty("order", "layer(low->high) -> depth -> width");
		state.addProperty("loadedAtMillis", System.currentTimeMillis());
		JsonContent.writeState(state, "building-rules");
	}
}
