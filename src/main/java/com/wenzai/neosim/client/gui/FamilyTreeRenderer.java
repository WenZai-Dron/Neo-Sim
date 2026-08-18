package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.life.Genealogy.FamilyNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// 族谱自绘渲染器：纵向分代 + 拖动平移 + 滚轮缩放 + 点击节点重新居中
public class FamilyTreeRenderer
{
	// 节点世界坐标矩形
	public record Rect(float x, float y, float w, float h) {}

	// 连线
	private record Edge(String a, String b, boolean spouse) {}

	private static final float NODE_H = 28.0F;
	private static final float NODE_GAP_X = 14.0F;
	private static final float LAYER_GAP_Y = 40.0F;
	private static final float MIN_ZOOM = 0.5F;
	private static final float MAX_ZOOM = 2.0F;

	// 翻译键前缀
	private static final String P = "gui.neosim.npc.family.";

	private final Consumer<String> onNodeClicked;

	private String centerName = "";
	private final Map<String, FamilyNode> nodes = new HashMap<>();
	private final Map<String, Rect> rects = new HashMap<>();
	private final List<Edge> edges = new ArrayList<>();
	// M13：称谓预计算缓存（layout 时一次算全，渲染每帧不再对每个节点重算）
	private final Map<String, String> titles = new HashMap<>();

	private float panX, panY;
	private float zoom = 1.0F;
	private final Font font;

	public FamilyTreeRenderer(Consumer<String> onNodeClicked)
	{
		this.onNodeClicked = onNodeClicked;
		this.font = Minecraft.getInstance().font;
	}

	// 数据入口：设置中心节点与节点集合，重算布局并复位视图
	public void setData(String centerName, List<FamilyNode> nodes)
	{
		this.centerName = centerName != null ? centerName : "";
		this.nodes.clear();
		if (nodes != null)
		{
			for (FamilyNode n : nodes) this.nodes.put(n.name(), n);
		}
		layout();
		resetView();
	}

	// 当前中心节点名
	public String getCenterName()
	{
		return centerName;
	}

	// 世界坐标 <-> 屏幕坐标
	private float toWorldX(double sx) { return (float) ((sx - panX) / zoom); }
	private float toWorldY(double sy) { return (float) ((sy - panY) / zoom); }
	private int toScreenX(float wx) { return Math.round(wx * zoom + panX); }
	private int toScreenY(float wy) { return Math.round(wy * zoom + panY); }

	// 计算某节点相对中心节点的代差：祖辈-2 / 父母、姻亲-1 / 本人、配偶、兄弟姐妹0 / 子女+1
	private int generation(String name)
	{
		if (name.equals(centerName)) return 0;
		FamilyNode c = nodes.get(centerName);
		if (c == null) return 0;
		if (c.parents().contains(name)) return -1;
		if (c.children().contains(name)) return 1;
		if (c.partner().equals(name)) return 0;

		// 姻亲：配偶的父母
		if (!c.partner().isEmpty())
		{
			FamilyNode sp = nodes.get(c.partner());
			if (sp != null && sp.parents().contains(name)) return -1;
		}
		// 祖辈：父母的父母
		for (String p : c.parents())
		{
			FamilyNode pn = nodes.get(p);
			if (pn != null && pn.parents().contains(name)) return -2;
		}
		// 兄弟姐妹：共享任一父母
		for (String p : c.parents())
		{
			FamilyNode pn = nodes.get(p);
			if (pn != null && pn.children().contains(name)) return 0;
		}
		return 0;
	}

	// 重算布局：按代分 4 层，每层水平排列（中心节点所在层居中）
	private void layout()
	{
		rects.clear();
		edges.clear();
		titles.clear();
		if (centerName.isEmpty() || !nodes.containsKey(centerName)) return;

		// 分代
		Map<Integer, List<String>> layers = new HashMap<>();
		for (String name : nodes.keySet())
		{
			layers.computeIfAbsent(generation(name), k -> new ArrayList<>()).add(name);
		}

		// 代差 -2 → +1 逐层往下
		float y = 0.0F;
		for (int g = -2; g <= 1; g++)
		{
			List<String> layer = layers.get(g);
			if (layer == null || layer.isEmpty()) { y += NODE_H + LAYER_GAP_Y; continue; }

			// 本人所在层：本人放中间
			if (g == 0)
			{
				layer.remove(centerName);
				layer.sort(null);
				layer.add(0, centerName);
			}

			// 计算每节点宽度
			float totalW = 0;
			List<Float> widths = new ArrayList<>();
			for (String name : layer)
			{
				float w = font.width(name) + 16.0F;
				widths.add(w);
				totalW += w;
			}
			totalW += NODE_GAP_X * (layer.size() - 1);

			float x = -totalW / 2.0F;
			for (int i = 0; i < layer.size(); i++)
			{
				String name = layer.get(i);
				float w = widths.get(i);
				rects.put(name, new Rect(x, y, w, NODE_H));
				x += w + NODE_GAP_X;
			}
			y += NODE_H + LAYER_GAP_Y;
		}

		// 连线：父子（竖线）+ 配偶（横线）
		Map<String, String> dedup = new HashMap<>();
		for (String name : nodes.keySet())
		{
			FamilyNode n = nodes.get(name);
			for (String p : n.parents())
			{
				if (rects.containsKey(p) && !dedup.containsKey(p + "->" + name))
				{
					edges.add(new Edge(p, name, false));
					dedup.put(p + "->" + name, "");
				}
			}
			if (!n.partner().isEmpty() && rects.containsKey(n.partner()))
			{
				String key = name.compareTo(n.partner()) < 0
						? name + "~" + n.partner() : n.partner() + "~" + name;
				if (!dedup.containsKey(key))
				{
					edges.add(new Edge(name, n.partner(), true));
					dedup.put(key, "");
				}
			}
		}

		// M13：称谓预计算（一次全量，渲染零开销）
		for (String name : nodes.keySet())
		{
			String title = computeRelationTitle(name);
			if (!title.isEmpty()) titles.put(name, title);
		}
	}

	// 复位视图：整棵树适配屏幕并居中（保证全部节点可见）
	public void resetView()
	{
		if (rects.isEmpty())
		{
			panX = 0; panY = 0;
			return;
		}
		int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();

		// 整棵树包围盒
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (Rect r : rects.values())
		{
			minX = Math.min(minX, r.x());
			minY = Math.min(minY, r.y());
			maxX = Math.max(maxX, r.x() + r.w());
			maxY = Math.max(maxY, r.y() + r.h());
		}
		float treeW = Math.max(1.0F, maxX - minX);
		float treeH = Math.max(1.0F, maxY - minY);

		// 适配缩放：四周留 40px 边距；树小不放大（上限 1.0），树大完全缩小到能放下
		float margin = 40.0F;
		float fit = Math.min((w - margin * 2) / treeW, (h - margin * 2) / treeH);
		zoom = Math.min(1.0F, fit);

		// 整棵树居中
		panX = w / 2.0F - (minX + treeW / 2.0F) * zoom;
		panY = h / 2.0F - (minY + treeH / 2.0F) * zoom;
	}

	// 计算某节点的关系称谓（基于与中心节点的关系；M13：layout 时预计算一次进 titles 缓存）
	private String computeRelationTitle(String name)
	{
		if (name.equals(centerName)) return "";
		FamilyNode c = nodes.get(centerName);
		FamilyNode n = nodes.get(name);
		if (c == null || n == null) return "";

		boolean male = "male".equals(n.sex());
		if (c.parents().contains(name))
			return male ? t(P + "father") : t(P + "mother");
		if (c.children().contains(name))
			return male ? t(P + "son") : t(P + "daughter");
		if (c.partner().equals(name)) return t(P + "partner");
		for (String p : c.parents())
		{
			FamilyNode pn = nodes.get(p);
			if (pn != null && pn.parents().contains(name))
				return male ? t(P + "grandfather") : t(P + "grandmother");
		}
		if (!c.partner().isEmpty())
		{
			FamilyNode sp = nodes.get(c.partner());
			if (sp != null && sp.parents().contains(name))
				return male ? t(P + "fatherInLaw") : t(P + "motherInLaw");
		}
		for (String p : c.parents())
		{
			FamilyNode pn = nodes.get(p);
			if (pn != null && pn.children().contains(name))
				return male ? t(P + "brother") : t(P + "sister");
		}
		return "";
	}

	// 主渲染入口
	public void render(GuiGraphics gfx)
	{
		if (centerName.isEmpty() || !nodes.containsKey(centerName)) return;

		// 连线
		for (Edge e : edges)
		{
			Rect ra = rects.get(e.a());
			Rect rb = rects.get(e.b());
			if (ra == null || rb == null) continue;
			if (e.spouse())
			{
				// 配偶：水平线连接两节点中心
				int ya = toScreenY(ra.y() + ra.h() / 2.0F);
				int yb = toScreenY(rb.y() + rb.h() / 2.0F);
				int ym = (ya + yb) / 2;
				int xa = toScreenX(ra.x() + ra.w());
				int xb = toScreenX(rb.x());
				gfx.hLine(Math.min(xa, xb), Math.max(xa, xb), ym, 0xFF888888);
			}
			else
			{
				// 父子：父底部中心 → 子顶部中心（竖线折线）
				int xa = toScreenX(ra.x() + ra.w() / 2.0F);
				int ya = toScreenY(ra.y() + ra.h());
				int xb = toScreenX(rb.x() + rb.w() / 2.0F);
				int yb = toScreenY(rb.y());
				int ym = (ya + yb) / 2;
				gfx.vLine(xa, ya, ym, 0xFF888888);
				gfx.hLine(Math.min(xa, xb), Math.max(xa, xb), ym, 0xFF888888);
				gfx.vLine(xb, ym, yb, 0xFF888888);
			}
		}

		// 节点
		for (Map.Entry<String, Rect> e : rects.entrySet())
		{
			drawNode(gfx, e.getKey(), e.getValue());
		}
	}

	// 绘制单个节点：男直角/女圆角，名字+称谓整体垂直居中、随 zoom 缩放
	private void drawNode(GuiGraphics gfx, String name, Rect r)
	{
		int sx = toScreenX(r.x());
		int sy = toScreenY(r.y());
		int sw = Math.max(1, Math.round(r.w() * zoom));
		int sh = Math.max(1, Math.round(r.h() * zoom));

		FamilyNode n = nodes.get(name);
		boolean male = !"female".equals(n == null ? "" : n.sex());
		boolean isCenter = name.equals(centerName);

		int bg = isCenter ? 0xFF3A2E10 : 0xFF202020;
		int border = isCenter ? 0xFFD7A83E : 0xFF888888;

		// 框：世界坐标 → 屏幕坐标（与连线同一变换；renderOutline 第3、4参数为宽高）
		if (male)
		{
			gfx.fill(sx, sy, sx + sw, sy + sh, bg);
			gfx.renderOutline(sx, sy, sw, sh, border);
		}
		else
		{
			fillRoundRect(gfx, sx, sy, sx + sw, sy + sh, 6.0F * zoom, bg);
			fillRoundRect(gfx, sx + 1, sy + 1, sx + sw - 1, sy + sh - 1, 5.0F * zoom, border);
		}

		// M13：称谓直接查 layout 预计算缓存（不再每帧重算）
		String title = titles.getOrDefault(name, "");

		// 文字：同样从世界坐标 → 屏幕坐标（与框完全同源）
		// 名字（正常字号，水平居中）
		int nameW = font.width(name);
		float nameX = sx + (sw - nameW * zoom) / 2.0F;
		// 内容总高 = 名字行高(9) + 称谓行高(0.7*9≈6.3)；垂直居中
		float contentH = 9.0F + (title.isEmpty() ? 0.0F : 6.3F);
		float textTop = sy + Math.max(1.0F, (sh - contentH * zoom) / 2.0F);
		gfx.drawString(font, name, Math.round(nameX), Math.round(textTop), 0xFFFFFF);

		// 称谓（0.7 倍小字号，水平居中）
		if (!title.isEmpty())
		{
			float titleW = font.width(title) * 0.7F * zoom;
			float tx = sx + (sw - titleW) / 2.0F;
			gfx.pose().pushPose();
			gfx.pose().translate(tx, textTop + 9.0F * zoom, 0.0F);
			gfx.pose().scale(0.7F * zoom, 0.7F * zoom, 1.0F);
			gfx.drawString(font, title, 0, 0, 0xAAAAAA);
			gfx.pose().popPose();
		}
	}

	// 左键点击：命中节点 → 回调重居中；命中空白 → 复位视图
	public boolean handleClick(double mx, double my)
	{
		float wx = toWorldX(mx);
		float wy = toWorldY(my);
		for (Map.Entry<String, Rect> e : rects.entrySet())
		{
			Rect r = e.getValue();
			if (wx >= r.x() && wx <= r.x() + r.w() && wy >= r.y() && wy <= r.y() + r.h())
			{
				if (!e.getKey().equals(centerName))
				{
					onNodeClicked.accept(e.getKey());
				}
				return true;
			}
		}
		resetView();
		return true;
	}

	// 左键拖动：平移画布
	public void panBy(double dx, double dy)
	{
		panX += (float) dx;
		panY += (float) dy;
	}

	// 滚轮：以鼠标位置为锚点缩放（0.5~2.0）
	public void zoomAt(double mx, double my, double scrollAmount)
	{
		float factor = scrollAmount > 0 ? 1.1F : 0.9F;
		float newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
		if (newZoom == zoom) return;
		float k = newZoom / zoom;
		panX = (float) (mx - (mx - panX) * k);
		panY = (float) (my - (my - panY) * k);
		zoom = newZoom;
	}

	// 画圆角矩形：用 gfx.fill 分块（中心 + 上下左右条 + 四角阶梯圆角），绝不产生畸形形状
	private static void fillRoundRect(GuiGraphics gfx, float x1, float y1, float x2, float y2,
									  float radius, int color)
	{
		float r = Math.min(radius, Math.min((x2 - x1) / 2.0F, (y2 - y1) / 2.0F));
		int ix1 = Math.round(x1), iy1 = Math.round(y1);
		int ix2 = Math.round(x2), iy2 = Math.round(y2);
		int ir = Math.max(1, Math.round(r));
		if (ix2 - ix1 < ir * 2 || iy2 - iy1 < ir * 2)
		{
			gfx.fill(ix1, iy1, ix2, iy2, color);
			return;
		}

		// 中心矩形
		gfx.fill(ix1 + ir, iy1, ix2 - ir, iy2, color);
		// 左条 / 右条
		gfx.fill(ix1, iy1 + ir, ix1 + ir, iy2 - ir, color);
		gfx.fill(ix2 - ir, iy1 + ir, ix2, iy2 - ir, color);
		// 上条 / 下条
		gfx.fill(ix1 + ir, iy1, ix2 - ir, iy1 + ir, color);
		gfx.fill(ix1 + ir, iy2 - ir, ix2 - ir, iy2, color);
		// 四角：两级阶梯近似 1/4 圆弧
		int s2 = (ir * 2) / 3, s1 = ir / 3;
		// 左上
		gfx.fill(ix1, iy1 + s2, ix1 + s1, iy1 + ir, color);
		gfx.fill(ix1, iy1 + s1, ix1 + s2, iy1 + s2, color);
		gfx.fill(ix1, iy1, ix1 + ir, iy1 + s1, color);
		// 右上
		gfx.fill(ix2 - s1, iy1 + s2, ix2, iy1 + ir, color);
		gfx.fill(ix2 - s2, iy1 + s1, ix2 - s1, iy1 + s2, color);
		gfx.fill(ix2 - ir, iy1, ix2, iy1 + s1, color);
		// 左下
		gfx.fill(ix1, iy2 - ir, ix1 + s1, iy2 - s2, color);
		gfx.fill(ix1, iy2 - s2, ix1 + s2, iy2 - s1, color);
		gfx.fill(ix1, iy2 - s1, ix1 + ir, iy2, color);
		// 右下
		gfx.fill(ix2 - s1, iy2 - ir, ix2, iy2 - s2, color);
		gfx.fill(ix2 - s2, iy2 - s2, ix2 - s1, iy2 - s1, color);
		gfx.fill(ix2 - ir, iy2 - s1, ix2, iy2, color);
	}

	// 翻译取词
	private static String t(String key)
	{
		return Component.translatable(key).getString();
	}
}
