package com.wenzai.neosim.compat.attached;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.json.BlockMatcher;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

// 一条依附规则：匹配（id 通配 / 命名空间 / 标签 / 正则）+ 依附方式 + 可选覆盖项
public final class AttachedRule
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public enum Source { BUILTIN, EXTERNAL }

	private final Source source;
	private final BlockMatcher matcher;
	private final AttachMode mode;
	private final int phaseOverride;
	private final List<String> facing;
	private final boolean connective;
	private final String note;
	private final boolean enabled;
	private final int index;

	private AttachedRule(Source source, BlockMatcher matcher, AttachMode mode, int phaseOverride,
			List<String> facing, boolean connective, String note, boolean enabled, int index)
	{
		this.source = source;
		this.matcher = matcher;
		this.mode = mode;
		this.phaseOverride = phaseOverride;
		this.facing = facing;
		this.connective = connective;
		this.note = note;
		this.enabled = enabled;
		this.index = index;
	}

	public Source source() { return source; }

	public BlockMatcher matcher() { return matcher; }

	public BlockMatcher.Kind kind() { return matcher.kind(); }

	public String value() { return matcher.value(); }

	public AttachMode mode() { return mode; }

	public List<String> facing() { return facing; }

	public boolean connective() { return connective; }

	public String note() { return note; }

	public boolean enabled() { return enabled; }

	public int index() { return index; }

	// 生效轮次：显式 phase 优先，否则由依附方式推导
	public int phase()
	{
		return phaseOverride > 0 ? phaseOverride : mode.phase();
	}

	// 匹配表达式原文（GUI 展示）
	public String expression()
	{
		return matcher.expression();
	}

	public boolean matches(Block block)
	{
		return enabled && matcher.matches(block);
	}

	// 导出为 JSON（GUI 保存用）
	public JsonObject toJson()
	{
		JsonObject obj = new JsonObject();
		matcher.writeTo(obj);
		obj.addProperty("attach", mode.id());
		if (phaseOverride > 0) obj.addProperty("phase", phaseOverride);
		if (!facing.isEmpty())
		{
			JsonArray arr = new JsonArray();
			for (String f : facing) arr.add(f);
			obj.add("facing", arr);
		}
		if (connective) obj.addProperty("connective", true);
		if (!enabled) obj.addProperty("enabled", false);
		if (!note.isEmpty()) obj.addProperty("note", note);
		return obj;
	}

	public AttachedRule withMode(AttachMode newMode)
	{
		return new AttachedRule(source, matcher, newMode, 0, facing, connective, note, enabled, index);
	}

	public AttachedRule withEnabled(boolean value)
	{
		return new AttachedRule(source, matcher, mode, phaseOverride, facing, connective, note, value, index);
	}

	// 从 JSON 解析一条规则；非法条目返回 null
	@Nullable
	public static AttachedRule fromJson(JsonObject obj, Source source, int index)
	{
		BlockMatcher matcher = BlockMatcher.fromJson(obj);
		if (matcher == null)
		{
			LOGGER.warn("NeoSim-AttachedRule: rule #{} has no match key (id/namespace/tag/regex) — skipped", index);
			return null;
		}

		AttachMode mode = AttachMode.byId(optString(obj, "attach"));
		if (mode == null)
		{
			LOGGER.warn("NeoSim-AttachedRule: rule #{} unknown attach '{}' — defaults to auto",
					index, optString(obj, "attach"));
			mode = AttachMode.AUTO;
		}

		int phase = 0;
		if (obj.has("phase") && obj.get("phase").isJsonPrimitive())
		{
			try
			{
				int p = obj.get("phase").getAsInt();
				if (p == 1 || p == 2) phase = p;
			}
			catch (Exception ignored)
			{
			}
		}

		List<String> facing = new ArrayList<>();
		if (obj.has("facing") && obj.get("facing").isJsonArray())
		{
			JsonArray arr = obj.getAsJsonArray("facing");
			for (int i = 0; i < arr.size(); i++)
			{
				facing.add(arr.get(i).getAsString().toLowerCase(Locale.ROOT));
			}
		}

		boolean connective = obj.has("connective") && obj.get("connective").getAsBoolean();
		boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
		String note = optString(obj, "note");

		return new AttachedRule(source, matcher, mode, phase, List.copyOf(facing), connective,
				note == null ? "" : note, enabled, index);
	}

	private static String optString(JsonObject obj, String key)
	{
		if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) return null;
		String s = obj.get(key).getAsString().trim();
		return s.isEmpty() ? null : s;
	}
}
