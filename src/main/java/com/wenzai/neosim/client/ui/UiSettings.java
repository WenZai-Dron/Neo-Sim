package com.wenzai.neosim.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.json.JsonContent;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

// 界面个人设置：HUD 外观 + 世界投影外观，一人一份文件 NeoSim/Json/ui/<玩家名>.json。
// 纯客户端：联机时服务器那份文件不参与；字段级容错，缺失/类型错的字段一律回落默认值。
@OnlyIn(Dist.CLIENT)
public final class UiSettings
{
	private static final Logger LOGGER = LogUtils.getLogger();

	// 所在分类：NeoSim/Json/ui/
	private static final String CATEGORY = "ui";

	// HUD 字段键：数组顺序即默认显示顺序，也是配置文件里缺字段时的补全顺序
	public static final String[] FIELD_KEYS =
			{ "city", "time", "weekday", "day", "population", "credit", "mode" };

	// 九宫格锚点（顺序即 GUI 循环按钮的顺序）
	public static final String[] ANCHORS =
			{ "top_left", "top_center", "top_right", "middle_left", "middle_center", "middle_right",
					"bottom_left", "bottom_center", "bottom_right" };

	// 分隔符候选（GUI 循环按钮与 JSON 同表）
	public static final String[] SEPARATORS = { " - ", " | ", "  ", " · " };

	// 颜色预设：GUI「预设」按钮循环用（HUD 文字色与投影染色共用同一组）
	public static final int[] COLOR_PRESETS =
			{ 0xFFFFFFFF, 0xFF88CCFF, 0xFFFFD24A, 0xFF55FF55, 0xFFFF66E6, 0xFFFF5555 };

	// 当前设置：GUI 直接改这两个对象；改完必须调 touch()（HUD 行缓存靠 revision 失效）
	private static Hud hud = Hud.defaults();
	private static Preview preview = Preview.defaults();

	// 设置版本号：任何变化都自增，供 HUD 行缓存判定
	private static int revision;

	private UiSettings()
	{
	}

	// ---- 读写 ----

	// 玩家名（登录名 / 离线名），文件内 "player" 字段与注释都用它
	public static String rawPlayerName()
	{
		Minecraft mc = Minecraft.getInstance();
		String name = mc == null || mc.getUser() == null ? "" : mc.getUser().getName();
		return name == null ? "" : name;
	}

	// 文件名：Windows 非法字符与控制字符换 _，去首尾空白与结尾点，限长 32，空则退化为 player
	public static String fileName()
	{
		String raw = rawPlayerName().trim();
		StringBuilder sb = new StringBuilder(raw.length());

		for (int i = 0; i < raw.length(); i++)
		{
			char c = raw.charAt(i);
			sb.append(c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
		}

		String name = sb.toString().trim();
		while (name.endsWith("."))
		{
			name = name.substring(0, name.length() - 1).trim();
		}
		if (name.length() > 32)
		{
			name = name.substring(0, 32);
		}
		return name.isEmpty() ? "player" : name;
	}

	// 本玩家设置文件的完整路径（GUI 状态行显示用）
	public static String fileDisplayPath()
	{
		return "NeoSim/Json/" + CATEGORY + "/" + fileName() + ".json";
	}

	// 两段设置各自的"本地有未保存改动"标记。
	// 保存 / 重载是按段落做的：只覆盖被操作的那一段，另一段留在内存里的未保存改动不会被
	// mtime 轮询的自动重载悄悄冲掉（否则"在 HUD 页保存一下"就能吃掉投影页刚调好的颜色）
	private static boolean hudDirty;
	private static boolean previewDirty;

	// 设置分段：外观页与内容页都属于 HUD，投影页单独一段
	public enum Section
	{
		HUD, PREVIEW
	}

	// 启动时整份读；文件不存在就落一份默认模板
	public static void load()
	{
		String name = fileName() + ".json";
		Path file = JsonContent.externalPath(CATEGORY, name);
		JsonObject root = JsonContent.readExternal(CATEGORY, name);

		if (root == null)
		{
			hud = Hud.defaults();
			preview = Preview.defaults();
			hudDirty = false;
			previewDirty = false;
			touch();

			if (Files.exists(file))
			{
				LOGGER.warn("NeoSim-UiSettings: {} unreadable, falling back to defaults", fileDisplayPath());
				return;
			}

			JsonContent.save(CATEGORY, name, template());
			LOGGER.info("NeoSim-UiSettings: created template {}", fileDisplayPath());
			return;
		}

		hud = Hud.fromJson(root.get("hud"));
		preview = Preview.fromJson(root.get("preview"));
		hudDirty = false;
		previewDirty = false;
		touch();
	}

	// 自动重载（文件被人手改了）：只覆盖"本地没改过"的段，未保存的改动一律保留
	public static void refresh()
	{
		JsonObject root = JsonContent.readExternal(CATEGORY, fileName() + ".json");
		if (root == null) return;

		boolean changed = false;

		if (!hudDirty)
		{
			hud = Hud.fromJson(root.get("hud"));
			changed = true;
		}

		if (!previewDirty)
		{
			preview = Preview.fromJson(root.get("preview"));
			changed = true;
		}

		if (changed) touch();
	}

	// 「重载」按钮：只把这一段从文件读回来（未保存的改动按玩家的意思丢掉），另一段完全不碰
	public static void reloadSection(Section section)
	{
		JsonObject root = JsonContent.readExternal(CATEGORY, fileName() + ".json");
		if (root == null) return;

		if (section == Section.HUD)
		{
			hud = Hud.fromJson(root.get("hud"));
			hudDirty = false;
		}
		else
		{
			preview = Preview.fromJson(root.get("preview"));
			previewDirty = false;
		}

		touch();
	}

	// 「保存」按钮：只把这一段写进文件，另一段取磁盘上的当前内容
	// （这次保存不该顺手把另一段还没保存的改动也写进去）
	public static boolean save(Section section)
	{
		JsonObject root = template();
		JsonObject onDisk = JsonContent.readExternal(CATEGORY, fileName() + ".json");

		if (section == Section.HUD)
		{
			root.add("hud", hud.toJson());
			root.add("preview", onDisk != null && onDisk.has("preview") ? onDisk.get("preview") : preview.toJson());
			hudDirty = false;
		}
		else
		{
			root.add("hud", onDisk != null && onDisk.has("hud") ? onDisk.get("hud") : hud.toJson());
			root.add("preview", preview.toJson());
			previewDirty = false;
		}

		return JsonContent.save(CATEGORY, fileName() + ".json", root);
	}

	// 文件骨架：版本 / 注释 / 玩家名
	private static JsonObject template()
	{
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("_comment", "本文件只属于 " + rawPlayerName()
				+ "：HUD 与建筑投影的外观设置，仅客户端读取。改完存盘后数秒内生效，「界面」页有重置按钮。");
		root.addProperty("player", rawPlayerName());
		return root;
	}

	// 该段是否还有未保存的改动
	public static boolean isDirty(Section section)
	{
		return section == Section.HUD ? hudDirty : previewDirty;
	}

	// 标记该段有未保存的改动
	public static void markDirty(Section section)
	{
		if (section == Section.HUD)
		{
			hudDirty = true;
		}
		else
		{
			previewDirty = true;
		}
	}

	// ---- 当前设置 ----

	public static Hud hud()
	{
		return hud;
	}

	public static Preview preview()
	{
		return preview;
	}

	public static int revision()
	{
		return revision;
	}

	// 设置变化：版本号 +1，HUD 下一帧重建行缓存
	public static void touch()
	{
		revision++;
	}

	// ---- 重置（三级：单项由 GUI 直接写默认值，这里管整段） ----

	public static void resetHud()
	{
		hud = Hud.defaults();
		hudDirty = true;
		touch();
	}

	public static void resetPreview()
	{
		preview = Preview.defaults();
		previewDirty = true;
		touch();
	}

	// ---- 小工具（GUI 与解析共用） ----

	// 颜色 → "#RRGGBB"（不带 alpha，编辑框里就写这个）
	public static String rgbHex(int argb)
	{
		return String.format("#%06X", argb & 0xFFFFFF);
	}

	// 颜色 → "#AARRGGBB"
	public static String argbHex(int argb)
	{
		return String.format("#%08X", argb);
	}

	// "#RRGGBB" / "#AARRGGBB"（# 可省）→ ARGB；格式不对返回 null
	@Nullable
	public static Integer parseColor(String text)
	{
		if (text == null) return null;
		String s = text.trim();

		if (s.startsWith("#")) s = s.substring(1);
		if (s.length() != 6 && s.length() != 8) return null;

		for (int i = 0; i < s.length(); i++)
		{
			if (Character.digit(s.charAt(i), 16) < 0) return null;
		}

		try
		{
			long v = Long.parseLong(s, 16);
			return s.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	// 字段键是否合法
	public static boolean knownField(String key)
	{
		for (String k : FIELD_KEYS)
		{
			if (k.equals(key)) return true;
		}
		return false;
	}

	// 字段名（GUI 与提示共用）
	public static String fieldName(String key)
	{
		return net.minecraft.network.chat.Component.translatable("gui.neosim.ui.field." + key).getString();
	}

	// ---- 解析工具：全部按"读不出来就回落默认"工作 ----

	private static boolean bool(JsonObject o, String key, boolean fallback)
	{
		try
		{
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
		}
		catch (Exception e)
		{
			return fallback;
		}
	}

	private static int intVal(JsonObject o, String key, int fallback)
	{
		try
		{
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
		}
		catch (Exception e)
		{
			return fallback;
		}
	}

	private static double doubleVal(JsonObject o, String key, double fallback)
	{
		try
		{
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
		}
		catch (Exception e)
		{
			return fallback;
		}
	}

	private static String string(JsonObject o, String key, String fallback)
	{
		try
		{
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
		}
		catch (Exception e)
		{
			return fallback;
		}
	}

	private static int clamp(int v, int min, int max)
	{
		return v < min ? min : Math.min(v, max);
	}

	private static double clamp(double v, double min, double max)
	{
		return v < min ? min : Math.min(v, max);
	}

	@Nullable
	private static JsonObject asObject(@Nullable JsonElement e)
	{
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	// 解析结果为空时回落默认色
	private static int color(@Nullable Integer parsed, int fallback)
	{
		return parsed != null ? parsed : fallback;
	}

	// ---- HUD 设置 ----

	public static final class Hud
	{
		public boolean enabled = true;
		public String anchor = "top_left";
		public int offsetX = 10;
		public int offsetY = 10;
		public double scale = 1.0D;
		public int lineSpacing = 2;
		public boolean multiLine;
		public String separator = " - ";
		public int color = 0xFFFFFFFF;
		public boolean shadow = true;
		public boolean background;
		public int backgroundColor = 0xFF000000;
		public int backgroundAlpha = 128;
		public int backgroundPadding = 4;
		public final List<Field> fields = new ArrayList<>();

		// 默认值：一行、左上角、白字、带阴影（不改变任何玩家的观感）
		public static Hud defaults()
		{
			Hud h = new Hud();
			for (String key : FIELD_KEYS)
			{
				h.fields.add(new Field(key, !key.equals("mode")));
			}
			return h;
		}

		public static Hud fromJson(@Nullable JsonElement e)
		{
			Hud d = defaults();
			JsonObject o = asObject(e);
			if (o == null) return d;

			Hud h = new Hud();
			h.enabled = bool(o, "enabled", d.enabled);
			h.anchor = anchorOf(string(o, "anchor", d.anchor), d.anchor);
			h.offsetX = clamp(intVal(o, "offsetX", d.offsetX), -2000, 2000);
			h.offsetY = clamp(intVal(o, "offsetY", d.offsetY), -2000, 2000);
			h.scale = clamp(doubleVal(o, "scale", d.scale), 0.25D, 4.0D);
			h.lineSpacing = clamp(intVal(o, "lineSpacing", d.lineSpacing), 0, 20);
			h.multiLine = bool(o, "multiLine", d.multiLine);
			h.separator = string(o, "separator", d.separator);
			h.color = color(parseColor(string(o, "color", rgbHex(d.color))), d.color);
			h.shadow = bool(o, "shadow", d.shadow);
			h.background = bool(o, "background", d.background);
			h.backgroundColor = color(parseColor(string(o, "backgroundColor", rgbHex(d.backgroundColor))),
					d.backgroundColor);
			h.backgroundAlpha = clamp(intVal(o, "backgroundAlpha", d.backgroundAlpha), 0, 255);
			h.backgroundPadding = clamp(intVal(o, "backgroundPadding", d.backgroundPadding), 0, 16);

			// 字段：以文件为准保留顺序与启用状态；缺的按默认顺序补在末尾，未知键与重复键忽略
			List<String> seen = new ArrayList<>();
			JsonArray arr = o.has("fields") && o.get("fields").isJsonArray() ? o.getAsJsonArray("fields") : null;

			if (arr != null)
			{
				for (JsonElement element : arr)
				{
					JsonObject fo = asObject(element);
					if (fo == null) continue;

					String key = string(fo, "key", "");
					if (!knownField(key) || seen.contains(key)) continue;

					seen.add(key);
					h.fields.add(new Field(key, bool(fo, "enabled", true)));
				}
			}

			for (String key : FIELD_KEYS)
			{
				if (!seen.contains(key))
				{
					h.fields.add(new Field(key, !key.equals("mode")));
				}
			}

			return h;
		}

		public JsonObject toJson()
		{
			JsonObject o = new JsonObject();
			o.addProperty("enabled", enabled);
			o.addProperty("anchor", anchor);
			o.addProperty("offsetX", offsetX);
			o.addProperty("offsetY", offsetY);
			o.addProperty("scale", scale);
			o.addProperty("lineSpacing", lineSpacing);
			o.addProperty("multiLine", multiLine);
			o.addProperty("separator", separator);
			// 带透明度的文字颜色写成 #AARRGGBB：否则每次保存都会把玩家手写的 alpha 悄悄抹掉
			o.addProperty("color", (color >>> 24) == 0xFF ? rgbHex(color) : argbHex(color));
			o.addProperty("shadow", shadow);
			o.addProperty("background", background);
			o.addProperty("backgroundColor", rgbHex(backgroundColor));
			o.addProperty("backgroundAlpha", backgroundAlpha);
			o.addProperty("backgroundPadding", backgroundPadding);

			JsonArray arr = new JsonArray();
			for (Field f : fields)
			{
				JsonObject fo = new JsonObject();
				fo.addProperty("key", f.key);
				fo.addProperty("enabled", f.enabled);
				arr.add(fo);
			}
			o.add("fields", arr);
			return o;
		}

		// 锚点循环（GUI 按钮）
		public void cycleAnchor(int step)
		{
			int index = 0;
			for (int i = 0; i < ANCHORS.length; i++)
			{
				if (ANCHORS[i].equals(anchor))
				{
					index = i;
					break;
				}
			}
			anchor = ANCHORS[Math.floorMod(index + step, ANCHORS.length)];
		}

		// 分隔符循环（GUI 按钮）
		public void cycleSeparator()
		{
			int index = 0;
			for (int i = 0; i < SEPARATORS.length; i++)
			{
				if (SEPARATORS[i].equals(separator))
				{
					index = i;
					break;
				}
			}
			separator = SEPARATORS[(index + 1) % SEPARATORS.length];
		}

		// 字段上移 / 下移（数组顺序即显示顺序）
		public void moveField(int index, int step)
		{
			int target = index + step;
			if (index < 0 || index >= fields.size() || target < 0 || target >= fields.size()) return;
			Field f = fields.remove(index);
			fields.add(target, f);
		}
	}

	// HUD 的一个信息字段：key 见 FIELD_KEYS
	public static final class Field
	{
		public final String key;
		public boolean enabled;

		public Field(String key, boolean enabled)
		{
			this.key = key;
			this.enabled = enabled;
		}
	}

	// ---- 投影预览设置 ----

	public static final class Preview
	{
		public int tint = 0xFFFBFDFF;
		public int alpha = 128;
		public double textureMix = 1.0D;

		// 默认值：染色 #FBFDFF、透明度 128、全纹理（不改变任何玩家的观感）
		public static Preview defaults()
		{
			return new Preview();
		}

		public static Preview fromJson(@Nullable JsonElement e)
		{
			Preview d = defaults();
			JsonObject o = asObject(e);
			if (o == null) return d;

			Preview p = new Preview();
			p.tint = color(parseColor(string(o, "tint", rgbHex(d.tint))), d.tint);
			p.alpha = clamp(intVal(o, "alpha", d.alpha), 0, 255);
			p.textureMix = clamp(doubleVal(o, "textureMix", d.textureMix), 0.0D, 1.0D);
			return p;
		}

		public JsonObject toJson()
		{
			JsonObject o = new JsonObject();
			o.addProperty("tint", rgbHex(tint));
			o.addProperty("alpha", alpha);
			o.addProperty("textureMix", textureMix);
			return o;
		}
	}

	// 锚点合法性：不认识的锚点按默认处理
	private static String anchorOf(String anchor, String fallback)
	{
		for (String a : ANCHORS)
		{
			if (a.equalsIgnoreCase(anchor)) return a;
		}
		return fallback;
	}

	// 分隔符的显示名（GUI 循环按钮）
	public static String separatorName(String separator)
	{
		for (int i = 0; i < SEPARATORS.length; i++)
		{
			if (SEPARATORS[i].equals(separator)) return "gui.neosim.ui.separator." + i;
		}
		return "gui.neosim.ui.separator.0";
	}

	// 锚点显示名
	public static String anchorKey(String anchor)
	{
		return "gui.neosim.ui.anchor." + anchorOf(anchor, ANCHORS[0]);
	}

	// 配色预设循环：返回下一组预设里的颜色（当前色不在预设表里就从第一个开始）
	public static int nextPreset(int current)
	{
		for (int i = 0; i < COLOR_PRESETS.length; i++)
		{
			if (COLOR_PRESETS[i] == (current | 0xFF000000) || COLOR_PRESETS[i] == current)
			{
				return COLOR_PRESETS[(i + 1) % COLOR_PRESETS.length];
			}
		}
		return COLOR_PRESETS[0];
	}
}
