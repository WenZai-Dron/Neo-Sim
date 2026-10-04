package com.wenzai.neosim.client.gui.config;

import com.wenzai.neosim.client.ui.UiSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// 「界面」页（城市信息 GUI → 配置 → 界面）：HUD 与世界投影的个人外观设置
// 一人一份文件 NeoSim/Json/ui/<玩家名>.json；控件直接改内存里的 UiSettings，按「保存」才落盘
// 布局：子标签（HUD 外观 / HUD 内容 / 投影预览）→ 设置行（名称 + 控件 + 值）→ 底部保存 / 重载 / 重置
public class UiPanel
{
	private static final String P = "gui.neosim.ui.";

	// 与配置页同一套紧凑间距：行高 16、行间不留白，窗口矮时整体收紧
	private static final int EDGE = 24;
	private static final int TAB_Y = 86;
	private static final int TAB_H = 16;
	private static final int CTRL_X = EDGE + 92;
	private static final int ACTION_Y_OFFSET = 46;

	// 三个子标签：HUD 外观 / HUD 内容 / 投影预览
	private static final int PAGE_APPEARANCE = 0;
	private static final int PAGE_CONTENT = 1;
	private static final int PAGE_PREVIEW = 2;

	// 每页行数上限：行高按窗口高度自适应（见 computeLayout），保证再多一行也不会压到底部按钮
	private static final int APPEARANCE_ROWS = 7;
	private static final int CONTENT_ROWS = 5;
	private static final int PREVIEW_ROWS = 3;

	// 开关按钮宽度（HUD显示 / 阴影显示 两行）
	private static final int TOGGLE_W = 70;

	// 行高上下限：太矮看不清，太高又会把页面撑出可用高度
	private static final int ROW_H_MAX = 16;
	private static final int ROW_H_MIN = 10;

	// 重置的二次确认时长（毫秒）：第一次点只换文案，超时自动还原
	private static final long CONFIRM_MS = 3000L;

	// 投影示意用的方块
	private static final ItemStack[] SAMPLE_BLOCKS = {
			new ItemStack(Items.STONE), new ItemStack(Items.OAK_PLANKS),
			new ItemStack(Items.GLASS), new ItemStack(Items.STONE_BRICKS) };

	// 一行设置：名称（画在左列）+ 控件（build 时摆好）+ 值文本（画在控件右边）
	// labelColor 非空时行名用这个颜色画（颜色行：行名本身就是颜色预览）
	private record Row(String labelKey, List<? extends AbstractWidget> controls, Supplier<String> value, int posY,
					   IntSupplier labelColor)
	{
	}

	private final List<Row> rows = new ArrayList<>();

	private ConfigPanel.Host host;
	private int page = PAGE_APPEARANCE;
	private String message = "";

	// build() 期间置位：控件回调不能在重建过程中再触发重建（会无限递归）
	private boolean building;

	// 本页排版：build() 开头按窗口高度算一次，render() 与 drag 都读它（两边永远一致）
	private int rowTop = 110;
	private int rowSizeH = ROW_H_MAX;
	private int rowLimit = 300;

	// 窗口太矮导致有设置行没排下：状态行改成提示"调高窗口/降低 GUI 缩放"
	private boolean crowded;

	// 重置确认：待确认的动作与过期时间
	private String confirmAction = "";
	private long confirmUntil;

	// 保存按钮：滑块 / 输入框这类改完不重建控件的操作，必须直接把它点亮（见 markDirty）
	private Button saveButton;

	// 颜色输入框的草稿（重建时按草稿恢复，避免输入一半被覆盖）
	private String hudColorDraft = "";
	private String tintDraft = "";

	// ---- 对外接口 ----

	public void build(ConfigPanel.Host host)
	{
		this.host = host;
		building = true;
		rows.clear();
		crowded = false;
		saveButton = null;
		computeLayout();

		// 每次重建都从当前设置刷新草稿，保证输入框显示的是最新颜色
		hudColorDraft = UiSettings.rgbHex(UiSettings.hud().color);
		tintDraft = UiSettings.rgbHex(UiSettings.preview().tint);

		buildTabRow(host);

		if (page == PAGE_APPEARANCE)
		{
			buildAppearancePage(host);
		}
		else if (page == PAGE_CONTENT)
		{
			buildContentPage(host);
		}
		else
		{
			buildPreviewPage(host);
		}

		buildActionRow(host);
		building = false;
	}

	public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick)
	{
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

		// 重置确认过期：把按钮文案还原（每帧一次纯数值比较）
		if (!confirmAction.isEmpty() && System.currentTimeMillis() > confirmUntil)
		{
			confirmAction = "";
			rebuild();
		}

		if (page == PAGE_PREVIEW)
		{
			drawProjectionSample(gfx);
		}

		drawRows(gfx, screenWidth);
		drawStatus(gfx, screenWidth, screenHeight);
	}

	// ---- build 分块 ----

	// 三个子标签：外观 / 内容 / 投影；每页的行数都控制在"任意窗口高度都排得下"的范围里
	private void buildTabRow(ConfigPanel.Host host)
	{
		int tabSizeW = Math.max(72, host.width() / 10);
		int posX = host.width() / 2 - (tabSizeW + 2) * 3 / 2;

		host.add(tabBean(host, posX, "tab.hud", PAGE_APPEARANCE, tabSizeW));
		host.add(tabBean(host, posX + tabSizeW + 2, "tab.content", PAGE_CONTENT, tabSizeW));
		host.add(tabBean(host, posX + (tabSizeW + 2) * 2, "tab.preview", PAGE_PREVIEW, tabSizeW));
	}

	private Button tabBean(ConfigPanel.Host host, int posX, String key, int target, int sizeW)
	{
		Button button = Button.builder(Component.translatable(P + key), b ->
				{
					if (page == target) return;
					page = target;
					message = "";
					host.rebuild();
				})
				.pos(posX, TAB_Y).size(sizeW, TAB_H).build();
		button.active = page != target;
		return button;
	}

	// 外观页（6 行）：显示开关与文字外观 / 位置 / 偏移 / 大小
	private void buildAppearancePage(ConfigPanel.Host host)
	{
		UiSettings.Hud hud = UiSettings.hud();

		// 0 HUD 总开关
		addRow(0, "hud", List.of(button(CTRL_X, rowY(0), TOGGLE_W,
				Component.translatable(P + (hud.enabled ? "on" : "off")), () ->
				{
					hud.enabled = !hud.enabled;
					markDirty();
				})), null);

		// 1 文字阴影
		addRow(1, "shadow", List.of(button(CTRL_X, rowY(1), TOGGLE_W,
				Component.translatable(P + (hud.shadow ? "on" : "off")), () ->
				{
					hud.shadow = !hud.shadow;
					markDirty();
				})), null);

		// 2 文字颜色：十六进制输入 + 原版样式的「预设」按钮；行名本身用当前颜色画（见 drawRows）
		EditBox colorBox = colorField(2, hudColorDraft, text ->
				{
					Integer parsed = UiSettings.parseColor(text);
					if (parsed == null) return;
					hud.color = parsed;
					markDirty();
				});

		List<AbstractWidget> colorControls = new ArrayList<>();
		colorControls.add(colorBox);
		colorControls.add(button(CTRL_X + 92, rowY(2), 64, Component.translatable(P + "preset"), () ->
				{
					hud.color = UiSettings.nextPreset(hud.color);
					hudColorDraft = UiSettings.rgbHex(hud.color);
					markDirty();
				}));
		addRow(2, "color", colorControls, null, () -> hud.color);

		// 3 背景板 + 背景不透明度
		List<AbstractWidget> blockControls = new ArrayList<>();
		blockControls.add(button(CTRL_X, rowY(3), 68, Component.translatable(P + (hud.background ? "on" : "off")), () ->
				{
					hud.background = !hud.background;
					markDirty();
				}));
		blockControls.add(new Slider(CTRL_X + 76, rowY(3), 96, ctrlHeight(), 0.0D, 255.0D, 1.0D, hud.backgroundAlpha,
				v ->
				{
					hud.backgroundAlpha = (int) Math.round(v);
					markDirty();
				},
				v -> Component.literal(Math.round(v / 255.0D * 100.0D) + "%")));
		addRow(3, "block", blockControls, null);

		// 4 位置：九宫格锚点循环
		addRow(4, "anchor", List.of(button(CTRL_X, rowY(4), 130,
				Component.translatable(UiSettings.anchorKey(hud.anchor)), () ->
				{
					hud.cycleAnchor(1);
					markDirty();
				})), null);

		// 5 偏移：X / Y 各一对步进（像素级微调），数值画在 Y+ 右边
		List<AbstractWidget> offsets = new ArrayList<>();
		int offsetPosX = stepButton(offsets, CTRL_X, 5, "X-", () -> hud.offsetX -= 10);
		offsetPosX = stepButton(offsets, offsetPosX, 5, "X+", () -> hud.offsetX += 10);
		offsetPosX = stepButton(offsets, offsetPosX, 5, "Y-", () -> hud.offsetY -= 10);
		stepButton(offsets, offsetPosX, 5, "Y+", () -> hud.offsetY += 10);
		addRow(5, "offset", offsets, () -> hud.offsetX + ", " + hud.offsetY);

		// 6 大小
		addRow(6, "scale", List.of(new Slider(CTRL_X, rowY(6), 160, ctrlHeight(), 0.3D, 2.0D, 0.05D, hud.scale,
				v ->
				{
					hud.scale = v;
					markDirty();
				},
				v -> Component.literal(String.format("%.2fx", v)))), null);
	}

	// 内容页（5 行）：排版 + 8 个信息字段的开关与顺序
	private void buildContentPage(ConfigPanel.Host host)
	{
		UiSettings.Hud hud = UiSettings.hud();

		// 0 排版：单行 / 多行 + 分隔符循环
		List<AbstractWidget> layoutControls = new ArrayList<>();
		layoutControls.add(button(CTRL_X, rowY(0), 84,
				Component.translatable(P + (hud.multiLine ? "layout.multi" : "layout.single")), () ->
				{
					hud.multiLine = !hud.multiLine;
					markDirty();
				}));
		layoutControls.add(button(CTRL_X + 92, rowY(0), 96,
				Component.translatable(UiSettings.separatorName(hud.separator)), () ->
				{
					hud.cycleSeparator();
					markDirty();
				}));
		addRow(0, "layout", layoutControls, null);

		buildFieldGrid(host);
	}

	// 字段开关表：第 1 行起、两列 × 四行，每格 [开关][上移][下移]；数组顺序就是 HUD 上的显示顺序
	private void buildFieldGrid(ConfigPanel.Host host)
	{
		UiSettings.Hud hud = UiSettings.hud();
		int top = rowY(1);
		int cellSizeW = Math.max(110, (host.width() - EDGE * 2 - 12) / 2);

		for (int index = 0; index < hud.fields.size(); index++)
		{
			int line = index / 2;
			if (!fits(1 + line))
			{
				crowded = true;
				break;
			}

			UiSettings.Field field = hud.fields.get(index);
			int posY = top + line * rowSizeH;
			int posX = EDGE + (index % 2) * (cellSizeW + 12);

			// lambda 里要用下标：循环变量本身不是 effectively final，先接一个局部量
			int fieldIndex = index;

			host.add(button(posX, posY, cellSizeW - 40, Component.literal(fieldLabel(field)), () ->
					{
						field.enabled = !field.enabled;
						markDirty();
					}));
			host.add(button(posX + cellSizeW - 36, posY, 16, Component.literal("↑"), () ->
					{
						hud.moveField(fieldIndex, -1);
						markDirty();
					}));
			host.add(button(posX + cellSizeW - 18, posY, 16, Component.literal("↓"), () ->
					{
						hud.moveField(fieldIndex, 1);
						markDirty();
					}));

			rows.add(new Row(null, null, null, posY, null));
		}
	}

	// 投影页：颜色 / 不透明度 / 纹理显示度
	private void buildPreviewPage(ConfigPanel.Host host)
	{
		UiSettings.Preview preview = UiSettings.preview();

		// 0 投影颜色
		EditBox tintBox = colorField(0, tintDraft, text ->
				{
					Integer parsed = UiSettings.parseColor(text);
					if (parsed == null) return;
					preview.tint = parsed;
					markDirty();
				});

		// 投影染色：按钮走原版样式 + 「预设」；行名「投影颜色」本身用当前染色画（见 drawRows）
		List<AbstractWidget> tintControls = new ArrayList<>();
		tintControls.add(tintBox);
		tintControls.add(button(CTRL_X + 92, rowY(0), 64, Component.translatable(P + "preset"), () ->
				{
					preview.tint = UiSettings.nextPreset(preview.tint);
					tintDraft = UiSettings.rgbHex(preview.tint);
					markDirty();
				}));
		addRow(0, "tint", tintControls, null, () -> preview.tint);

		// 1 不透明度
		addRow(1, "alpha", List.of(new Slider(CTRL_X, rowY(1), 160, ctrlHeight(), 0.0D, 255.0D, 1.0D, preview.alpha,
				v ->
				{
					preview.alpha = (int) Math.round(v);
					markDirty();
				},
				v -> Component.literal(Math.round(v / 255.0D * 100.0D) + "%"))), null);

		// 2 方块纹理显示度：0 = 纯色投影，1 = 完整的方块贴图
		addRow(2, "mix", List.of(new Slider(CTRL_X, rowY(2), 160, ctrlHeight(), 0.0D, 1.0D, 0.01D, preview.textureMix,
				v ->
				{
					preview.textureMix = v;
					markDirty();
				},
				v -> Component.literal(Math.round(v * 100.0D) + "%"))), null);
	}

	// 底部：保存 / 重载 / 重置
	// 三个都只作用于「界面」这一份个人设置文件（NeoSim/Json/ui/<玩家名>.json），不碰内容表、不碰城市数据
	// 排布交给 ConfigPanel.layoutActions：它按可用宽度整体收窄，并已经扣掉右下角关闭按钮的预留区
	private void buildActionRow(ConfigPanel.Host host)
	{
		int limit = host.width() - EDGE - ConfigPanel.CLOSE_RESERVE - 8;
		List<ConfigPanel.BottomAction> actions = new ArrayList<>();

		actions.add(new ConfigPanel.BottomAction(64, Component.translatable(P + "save"), dirty(), this::save,
				b -> saveButton = b));
		actions.add(new ConfigPanel.BottomAction(64, Component.translatable(P + "reload"), true, this::reload));
		actions.add(new ConfigPanel.BottomAction(52, Component.translatable(confirmKey("page")), true, this::resetPage));

		ConfigPanel.layoutActions(host, actions, host.height() - ACTION_Y_OFFSET, limit);
	}

	// ---- render 分块 ----

	// 设置行：名称在左列，值紧跟本行最后一个控件
	private void drawRows(GuiGraphics gfx, int screenWidth)
	{
		Font font = Minecraft.getInstance().font;
		int valueRight = screenWidth - EDGE;

		for (Row row : rows)
		{
			if (row.labelKey() != null)
			{
				// 颜色行的行名就用当前颜色画：改没改、改成什么样，行名上直接看得见
				int color = row.labelColor() != null ? row.labelColor().getAsInt() : 0xAAAAAA;
				gfx.drawString(font, Component.translatable(P + "row." + row.labelKey()).getString(),
						EDGE, row.posY() + 4, color);
			}

			if (row.value() == null) continue;

			String text = row.value().get();
			if (text == null || text.isEmpty()) continue;

			// 值紧跟在本行最后一个控件右边（例如偏移的数字就在 Y+ 旁边），不拉到屏幕最右
			int controlsRight = EDGE;
			if (row.controls() != null)
			{
				for (AbstractWidget widget : row.controls())
				{
					controlsRight = Math.max(controlsRight, widget.getX() + widget.getWidth());
				}
			}

			// 窗口太窄时干脆不画，别顶到/盖住别的东西
			if (controlsRight + 10 + font.width(text) > valueRight) continue;

			gfx.drawString(font, text, controlsRight + 10, row.posY() + 4, 0x55FF55);
		}
	}

	// 投影示意：四个方块按当前染色与透明度叠一层，纹理显示度越低纯色越重
	private void drawProjectionSample(GuiGraphics gfx)
	{
		Font font = Minecraft.getInstance().font;
		UiSettings.Preview preview = UiSettings.preview();
		int posY = rowY(PREVIEW_ROWS) + 6;
		if (posY + 40 > rowLimit) return;

		int posX = EDGE;
		int tint = preview.tint & 0xFFFFFF;
		int flat = (int) Math.round(preview.alpha * (1.0D - preview.textureMix));
		int texture = (int) Math.round(preview.alpha * preview.textureMix);

		gfx.fill(EDGE - 4, posY - 4, EDGE + SAMPLE_BLOCKS.length * 24 + 4, posY + 20, 0xC0101618);

		for (ItemStack stack : SAMPLE_BLOCKS)
		{
			gfx.renderItem(stack, posX, posY);

			// 与真实渲染同序：先纯色层，再贴图层（这里是近似示意，不是逐像素复刻）
			if (flat > 0) gfx.fill(posX, posY, posX + 16, posY + 16, (flat << 24) | tint);
			if (texture > 0) gfx.fill(posX, posY, posX + 16, posY + 16, (texture << 24) | tint);
			posX += 24;
		}

		gfx.drawString(font, Component.translatable(P + "preview.hint2").getString(), EDGE, posY + 26, 0x777777);
	}

	// 底部状态行：保存状态 / 操作结果 / 窗口提示
	private void drawStatus(GuiGraphics gfx, int screenWidth, int screenHeight)
	{
		Font font = Minecraft.getInstance().font;
		int posY = screenHeight - 20;

		int limit = screenWidth - ConfigPanel.CLOSE_RESERVE - 8;
		boolean unsaved = dirty();
		boolean otherDirty = UiSettings.isDirty(page == PAGE_PREVIEW ? UiSettings.Section.HUD : UiSettings.Section.PREVIEW);

		String text;
		if (!message.isEmpty())
		{
			text = message;
		}
		else if (crowded && !unsaved)
		{
			// 有设置行没排下：告诉玩家是窗口太矮，而不是设置丢了
			text = Component.translatable(P + "status.crowded").getString();
		}
		else
		{
			text = Component.translatable(P + (unsaved ? "status.unsaved" : "status.saved")).getString();

			// 另一段还挂着未保存的改动：说一声，免得玩家切过去才发现没存
			if (otherDirty)
			{
				text = text + "  ·  " + Component.translatable(P + "status.other").getString();
			}
		}

		String shown = font.plainSubstrByWidth(text, Math.max(40, limit - EDGE));
		gfx.drawString(font, shown, EDGE, posY, unsaved && message.isEmpty() ? 0xFFFF55 : 0x55FF55);
	}

	// ---- 操作 ----

	private void save()
	{
		boolean ok = UiSettings.save(section());
		message = Component.translatable(P + (ok ? "saved" : "saveFailed")).getString();
		rebuild();
	}

	private void reload()
	{
		UiSettings.reloadSection(section());
		message = Component.translatable(P + "reloaded").getString();
		rebuild();
	}

	// 重置本页：只恢复当前子页那一段设置
	private void resetPage()
	{
		if (!confirm("page")) return;

		// 外观页与内容页都是 HUD，只有投影页单独一段
		if (page == PAGE_PREVIEW)
		{
			UiSettings.resetPreview();
		}
		else
		{
			UiSettings.resetHud();
		}

		finishReset("resetPage");
	}

	private void finishReset(String messageKey)
	{
		UiSettings.save(section());
		message = Component.translatable(P + messageKey).getString();
		rebuild();
	}

	// 二次确认：第一次点只把按钮文案换成「确认重置」，3 秒内再点才真的重置
	private boolean confirm(String action)
	{
		long now = System.currentTimeMillis();
		if (confirmAction.equals(action) && now <= confirmUntil)
		{
			confirmAction = "";
			return true;
		}

		confirmAction = action;
		confirmUntil = now + CONFIRM_MS;
		rebuild();
		return false;
	}

	// 按钮文案：平时是「重置」，点过一次变「确认重置？」
	// 注意用的是 reset.page（按钮短文案），不是 resetPage（重置完成后的提示句）
	private String confirmKey(String action)
	{
		boolean pending = confirmAction.equals(action) && System.currentTimeMillis() <= confirmUntil;
		return P + (pending ? "confirm" : "reset.page");
	}

	// 当前子页对应的设置段：外观页与内容页都是 HUD，投影页单独一段
	private UiSettings.Section section()
	{
		return page == PAGE_PREVIEW ? UiSettings.Section.PREVIEW : UiSettings.Section.HUD;
	}

	// 本段是否有未保存的改动（保存按钮的可用状态、状态行都读它）
	private boolean dirty()
	{
		return UiSettings.isDirty(section());
	}

	// 改内存状态：置脏 + 让 HUD 的缓存失效。**不重建控件**——
	// 重建会把正在输入的 EditBox、正在拖的滑块换成新实例（表现就是"一次只能输入一个字"、拖一下就断）
	private void markDirty()
	{
		message = "";
		UiSettings.markDirty(section());
		UiSettings.touch();

		// 保存按钮的可用状态挂在控件上：拖滑块 / 改颜色不重建控件（重建会把正在拖的滑块、正在输入的框换掉），
		// 所以这里直接把它点亮——否则一直是灰的，看着像"拖了却没生效"
		if (saveButton != null)
		{
			saveButton.active = true;
		}
	}

	private void rebuild()
	{
		if (building || host == null) return;
		host.rebuild();
	}

	// ---- 小工具 ----

	// 本页排几行：行高按它自适应
	private int rowsOnPage()
	{
		return switch (page)
		{
			case PAGE_CONTENT -> CONTENT_ROWS;
			case PAGE_PREVIEW -> PREVIEW_ROWS;
			default -> APPEARANCE_ROWS;
		};
	}

	// 排版：顶边固定，行高 = 可用高度 ÷ 本页行数，夹在上下限之间
	// 底部操作行在 height - 46，这里留 6px 余量——所以设置行永远不会压到保存 / 关闭那排按钮上
	private void computeLayout()
	{
		int bottom = host.height() - ACTION_Y_OFFSET - 6;

		// 子标签行是 y=86、高 16（占到 102），顶边至少留 4px 才不会顶上去
		rowTop = host.height() < 380 ? 106 : 110;
		rowSizeH = Math.max(ROW_H_MIN, Math.min(ROW_H_MAX, (bottom - rowTop) / Math.max(1, rowsOnPage())));
		rowLimit = bottom;
	}

	// 这一行放得下吗：放不下就整行不排（状态行会提示"窗口太矮"），绝不叠到下面的按钮上
	private boolean fits(int index)
	{
		return rowY(index) + rowSizeH <= rowLimit;
	}

	// 控件比行高矮 2：行间不留白，也不让相邻控件贴在一起
	private int ctrlHeight()
	{
		return Math.max(9, rowSizeH - 2);
	}

	private int rowY(int index)
	{
		return rowTop + index * rowSizeH;
	}

	private void addRow(int index, String labelKey, List<? extends AbstractWidget> controls, Supplier<String> value)
	{
		addRow(index, labelKey, controls, value, null);
	}

	private void addRow(int index, String labelKey, List<? extends AbstractWidget> controls, Supplier<String> value,
			IntSupplier labelColor)
	{
		if (!fits(index))
		{
			// 窗口太矮：整行不排，避免压到底部操作行上
			crowded = true;
			return;
		}

		for (AbstractWidget widget : controls)
		{
			host.add(widget);
		}
		rows.add(new Row(labelKey, controls, value, rowY(index), labelColor));
	}

	private Button button(int posX, int posY, int sizeW, Component label, Runnable press)
	{
		return Button.builder(label, b ->
				{
					press.run();
					markDirty();
					rebuild();
				})
				.pos(posX, posY).size(sizeW, ctrlHeight()).build();
	}

	// 步进按钮：按住一次 ±10 像素
	private int stepButton(List<AbstractWidget> into, int posX, int index, String label, Runnable change)
	{
		into.add(Button.builder(Component.literal(label), b ->
				{
					change.run();
					markDirty();
				})
				.pos(posX, rowY(index)).size(34, ctrlHeight()).build());
		return posX + 36;
	}

	// 十六进制颜色输入框：格式不对就保持当前颜色不动（值列会显示真正生效的颜色）
	private EditBox colorField(int index, String value, Consumer<String> apply)
	{
		EditBox box = new EditBox(Minecraft.getInstance().font, CTRL_X, rowY(index), 88, ctrlHeight(),
				Component.translatable(P + "row.color"));
		box.setMaxLength(9);
		box.setValue(value);
		box.setHint(Component.literal("#RRGGBB"));
		box.setResponder(apply::accept);
		return box;
	}

	private String fieldLabel(UiSettings.Field field)
	{
		return (field.enabled ? "\u2714 " : "\u2718 ") + UiSettings.fieldName(field.key);
	}

	// 通用滑块：归一化后交给原版 AbstractSliderButton，标签与写回由调用方给
	// 滑动过程中只改内存（不重建控件），否则正在拖的那个滑块会被换掉
	private static class Slider extends AbstractSliderButton
	{
		private final double min;
		private final double max;
		private final double step;
		private final DoubleConsumer onChange;
		private final Function<Double, Component> label;

		Slider(int posX, int posY, int sizeW, int sizeH, double min, double max, double step, double value,
				DoubleConsumer onChange, Function<Double, Component> label)
		{
			super(posX, posY, sizeW, sizeH, Component.empty(),
					(Math.min(Math.max(value, min), max) - min) / (max - min));
			this.min = min;
			this.max = max;
			this.step = step;
			this.onChange = onChange;
			this.label = label;
			updateMessage();
		}

		// 当前值：按步长取整，免得攒出 0.30000000000000004 这种数字
		private double current()
		{
			double raw = min + (max - min) * this.value;
			return step <= 0.0D ? raw : Math.round(raw / step) * step;
		}

		@Override
		protected void updateMessage()
		{
			setMessage(label.apply(current()));
		}

		@Override
		protected void applyValue()
		{
			onChange.accept(current());
		}
	}
}
