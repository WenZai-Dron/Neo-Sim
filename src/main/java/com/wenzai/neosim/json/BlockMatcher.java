package com.wenzai.neosim.json;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

// 方块匹配器：id（支持 * 与 ? 通配）/ namespace / tag / regex —— 依附方块表与模组作物表共用
public final class BlockMatcher
{
	private static final Logger LOGGER = LogUtils.getLogger();

	public enum Kind { ID, NAMESPACE, TAG, REGEX }

	private final Kind kind;
	private final String value;
	private final Pattern pattern;
	private final TagKey<Block> tag;

	private BlockMatcher(Kind kind, String value, Pattern pattern, TagKey<Block> tag)
	{
		this.kind = kind;
		this.value = value;
		this.pattern = pattern;
		this.tag = tag;
	}

	public Kind kind()
	{
		return kind;
	}

	public String value()
	{
		return value;
	}

	// 匹配表达式原文（GUI 展示）
	public String expression()
	{
		return switch (kind)
		{
			case TAG -> "#" + value;
			case NAMESPACE -> value + ":*";
			default -> value;
		};
	}

	public boolean matches(@Nullable Block block)
	{
		if (block == null) return false;
		ResourceLocation id = block.builtInRegistryHolder().key().location();
		return switch (kind)
		{
			case ID, REGEX -> pattern != null && pattern.matcher(id.toString()).matches();
			case NAMESPACE -> value.equals(id.getNamespace());
			case TAG -> tag != null && block.defaultBlockState().is(tag);
		};
	}

	// 把匹配键写回 JSON 对象
	public void writeTo(JsonObject obj)
	{
		switch (kind)
		{
			case ID -> obj.addProperty("id", value);
			case NAMESPACE -> obj.addProperty("namespace", value);
			case TAG -> obj.addProperty("tag", "#" + value);
			case REGEX -> obj.addProperty("regex", value);
		}
	}

	// 精确 id 匹配器（GUI 用手持方块加规则）
	public static BlockMatcher ofId(String id)
	{
		return new BlockMatcher(Kind.ID, id, glob(id), null);
	}

	// 从 JSON 读匹配键（id / namespace / tag / regex），都没有则返回 null
	@Nullable
	public static BlockMatcher fromJson(JsonObject obj)
	{
		String id = optString(obj, "id");
		String namespace = optString(obj, "namespace");
		String tagRaw = optString(obj, "tag");
		String regex = optString(obj, "regex");

		Kind kind;
		String value;
		if (id != null)
		{
			kind = Kind.ID;
			value = id;
		}
		else if (namespace != null)
		{
			kind = Kind.NAMESPACE;
			value = namespace;
		}
		else if (tagRaw != null)
		{
			kind = Kind.TAG;
			value = tagRaw.startsWith("#") ? tagRaw.substring(1) : tagRaw;
		}
		else if (regex != null)
		{
			kind = Kind.REGEX;
			value = regex;
		}
		else
		{
			return null;
		}

		Pattern pattern;
		try
		{
			pattern = kind == Kind.REGEX ? Pattern.compile(value) : glob(value);
		}
		catch (Exception e)
		{
			LOGGER.warn("NeoSim-BlockMatcher: bad pattern '{}' — {}", value, e.getMessage());
			return null;
		}

		TagKey<Block> tag = null;
		if (kind == Kind.TAG)
		{
			ResourceLocation rl = ResourceLocation.tryParse(value);
			if (rl == null)
			{
				LOGGER.warn("NeoSim-BlockMatcher: bad tag '{}' — skipped", value);
				return null;
			}
			tag = TagKey.create(Registries.BLOCK, rl);
		}

		return new BlockMatcher(kind, value, pattern, tag);
	}

	private static String optString(JsonObject obj, String key)
	{
		if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) return null;
		String s = obj.get(key).getAsString().trim();
		return s.isEmpty() ? null : s;
	}

	// * 与 ? 通配 → 正则（其余字符按字面量处理）
	public static Pattern glob(String glob)
	{
		StringBuilder sb = new StringBuilder();
		for (char c : glob.toCharArray())
		{
			switch (c)
			{
				case '*' -> sb.append(".*");
				case '?' -> sb.append('.');
				default -> sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(sb.toString());
	}
}
