package com.wenzai.neosim.compat.crops;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.json.BlockMatcher;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import javax.annotation.Nullable;

// 一条模组作物规则：匹配作物方块 + 可选覆盖（成熟目标 / 需水 / 排除 / 停用）
// 未在 JSON 中出现的字段保持"未声明"，叠加时不会覆盖上一条规则已设置的值。
public final class CropRule
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public enum Source { BUILTIN, EXTERNAL }

	private final Source source;
	private final BlockMatcher matcher;
	private final String matureId;
	private final Boolean needsWater;
	private final Boolean excluded;
	private final Boolean enabled;
	private final String note;
	private final int index;

	private CropRule(Source source, BlockMatcher matcher, String matureId, Boolean needsWater,
			Boolean excluded, Boolean enabled, String note, int index)
	{
		this.source = source;
		this.matcher = matcher;
		this.matureId = matureId;
		this.needsWater = needsWater;
		this.excluded = excluded;
		this.enabled = enabled;
		this.note = note;
		this.index = index;
	}

	public Source source() { return source; }

	public BlockMatcher matcher() { return matcher; }

	public BlockMatcher.Kind kind() { return matcher.kind(); }

	public String value() { return matcher.value(); }

	public String matureId() { return matureId; }

	public Boolean needsWater() { return needsWater; }

	public Boolean excluded() { return excluded; }

	public Boolean enabled() { return enabled; }

	public String note() { return note; }

	public int index() { return index; }

	public String expression() { return matcher.expression(); }

	public boolean matches(Block block)
	{
		return (enabled == null || enabled) && matcher.matches(block);
	}

	public JsonObject toJson()
	{
		JsonObject obj = new JsonObject();
		matcher.writeTo(obj);
		if (matureId != null) obj.addProperty("mature", matureId);
		if (needsWater != null) obj.addProperty("needsWater", needsWater);
		if (excluded != null) obj.addProperty("excluded", excluded);
		if (enabled != null) obj.addProperty("enabled", enabled);
		if (!note.isEmpty()) obj.addProperty("note", note);
		return obj;
	}

	public CropRule withEnabled(boolean value)
	{
		return new CropRule(source, matcher, matureId, needsWater, excluded, value, note, index);
	}

	public CropRule withNeedsWater(boolean value)
	{
		return new CropRule(source, matcher, matureId, value, excluded, enabled, note, index);
	}

	public CropRule withExcluded(boolean value)
	{
		return new CropRule(source, matcher, matureId, needsWater, value, enabled, note, index);
	}

	@Nullable
	public static CropRule fromJson(JsonObject obj, Source source, int index)
	{
		BlockMatcher matcher = BlockMatcher.fromJson(obj);
		if (matcher == null)
		{
			LOGGER.warn("NeoSim-CropRule: rule #{} has no match key (id/namespace/tag/regex) — skipped", index);
			return null;
		}

		String mature = optString(obj, "mature");
		Boolean needsWater = optBool(obj, "needsWater");
		Boolean excluded = optBool(obj, "excluded");
		Boolean enabled = optBool(obj, "enabled");
		String note = optString(obj, "note");

		return new CropRule(source, matcher, mature, needsWater, excluded, enabled,
				note == null ? "" : note, index);
	}

	@Nullable
	private static String optString(JsonObject obj, String key)
	{
		if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) return null;
		String s = obj.get(key).getAsString().trim();
		return s.isEmpty() ? null : s;
	}

	@Nullable
	private static Boolean optBool(JsonObject obj, String key)
	{
		if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) return null;
		try
		{
			return obj.get(key).getAsBoolean();
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
