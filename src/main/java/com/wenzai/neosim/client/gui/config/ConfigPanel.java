package com.wenzai.neosim.client.gui.config;

import com.wenzai.neosim.compat.attached.AttachMode;
import com.wenzai.neosim.compat.attached.AttachedBlockTable;
import com.wenzai.neosim.compat.attached.AttachedRule;
import com.wenzai.neosim.compat.crops.CropRegistry;
import com.wenzai.neosim.compat.crops.CropRule;
import com.wenzai.neosim.json.BlockMatcher;
import com.wenzai.neosim.json.JsonContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

// 配置中心（城市信息 GUI 的「配置」页）
// 结构：分类按钮 → 工具行（文件标签 / 搜索 / 筛选）→ 列表 → 底部两行操作 → 右侧详情面板
// 两张内容表（依附方块表、模组作物表）共用同一套列表与操作，差异点集中在 TableKind 分支里
public class ConfigPanel
{
	// ---- 宿主 ----

	public interface Host
	{
		void add(AbstractWidget widget);

		void rebuild();

		// 把焦点交给指定控件（重建后恢复搜索框焦点用）
		void focus(AbstractWidget widget);

		int width();

		int height();

		Font font();
	}

	// ---- 常量 ----

	private static final String P = "gui.neosim.config.";

	// 布局：顶部整块上移；分类按钮高 18；内容区组件统一 16 高、行距 16（行间不留白）
	private static final int STATUS_Y = 51;
	private static final int CATEGORY_Y = 62;
	private static final int CATEGORY_H = 18;
	private static final int TOOLBAR_Y = 86;
	private static final int TOOLBAR_H = 16;
	private static final int GRID_TOP = 120;
	private static final int ROW_H = 16;
	private static final int ROW_BTN_H = 16;

	// 底部两行：第一行内容操作，第二行文件操作与翻页（贴近窗口底部，两行间 6px）
	private static final int ACTION_Y_OFFSET = 46;
	private static final int FILE_ACTION_Y_OFFSET = 24;
	private static final int ACTION_H = 16;
	private static final int ACTION_GAP = 10;

	private static final int EDGE = 24;

	// 详情面板：上沿在工具行下沿（比列表上沿更靠上），下沿取 height-56 与 上沿+最大高度 的较小者
	private static final int DETAIL_TOP = TOOLBAR_Y + 6;
	private static final int DETAIL_MAX_H = 340;

	// 底部右侧留给「关闭」按钮的宽度（CityInfoGui 用它摆按钮，面板用它算按钮行的可用宽度；两边共用这一个数）
	public static final int CLOSE_RESERVE = 104;

	// 详情面板宽 = width / DETAIL_DIV（列表右侧相应变短）
	private static final int DETAIL_DIV = 3;

	private static final String[] CATEGORY_KEYS =
			{ "category.compat", "category.building", "category.work", "category.mapping", "category.ui" };

	// 各分类计划中的文件（未开放分类的占位提示）
	private static final String[][] CATEGORY_FILES = {
			{ "attached_blocks.json", "crops.json", "modded_blocks.json" },
			{ "materials.json", "rules.json" },
			{ "mine_filter.json", "farm_crops.json" },
			{ "block_id_mapping.json" },
			{ "个人设置：<玩家名>.json" },
	};

	// 可管理的文件
	private enum TableKind
	{
		ATTACHED, CROPS
	}

	private enum Filter { ALL, ATTACHED, SOLID }

	// 列表行：两张表统一成同一模型，external=false 表示只读（内置类型规则 / 自动检测结果）
	private record Row(String title, String subtitle, String state, boolean external, int ruleIndex,
					   boolean enabled, String note, ItemStack icon, String blockId,
					   boolean needsWater, boolean excluded, boolean attached) {}

	// 底部一个操作按钮：宽度只是首选值，排不下时整行一起收窄（见 layoutActions）
	// onBuild 可以把建好的按钮交回调用方：保存按钮要靠它把"可用"状态即时点亮，而不是等下次重建
	record BottomAction(int sizeW, Component label, boolean active, Runnable action,
						@Nullable Consumer<Button> onBuild)
	{
		BottomAction(int sizeW, Component label, boolean active, Runnable action)
		{
			this(sizeW, label, active, action, null);
		}
	}

	// ---- 状态 ----

	private final List<AttachedRule> attachedEditable = new ArrayList<>();
	private final List<CropRule> cropEditable = new ArrayList<>();
	private final List<Row> allRows = new ArrayList<>();
	private final List<Row> rows = new ArrayList<>();

	private TableKind kind = TableKind.ATTACHED;
	private String search = "";
	private Filter filter = Filter.ALL;
	private int page;
	private int pageRows = 6;
	private int selected = -1;

	// 两张表的未保存改动各自记账：切换文件不拦截，保存时只写有改动的那张
	private boolean attachedDirty;
	private boolean cropDirty;
	private String message = "";

	// 当前选中的分类下标；-1 = 尚未选择（配置页初始只显示 5 个分类按钮）
	private int selectedCategory = -1;

	// build() 期间置位：搜索框 setValue 会触发 responder，不能在重建过程中再次 rebuild（会无限递归）
	private boolean building;

	// 下一次 build() 结束后是否把焦点还给搜索框（只生效一次）
	private boolean focusSearch;

	// 宿主屏幕：编辑动作改完内存状态后要重建控件，按钮文案 / 行标记才会立即刷新
	private Host host;

	private EditBox searchBox;

	// 界面分类（第 5 个按钮）的内容面板：HUD / 投影外观设置
	private final UiPanel uiPanel = new UiPanel();

	public ConfigPanel()
	{
		reloadFromTables();
	}

	// ---- 对外接口 ----

	// 从内容表重新读取外部规则（丢弃未保存改动）
	public void reloadFromTables()
	{
		attachedEditable.clear();
		attachedEditable.addAll(AttachedBlockTable.externalRules());
		cropEditable.clear();
		cropEditable.addAll(CropRegistry.externalRules());
		attachedDirty = false;
		cropDirty = false;
		rebuildRows();
	}

	public boolean isDirty()
	{
		return attachedDirty || cropDirty;
	}

	// 记下是哪张表脏了：切换文件后两张表仍能分别保存
	private void markDirty(TableKind table)
	{
		if (table == TableKind.ATTACHED) attachedDirty = true;
		else cropDirty = true;
	}

	public void build(Host host)
	{
		this.host = host;
		building = true;

		buildCategoryRow(host, host.width());

		// 界面分类：内容整块交给 UiPanel（HUD / 投影两个子标签）
		if (selectedCategory == 4)
		{
			uiPanel.build(host);
			building = false;
			return;
		}

		// 未选择分类 / 选中未开放分类：只留分类按钮，内容区由 render() 画提示
		if (selectedCategory != 0)
		{
			building = false;
			return;
		}

		buildToolbar(host, host.width());
		buildList(host, host.width(), host.height());
		buildActionRows(host, host.height());

		building = false;
	}

	public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick)
	{
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

		if (!renderPlaceholder(gfx, screenWidth))
		{
			return;
		}

		// 界面分类：内容区整块交给 UiPanel
		if (selectedCategory == 4)
		{
			uiPanel.render(gfx, mouseX, mouseY, partialTick);
			return;
		}

		renderStatusLine(gfx, screenWidth);
		renderInfoLine(gfx, screenWidth);
		renderListIcons(gfx);
		renderDetail(gfx, screenWidth, screenHeight);
	}

	// ---- build 分块 ----

	// 5 个分类按钮：点哪个才显示哪个分类的内容
	private void buildCategoryRow(Host host, int screenWidth)
	{
		int categorySizeW = Math.max(58, screenWidth / 12);
		int categoryPosX = screenWidth / 2 - categorySizeW * CATEGORY_KEYS.length / 2;

		for (int i = 0; i < CATEGORY_KEYS.length; i++)
		{
			final int index = i;
			Button b = Button.builder(Component.translatable(P + CATEGORY_KEYS[i]), btn ->
					{
						selectedCategory = index;
						page = 0;
						selected = -1;
						message = "";
						reloadFromTables();
						host.rebuild();
					})
					.pos(categoryPosX + i * categorySizeW, CATEGORY_Y)
					.size(categorySizeW - 2, CATEGORY_H)
					.build();
			b.active = i != selectedCategory;
			host.add(b);
		}
	}

	// 工具行：文件标签 + 搜索框
	private void buildToolbar(Host host, int screenWidth)
	{
		int fileSizeW = Math.min(110, Math.max(80, rightLimit(screenWidth) / 7));
		for (TableKind value : TableKind.values())
		{
			Button b = Button.builder(Component.translatable(P + "file." + value.name().toLowerCase(Locale.ROOT)), btn ->
					{
						if (kind == value) return;
						kind = value;
						if (value == TableKind.CROPS) filter = Filter.ALL;
						page = 0;
						selected = -1;
						message = "";
						rebuildRows();
						host.rebuild();
					})
					.pos(EDGE + value.ordinal() * (fileSizeW + 6), TOOLBAR_Y)
					.size(fileSizeW, TOOLBAR_H)
					.build();

			// 当前文件置灰；两张表的改动各自留在内存里，切换不拦（保存时各自落盘）
			b.active = value != kind;
			host.add(b);
		}

		searchBox = new EditBox(host.font(),
				EDGE + TableKind.values().length * (fileSizeW + 6) + 8, TOOLBAR_Y,
				Math.max(90, Math.min(160, rightLimit(screenWidth) / 4)), TOOLBAR_H,
				Component.translatable(P + "search"));
		searchBox.setMaxLength(64);
		searchBox.setValue(search);
		searchBox.setResponder(text ->
		{
			search = text;
			page = 0;
			applyFilter();

			// 重建后要把焦点还给搜索框（见 focusSearch），否则每输入一个字符就失去焦点
			focusSearch = !building;
			if (!building) host.rebuild();
		});
		host.add(searchBox);

		if (focusSearch)
		{
			host.focus(searchBox);
			searchBox.setCursorPosition(search.length());
			focusSearch = false;
		}

		// 模式筛选只对依附方块表有意义：贴列表顶右上角（详情面板左侧、列表第一行正上方）
		if (kind == TableKind.ATTACHED)
		{
			host.add(Button.builder(filterLabel(), b ->
					{
						filter = switch (filter)
						{
							case ALL -> Filter.ATTACHED;
							case ATTACHED -> Filter.SOLID;
							case SOLID -> Filter.ALL;
						};
						page = 0;
						applyFilter();
						host.rebuild();
					})
					.pos(filterLeft(screenWidth), GRID_TOP - TOOLBAR_H)
					.size(filterWidth(screenWidth), TOOLBAR_H)
					.build());
		}
	}

	// 列表：单列、行按钮紧贴、行数随窗口高度自适应
	private void buildList(Host host, int screenWidth, int screenHeight)
	{
		int listSizeW = rightLimit(screenWidth) - EDGE;
		pageRows = rowsPerPage(screenHeight);
		int start = page * pageRows;

		for (int i = 0; i < pageRows; i++)
		{
			int index = start + i;
			if (index >= rows.size()) break;

			Button rowButton = Button.builder(rowLabel(rows.get(index)), btn ->
					{
						selected = index;
						message = "";
						host.rebuild();
					})
					.pos(EDGE, GRID_TOP + i * ROW_H)
					.size(listSizeW, ROW_BTN_H)
					.build();

			// 被点中的那一行置灰，作为"当前选中"的视觉标记
			rowButton.active = index != selected;
			host.add(rowButton);
		}
	}

	// 底部两行：内容操作 + 文件操作与翻页
	// 一块宽度先让给右下角的「关闭」按钮（CLOSE_RESERVE），按钮排在可用宽度里等比收窄，窄窗口也不会压上去
	private void buildActionRows(Host host, int screenHeight)
	{
		Row sel = selectedRow();
		boolean hasSelection = sel != null;
		boolean externalSelection = hasSelection && sel.external();
		boolean attached = kind == TableKind.ATTACHED;
		int limit = host.width() - EDGE - CLOSE_RESERVE - 8;

		// 第一行：表相关的操作
		List<BottomAction> first = new ArrayList<>();
		if (attached)
		{
			first.add(new BottomAction(100, Component.translatable(P + "addHeld"), heldBlock() != null, this::addHeldBlock));
			first.add(new BottomAction(92, Component.translatable(P + "cycleMode"), externalSelection, this::cycleMode));
		}
		else
		{
			first.add(new BottomAction(92, Component.translatable(P + "rescan"), true, this::rescanCrops));
		}

		// 文案跟着选中项状态走：已启用 → "停用"，已停用 → "启用"
		boolean willDisable = !hasSelection || sel.enabled();
		first.add(new BottomAction(84, Component.translatable(P + (willDisable ? "disable" : "enable")),
				hasSelection, this::toggleEnabled));

		if (!attached)
		{
			// 需水 / 排除同理：按钮写的就是"点下去会发生什么"（未选中时保持默认文案）
			boolean willNeedWater = !hasSelection || !sel.needsWater();
			boolean willExclude = !hasSelection || !sel.excluded();
			first.add(new BottomAction(72, Component.translatable(P + (willNeedWater ? "needsWater" : "crop.noWater")),
					hasSelection, () -> toggleCropFlag(1)));
			first.add(new BottomAction(80, Component.translatable(P + (willExclude ? "exclude" : "unexclude")),
					hasSelection, () -> toggleCropFlag(2)));
		}

		first.add(new BottomAction(64, Component.translatable(P + "remove"), externalSelection, this::removeSelected));
		layoutActions(host, first, screenHeight - ACTION_Y_OFFSET, limit);

		// 第二行：保存 / 重载 / 翻页
		int pages = pageCount();
		List<BottomAction> second = new ArrayList<>();
		second.add(new BottomAction(72, Component.translatable(P + "save"), isDirty(), this::save));
		second.add(new BottomAction(72, Component.translatable(P + "reload"), true, () -> reload(host)));
		second.add(new BottomAction(64, Component.translatable(P + "prevPage"), page > 0, () ->
		{
			if (page > 0) page--;
			host.rebuild();
		}));
		second.add(new BottomAction(64, Component.translatable(P + "nextPage"), page < pages - 1, () ->
		{
			if (page < pages - 1) page++;
			host.rebuild();
		}));
		layoutActions(host, second, screenHeight - FILE_ACTION_Y_OFFSET, limit);
	}

	// 排一行底部按钮：宽度只是首选值，整行放不下就一起收窄（间距同步收），保证右端不越过 limit；
	// limit 已经扣掉了右下角关闭按钮的预留区，所以两处永远不会叠
	static void layoutActions(Host host, List<BottomAction> actions, int posY, int limit)
	{
		if (actions.isEmpty()) return;

		int totalSizeW = 0;
		for (BottomAction action : actions)
		{
			totalSizeW += action.sizeW();
		}

		int preferredGap = ACTION_GAP * (actions.size() - 1);
		int availableSizeW = Math.max(96, limit - preferredGap - EDGE);
		double scale = totalSizeW > availableSizeW ? availableSizeW / (double) totalSizeW : 1.0D;
		int gap = Math.max(4, (int) Math.round(ACTION_GAP * scale));

		int posX = EDGE;
		for (BottomAction action : actions)
		{
			// 下限给得比较小：宁可按钮变窄，也不让整行越过 limit 压到关闭按钮上
			int btnSizeW = Math.max(20, (int) Math.round(action.sizeW() * scale));
			Button button = Button.builder(action.label(), b -> action.action().run())
					.pos(posX, posY).size(btnSizeW, ACTION_H).build();
			button.active = action.active();
			host.add(button);

			if (action.onBuild() != null)
			{
				action.onBuild().accept(button);
			}
			posX += btnSizeW + gap;
		}
	}

	// ---- render 分块 ----

	// 未选择分类 / 分类未开放：只画提示，返回 false 表示内容区不渲染
	private boolean renderPlaceholder(GuiGraphics gfx, int screenWidth)
	{
		Font font = Minecraft.getInstance().font;

		if (selectedCategory < 0)
		{
			gfx.drawCenteredString(font, Component.translatable(P + "pickCategory").getString(),
					screenWidth / 2, STATUS_Y, 0xAAFFFF);
			return false;
		}

		if (selectedCategory != 0 && selectedCategory != 4)
		{
			gfx.drawCenteredString(font, Component.translatable(P + "category.notOpen").getString(),
					screenWidth / 2, STATUS_Y, 0xFFAA00);
			gfx.drawCenteredString(font, Component.translatable(P + "category.plan",
							String.join(" · ", CATEGORY_FILES[selectedCategory])).getString(),
					screenWidth / 2, STATUS_Y + 20, 0xAAAAAA);
			return false;
		}

		return true;
	}

	// 状态行：两张表各自的加载摘要 + 解析错误提示
	private void renderStatusLine(GuiGraphics gfx, int screenWidth)
	{
		String errors = JsonContent.readErrors();
		String status = kind == TableKind.ATTACHED ? attachedStatus() : cropStatus();

		// 模组依赖性方块判定结果：依附方块表页脚常驻（同一轮建造口径，和依附方块一起看）
		if (kind == TableKind.ATTACHED)
		{
			com.wenzai.neosim.compat.modded.ModBlockRegistry.Stats modStats =
					com.wenzai.neosim.compat.modded.ModBlockRegistry.stats();
			status = status + "  ·  " + Component.translatable(P + "status.modded", modStats.moddedBlocks(),
					modStats.dependentBlocks(), modStats.namespaces(), modStats.excludedDeferred()).getString();
		}
		if (!errors.isEmpty()) status = status + "  ·  " + Component.translatable(P + "errorHint").getString();
		gfx.drawCenteredString(Minecraft.getInstance().font, status, screenWidth / 2, STATUS_Y,
				errors.isEmpty() ? 0xAAFFFF : 0xFFFF5555);
	}

	// 两张表各自的加载摘要
	private String attachedStatus()
	{
		AttachedBlockTable.Stats stats = AttachedBlockTable.stats();
		return Component.translatable(P + "status.attached", stats.externalRules(), stats.builtinRules(),
				stats.attachedBlocks()).getString();
	}

	private String cropStatus()
	{
		CropRegistry.Stats stats = CropRegistry.stats();
		return Component.translatable(P + "status.crops", stats.detectedCrops(), stats.effectiveCrops(),
				stats.externalRules(), stats.builtinRules()).getString();
	}

	// 列表上方一行：页码 / 未保存 / 操作结果；依附方块表筛选按钮占了右上角，这行文字在它左侧截断
	private void renderInfoLine(GuiGraphics gfx, int screenWidth)
	{
		Font font = Minecraft.getInstance().font;
		int posY = GRID_TOP - 12;
		int pages = Math.max(1, (rows.size() + pageRows - 1) / pageRows);
		int rightEdge = kind == TableKind.ATTACHED ? filterLeft(screenWidth) - 6 : screenWidth;

		gfx.enableScissor(0, posY - 1, rightEdge, posY + font.lineHeight + 1);
		gfx.drawString(font, Component.translatable(P + "pageInfo", page + 1, pages, rows.size()).getString(),
				EDGE, posY, 0xAAAAAA);
		if (isDirty())
		{
			gfx.drawString(font, Component.translatable(P + "unsaved").getString(), EDGE + 200, posY, 0xFFFF55);
		}
		if (!message.isEmpty())
		{
			gfx.drawString(font, message, EDGE + 300, posY, 0x55FF55);
		}
		gfx.disableScissor();
	}

	// 行内方块图标（叠在行按钮左侧）
	private void renderListIcons(GuiGraphics gfx)
	{
		int start = page * pageRows;
		for (int i = 0; i < pageRows; i++)
		{
			int index = start + i;
			if (index >= rows.size()) break;
			ItemStack icon = rows.get(index).icon();
			if (icon.isEmpty()) continue;
			gfx.renderItem(icon, EDGE + 3, GRID_TOP + i * ROW_H);
		}
	}

	// 右侧详情面板：长文本自动换行（最多 2 行）
	private void renderDetail(GuiGraphics gfx, int screenWidth, int screenHeight)
	{
		Font font = Minecraft.getInstance().font;
		int panelSizeW = screenWidth / DETAIL_DIV;
		int panelPosX = screenWidth - panelSizeW - 4;
		int panelTop = DETAIL_TOP;
		int panelBottom = Math.min(screenHeight - (ACTION_Y_OFFSET + 10), panelTop + DETAIL_MAX_H);

		gfx.fill(panelPosX - 6, panelTop - 6, screenWidth - 8, panelBottom, 0x90000000);

		Row row = selectedRow();
		if (row == null)
		{
			gfx.drawString(font, Component.translatable(P + "detail.none").getString(), panelPosX, panelTop, 0xAAAAAA);
			return;
		}

		int posY = panelTop;
		posY = drawWrapped(font, gfx, row.title(), panelPosX, posY, panelSizeW - 16, 0xFFFFFF, 2) + 2;
		posY = drawWrapped(font, gfx, row.state(), panelPosX, posY, panelSizeW - 12, 0xFFFF55, 2) + 2;
		posY = drawWrapped(font, gfx, row.subtitle(), panelPosX, posY, panelSizeW - 12, 0xAAAAAA, 2) + 2;

		gfx.drawString(font, Component.translatable(P + "detail.source",
						Component.translatable(P + (row.external() ? "source.external" : "source.builtin")).getString())
				.getString(), panelPosX, posY, row.external() ? 0x55FF55 : 0xAAAAAA);
		posY += font.lineHeight + 6;

		if (!row.note().isEmpty())
		{
			posY = drawWrapped(font, gfx, row.note(), panelPosX, posY, panelSizeW - 12, 0xAAAAAA, 2) + 2;
		}

		gfx.drawString(font, Component.translatable(P + (row.enabled() ? "state.on" : "state.off")).getString(),
				panelPosX, posY, row.enabled() ? 0x55FF55 : 0xAAAAAA);
	}

	// ---- 行数据 ----

	private void rebuildRows()
	{
		allRows.clear();
		if (kind == TableKind.ATTACHED)
		{
			buildAttachedRows();
		}
		else
		{
			buildCropRows();
		}
		applyFilter();
	}

	// 依附方块表：列表只列"可以更改的"外部 JSON 规则；只读的内置类型规则不占行（判定链与优先级不变）
	// 排序：先按依附模式分组（自动 / 非依附 / 任意 / 贴墙 / 地面 / 悬挂），同模式内按匹配表达式
	private void buildAttachedRows()
	{
		List<Integer> order = ruleOrder(attachedEditable.size(),
				i -> attachedEditable.get(i).mode().ordinal(),
				i -> attachedEditable.get(i).expression());

		for (int index : order)
		{
			AttachedRule rule = attachedEditable.get(index);
			String id = exactId(rule.value(), rule.kind());
			allRows.add(new Row(rule.expression(), Component.translatable(P + "detail.rule", index).getString(),
					modeState(rule.mode().id()), true, index, rule.enabled(), rule.note(),
					iconOf(id), id, false, false, rule.phase() == 2));
		}
	}

	// 模组作物表：自动检测结果与指向它的外部规则合并成同一行（同一作物只出现一次）；
	// 未被任何检测作物命中的规则（命名空间 / 标签 / 正则 / 已卸载模组的 id）才单独成行
	private void buildCropRows()
	{
		Set<String> detectedIds = new HashSet<>();
		for (CropRegistry.Detected crop : CropRegistry.detected)
		{
			detectedIds.add(crop.blockId());

			int ruleIndex = cropRuleIndex(crop.blockId());
			CropRule rule = ruleIndex >= 0 ? cropEditable.get(ruleIndex) : null;

			// 行上显示"当前状态"：外部规则已声明的字段优先（内存里改完立刻可见），未声明的回落到已生效的检测结果
			boolean needsWater = rule != null && rule.needsWater() != null ? rule.needsWater() : crop.needsWater();
			boolean excluded = rule != null && rule.excluded() != null ? rule.excluded() : crop.excluded();
			boolean enabled = rule == null || !Boolean.FALSE.equals(rule.enabled());

			// 行首圆点看"是否生效"：启用且未排除才是 ●（否则点启用看不出变化）
			boolean active = enabled && !excluded;
			String matureId = rule != null && rule.matureId() != null ? rule.matureId() : crop.matureId();

			String subtitle = rule != null
					? Component.translatable(P + "detail.detectedRule", ruleIndex).getString()
					: Component.translatable(P + "detail.detected").getString();

			allRows.add(new Row(crop.blockId(), subtitle,
					cropState(needsWater, excluded || !enabled, matureId),
					rule != null, ruleIndex, active, rule != null ? rule.note() : "",
					itemOf(crop.blockId()), crop.blockId(), needsWater, excluded, false));
		}

		// 未被命中的外部规则（命名空间 / 标签 / 正则 / 已卸载模组的 id）单独成行
		for (int index = 0; index < cropEditable.size(); index++)
		{
			CropRule rule = cropEditable.get(index);
			String id = exactId(rule.value(), rule.kind());
			if (id != null && detectedIds.contains(id)) continue;

			boolean needsWater = Boolean.TRUE.equals(rule.needsWater());
			boolean excluded = Boolean.TRUE.equals(rule.excluded());
			boolean enabled = !Boolean.FALSE.equals(rule.enabled());
			boolean active = enabled && !excluded;
			allRows.add(new Row(rule.expression(), Component.translatable(P + "detail.rule", index).getString(),
					cropState(needsWater, excluded || !enabled, rule.matureId()),
					true, index, active, rule.note(),
					iconOf(id), id, needsWater, excluded, false));
		}

		// 同类作物排一起：农田（可种）→ 需水 → 非农田（已排除），组内按注册名 / 表达式
		allRows.sort(Comparator.comparingInt(ConfigPanel::cropGroup)
				.thenComparing(Row::title, String.CASE_INSENSITIVE_ORDER));
	}

	private String modeState(String modeId)
	{
		return Component.translatable(P + "detail.mode",
				Component.translatable(P + "mode." + modeId).getString()).getString();
	}

	private String cropState(boolean needsWater, boolean excluded, @Nullable String matureId)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(Component.translatable(P + (needsWater ? "crop.water" : "crop.noWater")).getString());
		if (excluded) sb.append(" · ").append(Component.translatable(P + "crop.excluded").getString());
		if (matureId != null) sb.append(" · ").append(Component.translatable(P + "crop.mature", matureId).getString());
		return sb.toString();
	}

	private void applyFilter()
	{
		rows.clear();
		String needle = search.toLowerCase(Locale.ROOT).trim();

		for (Row row : allRows)
		{
			if (!needle.isEmpty() && !row.title().toLowerCase(Locale.ROOT).contains(needle)
					&& !row.subtitle().toLowerCase(Locale.ROOT).contains(needle))
			{
				continue;
			}

			// 模式筛选只作用于依附方块表
			if (kind == TableKind.ATTACHED)
			{
				if (filter == Filter.ATTACHED && !row.attached()) continue;
				if (filter == Filter.SOLID && row.attached()) continue;
			}
			rows.add(row);
		}

		if (selected >= rows.size()) selected = -1;
	}

	// ---- 操作 ----

	@Nullable
	private Block heldBlock()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		ItemStack held = mc.player.getMainHandItem();
		return held.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
	}

	private void addHeldBlock()
	{
		Block block = heldBlock();
		if (block == null) return;
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();

		for (AttachedRule rule : attachedEditable)
		{
			if (rule.kind() == BlockMatcher.Kind.ID && rule.value().equals(id))
			{
				message = Component.translatable(P + "alreadyAdded", id).getString();
				return;
			}
		}

		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		obj.addProperty("id", id);
		obj.addProperty("attach", AttachMode.AUTO.id());
		AttachedRule added = AttachedRule.fromJson(obj, AttachedRule.Source.EXTERNAL, attachedEditable.size());
		if (added == null) return;

		attachedEditable.add(added);
		markDirty(kind);
		message = Component.translatable(P + "added", id).getString();
		rebuildRows();
		refresh();
	}

	// 作物表：重新扫描并把检测结果写回 JSON
	private void rescanCrops()
	{
		CropRegistry.rescan();
		reloadFromTables();
		message = Component.translatable(P + "rescanned", CropRegistry.detected.size()).getString();
		refresh();
	}

	private void cycleMode()
	{
		Row row = selectedRow();
		if (row == null || !row.external()) return;

		AttachedRule rule = attachedEditable.get(row.ruleIndex());
		attachedEditable.set(row.ruleIndex(), rule.withMode(rule.mode().next()));
		markDirty(kind);
		rebuildRows();
		refresh();
	}

	private void toggleEnabled()
	{
		Row row = selectedRow();
		if (row == null) return;

		if (kind == TableKind.ATTACHED)
		{
			if (!row.external()) return;
			AttachedRule rule = attachedEditable.get(row.ruleIndex());
			attachedEditable.set(row.ruleIndex(), rule.withEnabled(!rule.enabled()));
		}
		else
		{
			// 已有外部规则就直接改它（命名空间/标签/正则规则也走这条）；检测行没有规则时才补一条
			int index = row.external() && row.ruleIndex() >= 0 ? row.ruleIndex() : ensureCropRule(row.blockId());
			if (index < 0) return;
			CropRule rule = cropEditable.get(index);

			// 生效 → 停用（只关 enabled）；停用/排除 → 启用（enabled 与 excluded 一起复位，排除过的也能真正重新生效）
			cropEditable.set(index, row.enabled()
					? rule.withEnabled(false)
					: rule.withEnabled(true).withExcluded(false));
		}

		markDirty(kind);
		rebuildRows();
		refresh();
	}

	// flag: 1=需水 2=排除。翻转以行上显示的当前状态为准，规则里的字段与检测结果都能被正确翻转
	private void toggleCropFlag(int flag)
	{
		Row row = selectedRow();
		if (row == null) return;

		int index = row.external() && row.ruleIndex() >= 0 ? row.ruleIndex() : ensureCropRule(row.blockId());
		if (index < 0) return;

		CropRule rule = cropEditable.get(index);
		cropEditable.set(index, flag == 1 ? rule.withNeedsWater(!row.needsWater()) : rule.withExcluded(!row.excluded()));
		markDirty(kind);
		rebuildRows();
		refresh();
	}

	// 已检测作物对应的外部规则下标（仅精确 id 参与合并；通配 / 命名空间 / 标签 / 正则规则单独成行）
	private int cropRuleIndex(String blockId)
	{
		for (int i = 0; i < cropEditable.size(); i++)
		{
			CropRule rule = cropEditable.get(i);
			if (rule.kind() == BlockMatcher.Kind.ID && rule.value().equals(blockId)) return i;
		}
		return -1;
	}

	// 为某个作物方块找到（或补一条）外部规则，返回其下标
	private int ensureCropRule(String blockId)
	{
		if (blockId == null || blockId.isEmpty()) return -1;

		int existing = cropRuleIndex(blockId);
		if (existing >= 0) return existing;

		CropRule draft = CropRegistry.draftRule(blockId, cropEditable.size());
		if (draft == null) return -1;
		cropEditable.add(draft);
		return cropEditable.size() - 1;
	}

	private void removeSelected()
	{
		Row row = selectedRow();
		if (row == null || !row.external()) return;

		if (kind == TableKind.ATTACHED)
		{
			attachedEditable.remove(row.ruleIndex());
		}
		else
		{
			cropEditable.remove(row.ruleIndex());
		}

		selected = -1;
		markDirty(kind);
		rebuildRows();
		refresh();
	}

	private void save()
	{
		boolean ok = true;

		// 只写有改动的那张表，另一张在内存里的编辑不会被覆盖
		if (attachedDirty)
		{
			ok = AttachedBlockTable.saveExternalRules(attachedEditable,
					Component.translatable(P + "fileComment.attached").getString());
			com.wenzai.neosim.schematic.MaterialCalculator.invalidateClassification();
		}
		if (cropDirty)
		{
			ok = CropRegistry.saveExternalRules(cropEditable,
					Component.translatable(P + "fileComment.crops").getString()) && ok;
		}

		reloadFromTables();
		message = Component.translatable(ok ? P + "saved" : P + "saveFailed").getString();
		refresh();
	}

	private void reload(Host host)
	{
		if (kind == TableKind.ATTACHED)
		{
			AttachedBlockTable.reload();
		}
		else
		{
			CropRegistry.rescan();
		}

		reloadFromTables();
		message = Component.translatable(P + "reloaded").getString();
		host.rebuild();
	}

	// ---- 小工具 ----

	// 编辑完内存状态后重建控件：否则按钮文案（停用/启用、行标记、脏标记）不会变
	private void refresh()
	{
		if (host != null) host.rebuild();
	}

	// 总页数：底部翻页按钮与列表上方页码行共用
	private int pageCount()
	{
		return Math.max(1, (rows.size() + pageRows - 1) / pageRows);
	}

	@Nullable
	private Row selectedRow()
	{
		return selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
	}

	private Component filterLabel()
	{
		return Component.translatable(P + "filter." + filter.name().toLowerCase(Locale.ROOT));
	}

	// ---- 静态工具 ----

	// 每页行数：严格按"最后一行按钮的底边不越过底部操作行"来算，矮窗口下宁可少显示几行
	private static int rowsPerPage(int screenHeight)
	{
		int bottomLimit = screenHeight - ACTION_Y_OFFSET - 6;
		return Math.max(1, (bottomLimit - ROW_BTN_H - GRID_TOP) / ROW_H + 1);
	}

	// 精确 id（无通配）才给图标
	@Nullable
	private static String exactId(String value, BlockMatcher.Kind kind)
	{
		return kind == BlockMatcher.Kind.ID && !value.contains("*") && !value.contains("?") ? value : null;
	}

	private static ItemStack iconOf(@Nullable String blockId)
	{
		return blockId == null ? ItemStack.EMPTY : itemOf(blockId);
	}

	private static ItemStack itemOf(String blockId)
	{
		try
		{
			Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
			return new ItemStack(block);
		}
		catch (Throwable t)
		{
			return ItemStack.EMPTY;
		}
	}

	// 行首统一标记：Row.enabled 表示"这一行是否生效"（启用且未排除），据此画 ●/○
	private static Component rowLabel(Row row)
	{
		return Component.literal((row.enabled() ? "● " : "○ ") + row.title());
	}

	// 作物归类：0 农田（可种）/ 1 需水 / 2 非农田（已排除）
	private static int cropGroup(Row row)
	{
		if (row.excluded()) return 2;
		return row.needsWater() ? 1 : 0;
	}

	// 规整排序：按一个整数分组键 + 一个字符串键排出"同类放一起"的下标顺序，index 仍是原列表位置
	private static List<Integer> ruleOrder(int size, ToIntFunction<Integer> group,
			Function<Integer, String> label)
	{
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < size; i++) order.add(i);
		order.sort(Comparator.comparingInt((Integer i) -> group.applyAsInt(i))
				.thenComparing((Integer i) -> label.apply(i), String.CASE_INSENSITIVE_ORDER));
		return order;
	}

	// 内容区右边界（详情面板左侧）
	private static int rightLimit(int screenWidth)
	{
		return screenWidth - screenWidth / DETAIL_DIV - EDGE - 12;
	}

	// 依附方块表筛选按钮宽度 / 左边界（贴列表顶右上角；作物表没有这个按钮）
	private static int filterWidth(int screenWidth)
	{
		return Math.max(66, Math.min(100, rightLimit(screenWidth) / 5));
	}

	private static int filterLeft(int screenWidth)
	{
		return rightLimit(screenWidth) - filterWidth(screenWidth);
	}

	// 按宽度逐字换行绘制，最多 maxLines 行；返回下一行的 y
	private static int drawWrapped(Font font, GuiGraphics gfx, String text, int posX, int posY,
			int maxSizeW, int color, int maxLines)
	{
		String rest = text == null ? "" : text.trim();

		for (int line = 0; line < maxLines && !rest.isEmpty(); line++)
		{
			int cut = rest.length();
			while (cut > 1 && font.width(rest.substring(0, cut)) > maxSizeW) cut--;

			if (line < maxLines - 1)
			{
				// 非最后一行：尽量在分隔符处断开
				int brk = lastBreak(rest.substring(0, cut));
				if (brk > 0) cut = brk;
			}
			else if (cut < rest.length())
			{
				// 最后一行仍放不下：只此一处才截断
				gfx.drawString(font, rest.substring(0, Math.max(1, cut - 1)) + "…", posX, posY, color);
				return posY + font.lineHeight + 2;
			}

			gfx.drawString(font, rest.substring(0, cut), posX, posY, color);
			posY += font.lineHeight + 2;
			rest = rest.substring(cut).trim();
		}

		return posY;
	}

	// 找一个适合断行的位置（空格 / 中点 / 连字符 / 斜杠）
	private static int lastBreak(String s)
	{
		for (int i = s.length() - 1; i > 0; i--)
		{
			char c = s.charAt(i);
			if (c == ' ' || c == '·' || c == '-' || c == '/') return i;
		}
		return -1;
	}
}
