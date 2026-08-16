package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.block.TerraformEngine;
import com.wenzai.neosim.block.TerraformPlan;
import com.wenzai.neosim.client.BuildingNameLocalizer;
import com.wenzai.neosim.client.preview.SchematicPreviewManager;
import com.wenzai.neosim.client.render.MarkerBeamRenderer;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.MaterialCalculator;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Comparator;
import java.util.List;

public class BuildingConstructorGui extends Screen
{
	private static final int ROW_H = 84;
	private static final int COLS = 3;
	private static final int ROWS = 2;
	private static final int PER_PAGE = COLS * ROWS;

	private static final String P = "gui.neosim.BuildingConstructor.";

	// 单条缺少材料记录
	private record MissingEntry(net.minecraft.world.item.Item item, int missing) {}

	private int currentPage = 0;
	private int previousPage = 0;

	private BuildingType selectedType = null;
	private SchematicData selectedBuilding = null;

	private int buildingOffset = 0;
	private int buildingsOnPage = 0;

	// 材料需求分页
	private int materialOffset = 0;
	private static final int MAT_COLS = 2;

	// 当前缺少材料页
	private List<MissingEntry> missingMaterials = List.of();
	private int missingOffset = 0;
	private boolean missingScanPending;

	// 选择规划页状态
	private TerraformPlan selectedPlan = null;

	private final BlockPos constructorPos;
	private static final java.util.Map<BlockPos, String> WORKER_MAP = com.wenzai.neosim.NeoSim.WORKER_MAP;
	private final HireListPanel hirePanel;

	// 缓存的已选蓝图
	private static final java.util.Map<BlockPos, String> SELECTED_BUILDING = new java.util.concurrent.ConcurrentHashMap<>();

	public static String getWorkerAt(BlockPos pos) { return WORKER_MAP.get(pos); }
	public static void clearWorkerAt(BlockPos pos) { WORKER_MAP.remove(pos); }
	public static void clearSelectedAt(BlockPos pos) { SELECTED_BUILDING.remove(pos); }
	private String assignedWorker = null;
	private com.wenzai.neosim.building.ConstructionTask activeTask = null;
	private EditBox searchField;
	private List<SchematicData> currentBlueprints = List.of();

	// 格式筛选
	private com.wenzai.neosim.schematic.SchematicFormat selectedFormat;
	private int formatCycleIndex = 0;

	// 搜索模式：false=按建筑名搜索，true=按作者搜索
	private boolean searchByAuthor;

	// 建筑3D预览
	private static final long HOVER_THROTTLE_MS = 120;
	private final java.util.Map<Button, SchematicData> hoverMap = new java.util.HashMap<>();
	private float previewYaw = -30.0F;
	private float previewPitch = 18.0F;
	private float previewZoom = 1.0F;
	private boolean draggingPreview;
	private Button lastHoveredButton;
	private long hoverStartTime;

	// 排序
	private static final int SORT_DIMS = 3;
	private static final String[] SORT_ASC_KEYS = {
			P + "sortNameAsc", P + "sortCostAsc", P + "sortAuthorAsc"
	};
	private static final String[] SORT_DESC_KEYS = {
			P + "sortNameDesc", P + "sortCostDesc", P + "sortAuthorDesc"
	};
	private int sortIndex = 0;
	private boolean sortAscending = true;

	public BuildingConstructorGui(BlockPos constructorPos)
	{
		super(Component.translatable(P + "title"));
		this.constructorPos = constructorPos;
		this.assignedWorker = WORKER_MAP.get(constructorPos);

		// 恢复跨会话的已选蓝图
		String selected = SELECTED_BUILDING.get(constructorPos);
		if (selected != null)
		{
			selectedBuilding = SchematicRegistry.getInstance().get(selected);
		}

		this.hirePanel = new HireListPanel(new HireListPanel.WidgetHost()
		{
			@Override
			public <T extends AbstractWidget> T add(T widget) { return addRenderableWidget(widget); }
			@Override
			public void clear() { clearWidgets(); }
		}, constructorPos, P, "architect", name ->
		{
			WORKER_MAP.put(constructorPos, name);
			assignedWorker = name;
			assignNpcToSite(name);
			currentPage = 0;
			showPage();
		}, () ->
		{
			currentPage = 0;
			showPage();
		});
	}

	// 从预览返回到需求页
	public BuildingConstructorGui(BlockPos constructorPos, SchematicData building)
	{
		this(constructorPos);
		this.selectedBuilding = building;
		this.materialOffset = 0;
		this.currentPage = 6;
		this.previousPage = 2;
	}

	// 生命周期
	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	protected void init() { showPage(); }

	@Override
	public void render(GuiGraphics gfx, int mx, int my, float pt)
	{
		renderBackground(gfx, mx, my, pt);
		super.render(gfx, mx, my, pt);
		drawHeader(gfx);
		if (currentPage == 7) hirePanel.render(gfx);
		drawBuildingPreview(gfx, mx, my);
		if (currentPage == 11) drawTerraformPage(gfx);
	}

	// 建筑3D预览交互
	@Override
	public boolean mouseClicked(double mx, double my, int button)
	{
		if (super.mouseClicked(mx, my, button)) return true;
		if (button == 0 && isInPreviewPanel(mx, my))
		{
			draggingPreview = true;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy)
	{
		if (draggingPreview && button == 0)
		{
			previewYaw += (float) dx * 0.6F;
			previewPitch -= (float) dy * 0.6F;
			previewPitch = Math.max(-89.0F, Math.min(89.0F, previewPitch));
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button)
	{
		if (draggingPreview && button == 0)
		{
			draggingPreview = false;
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy)
	{
		if (isInPreviewPanel(mx, my) && dy != 0)
		{
			previewZoom *= (dy > 0) ? 1.1F : 0.9F;
			previewZoom = Math.max(0.4F, Math.min(4.0F, previewZoom));
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	// 建筑3D预览绘制
	private void drawBuildingPreview(GuiGraphics gfx, int mx, int my)
	{
		if (currentPage == 6)
		{
			drawPreviewPanel(gfx);
		}
		else if (currentPage >= 2 && currentPage <= 5 || currentPage == 9)
		{
			drawHoverPreview(gfx, mx, my);
		}
	}

	// 需求页右侧预览面板矩形
	private int previewPanelX() { return width / 2 + 6; }
	private int previewPanelY() { return 55; }
	private int previewPanelW() { return width / 2 - 14; }
	private int previewPanelH() { return height - 110; }

	private boolean isInPreviewPanel(double mx, double my)
	{
		if (currentPage != 6 || selectedBuilding == null) return false;
		int px = previewPanelX(), py = previewPanelY(), pw = previewPanelW(), ph = previewPanelH();
		return mx >= px && mx <= px + pw && my >= py && my <= py + ph;
	}

	// 需求页右侧3D预览面板
	private void drawPreviewPanel(GuiGraphics gfx)
	{
		if (selectedBuilding == null) return;
		int px = previewPanelX(), py = previewPanelY(), pw = previewPanelW(), ph = previewPanelH();

		gfx.fill(px, py, px + pw, py + ph, 0x90000000);
		gfx.drawString(font, BuildingNameLocalizer.localize(selectedBuilding.getName()),
				px + 4, py + 6, 0xFFFFFF);

		int cxp = px + pw / 2;
		int cyp = py + ph / 2 + 10;
		int size = Math.max(40, Math.min(pw, ph) - 24);
		BuildingPreviewRenderer.render(gfx, selectedBuilding, cxp, cyp,
				(int) (size * previewZoom), previewYaw, previewPitch);
	}

	// 蓝图列表页：悬停按钮显示3D预览（120ms防抖，切走即停）
	private void drawHoverPreview(GuiGraphics gfx, int mx, int my)
	{
		Button hovered = getHoveredBlueprintButton(mx, my);
		if (hovered == null)
		{
			lastHoveredButton = null;
			return;
		}
		long now = System.currentTimeMillis();
		if (hovered != lastHoveredButton)
		{
			lastHoveredButton = hovered;
			hoverStartTime = now;
		}
		if (now - hoverStartTime < HOVER_THROTTLE_MS) return;

		SchematicData data = hoverMap.get(hovered);
		if (data == null) return;

		int size = 44;
		int py = 8;

		int px = width - size - 16;

		gfx.fill(px - 4, py - 4, px + size + 4, py + size + 4, 0x90000000);
		BuildingPreviewRenderer.render(gfx, data, px + size / 2, py + size / 2,
				size, previewYaw, previewPitch);
	}

	private Button getHoveredBlueprintButton(int mx, int my)
	{
		for (Button b : hoverMap.keySet())
		{
			if (mx >= b.getX() && mx <= b.getX() + b.getWidth()
					&& my >= b.getY() && my <= b.getY() + b.getHeight())
			{
				return b;
			}
		}
		return null;
	}

	@Override
	public boolean charTyped(char c, int m)
	{
		if (searchField != null && searchField.isFocused())
		{
			return searchField.charTyped(c, m);
		}
		return super.charTyped(c, m);
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods)
	{
		if (searchField != null && searchField.isFocused())
		{
			if (searchField.keyPressed(key, scan, mods))
			{
				return true;
			}
		}
		if (key == 256)
		{
			if (currentPage == 0)
			{
				onClose();
			}
			else if (currentPage == 1)
			{
				currentPage = 0;
				showPage();
			}
			else if (currentPage >= 2 && currentPage <= 5)
			{
				currentPage = 1;
				showPage();
			}
			else if (currentPage == 6)
			{
				currentPage = previousPage;
				showPage();
			}
			else if (currentPage == 8)
			{
				currentPage = 0;
				showPage();
			}
			else if (currentPage == 9)
			{
				currentPage = 1;
				showPage();
			}
			else if (currentPage == 10)
			{
				currentPage = 0;
				showPage();
			}
			else if (currentPage == 11)
			{
				currentPage = 0;
				showPage();
			}
			else if (currentPage == 7)
			{
				currentPage = 0;
				showPage();
			}
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	// 首页
	private void drawHeader(GuiGraphics gfx)
	{
		if (currentPage != 7)
		{
			gfx.drawCenteredString(font, Component.translatable(P + "title"), width / 2, 10, 0xFFFFFF);
			gfx.drawCenteredString(font, Component.translatable(P + "statusReady"), width / 2, 22, 0xAAFFFF);
		}

		switch (currentPage)
		{
			case 0 -> gfx.drawCenteredString(font, Component.translatable(P + "page0.hint"),
					width / 2, 100, 0xFFFFAA);
			case 1 -> gfx.drawCenteredString(font, Component.translatable(P + "page1.hint"),
					width / 2, 80, 0xFFFFAA);
			case 2 -> gfx.drawCenteredString(font, Component.translatable(P + "page2.hint"),
					width / 2, 45, 0xFFFFAA);
			case 3 -> gfx.drawCenteredString(font, Component.translatable(P + "page3.hint"),
					width / 2, 45, 0xFFFFAA);
			case 4 -> gfx.drawCenteredString(font, Component.translatable(P + "page4.hint"),
					width / 2, 45, 0xFFFFAA);
			case 5 -> gfx.drawCenteredString(font, Component.translatable(P + "page5.hint"),
					width / 2, 45, 0xFFFFAA);
			case 6 ->
			{
				if (selectedBuilding != null)
				{
					gfx.drawString(font,
							Component.translatable(P + "page6.hint",
									BuildingNameLocalizer.localize(selectedBuilding.getName())),
							10, 45, 0xFFFFAA);
					drawRequirements(gfx);
				}
			}
			case 8 -> gfx.drawCenteredString(font, Component.translatable(P + "page8.hint"),
					width / 2, 45, 0xFFFFAA);
			case 9 -> gfx.drawCenteredString(font, Component.translatable(P + "page9.hint"),
					width / 2, 45, 0xFFFFAA);
			case 10 ->
			{
				SchematicData data = currentBuildingData();
				if (data != null)
				{
					gfx.drawString(font,
							Component.translatable(P + "page10.hint",
									BuildingNameLocalizer.localize(data.getName())),
							10, 45, 0xFFFFAA);
				}
				drawMissingMaterials(gfx);
			}
			case 11 -> gfx.drawCenteredString(font, Component.translatable(P + "page11.hint"),
					width / 2, 45, 0xFFFFAA);
		}

		if (currentPage == 8)
		{
			drawStatus(gfx);
		}
	}

	private void showPage()
	{
		clearWidgets();

		switch (currentPage)
		{
			case 0 -> showMainMenu();
			case 1 -> showTypeSelection();
			case 2 -> showBlueprintList(BuildingType.RESIDENTIAL);
			case 3 -> showBlueprintList(BuildingType.COMMERCIAL);
			case 4 -> showBlueprintList(BuildingType.INDUSTRIAL);
			case 5 -> showBlueprintList(BuildingType.OTHER);
			case 6 -> showRequirementsPage();
			case 7 -> hirePanel.build();
			case 8 -> showStatusPage();
			case 9 -> showBlueprintList(BuildingType.CUSTOM);
			case 10 -> showMissingMaterialsPage();
			case 11 -> showTerraformPage();
		}
	}

	private void showMainMenu()
	{
		// 按控制盒坐标查找模盒的建造任务
		if (activeTask == null)
		{
			activeTask = com.wenzai.neosim.building.ConstructionEngine.findTask(constructorPos);
		}

		// 检查任务是否完成
		if (activeTask != null && activeTask.getState() == com.wenzai.neosim.building.BuildingInstance.BuildState.COMPLETE)
		{
			activeTask = null;
		}

		int btnW = width / 4;
		int btnH = height / 13;
		int cx = width / 2;
		int row1Y = height * 5 / 8;
		int row2Y = row1Y + btnH;

		Component hireLabel = assignedWorker != null
				? Component.translatable(P + "fireWorker", assignedWorker)
				: Component.translatable(P + "hireBuilder");
		addButton(1, cx - width * 3 / 8, row1Y, btnW, btnH,
				hireLabel,
				b ->
				{
					if (assignedWorker != null)
					{
						releaseNpcFromSite(assignedWorker);
						WORKER_MAP.remove(constructorPos);
						assignedWorker = null;
						showPage();
					}
					else
					{
						currentPage = 7;
						showPage();
					}
				});
		addButton(2, cx - width / 8, row1Y, btnW, btnH,
				Component.translatable(P + "chooseBuilding"),
				b ->
				{
					currentPage = 1;
					showPage();
				});
		Button statusBtn = addButton(3, cx + width / 8, row1Y, btnW, btnH,
				Component.translatable(P + "currentStatus"), b ->
				{
					currentPage = 8;
					showPage();
				});
		statusBtn.active = selectedBuilding != null || assignedWorker != null || activeTask != null;

		boolean hasTask = activeTask != null && activeTask.getState() != com.wenzai.neosim.building.BuildingInstance.BuildState.COMPLETE;
		Component cpLabel = !hasTask ? Component.translatable(P + "pause") : (activeTask.isPaused() ? Component.translatable(P + "continue") : Component.translatable(P + "pause"));
		Button cpBtn = addButton(4, cx - width * 3 / 8, row2Y, btnW, btnH,
				cpLabel,
				b ->
				{
					if (activeTask != null)
					{
						if (activeTask.isPaused()) activeTask.resume();
						else activeTask.pause();
						showPage();
					}
				});
		cpBtn.active = hasTask;
		Button planBtn = addButton(5, cx - width / 8, row2Y, btnW, btnH,
				Component.translatable(P + "choosePlan"),
				b ->
				{
					currentPage = 11;
					showPage();
				});
		planBtn.active = !hasTask;
		Button missingBtn = addButton(6, cx + width / 8, row2Y, btnW, btnH,
				Component.translatable(P + "currentMissing"),
				b ->
				{
					currentPage = 10;
					showPage();
				});
		missingBtn.active = currentBuildingData() != null;
	}

	private void showTypeSelection()
	{
		int btnW = width * 5 / 24;
		int btnH = height / 13;
		int cx = width / 2;
		int y = height * 5 / 8;

		addButton(7, cx - width * 5 / 12, y, btnW, btnH,
				Component.translatable(P + "typeResidential"),
				b ->
				{
					currentPage = 2;
					selectedType = BuildingType.RESIDENTIAL;
					showPage();
				});
		addButton(8, cx - width * 5 / 24, y, btnW, btnH,
				Component.translatable(P + "typeCommercial"),
				b ->
				{
					currentPage = 3;
					selectedType = BuildingType.COMMERCIAL;
					showPage();
				});
		addButton(9, cx, y, btnW, btnH,
				Component.translatable(P + "typeIndustrial"),
				b ->
				{
					currentPage = 4;
					selectedType = BuildingType.INDUSTRIAL;
					showPage();
				});
		addButton(10, cx + width * 5 / 24, y, btnW, btnH,
				Component.translatable(P + "typeOther"),
				b ->
				{
					currentPage = 5;
					selectedType = BuildingType.OTHER;
					showPage();
				});

		addButton(11, cx - btnW / 2, y + btnH + 6, btnW, btnH,
				Component.translatable(P + "typeCustom"),
				b ->
				{
					currentPage = 9;
					selectedType = BuildingType.CUSTOM;
					showPage();
				});
	}

	private void showBlueprintList(BuildingType type)
	{
		// 切换建筑类型时重置翻页
		if (selectedType != type) buildingOffset = 0;
		selectedType = type;

		// 搜索模式切换按钮
		addButton(604, width / 2 - 137, height - 40, 60, 20,
				Component.translatable(P + (searchByAuthor ? "searchModeAuthor" : "searchModeBuilding")),
				b -> { searchByAuthor = !searchByAuthor; refreshBlueprintButtons(); });

		// 排序按钮
		addSortButton();

		// 翻页/返回后不丢失已有搜索词
		String existing = searchField != null ? searchField.getValue() : "";
		searchField = new EditBox(font, width / 2 - 75, height - 40, 150, 20,
				Component.translatable(P + "search"));
		searchField.setMaxLength(20);
		searchField.setValue(existing);
		searchField.setFocused(true);

		searchField.setResponder(text -> refreshBlueprintButtons());
		addRenderableWidget(searchField);

		buildBlueprintButtons();

		buildFormatButtons();
	}

	private void buildFormatButtons()
	{
		int fy = height - 40;
		int fx = width / 2 + 80;
		int bw = 70;

		// 切换格式筛选
		addButton(600, fx, fy, bw, 20,
				Component.translatable(P + (formatCycleIndex == 0 ? "filterAll"
						: formatCycleIndex == 1 ? "filterTxt" : "filterLitematic")),
				b ->
				{
					formatCycleIndex = (formatCycleIndex + 1) % 3;
					selectedFormat = switch (formatCycleIndex)
					{
						case 1 -> com.wenzai.neosim.schematic.SchematicFormat.SIM_UKRAFT_TXT;
						case 2 -> com.wenzai.neosim.schematic.SchematicFormat.LITEMATICA;
						default -> null;
					};
					showPage();
				});
	}

	private void refreshBlueprintButtons()
	{
		String text = searchField != null ? searchField.getValue() : "";
		boolean focused = searchField != null && searchField.isFocused();
		buildingOffset = 0;

		clearWidgets();
		addButton(604, width / 2 - 137, height - 40, 60, 20,
				Component.translatable(P + (searchByAuthor ? "searchModeAuthor" : "searchModeBuilding")),
				b -> { searchByAuthor = !searchByAuthor; refreshBlueprintButtons(); });
		searchField = new EditBox(font, width / 2 - 75, height - 40, 150, 20,
				Component.translatable(P + "search"));
		searchField.setMaxLength(20);

		searchField.setValue(text);
		searchField.setFocused(focused);
		searchField.setResponder(t -> refreshBlueprintButtons());
		addRenderableWidget(searchField);

		addSortButton();

		buildBlueprintButtons();

		buildFormatButtons();
	}

	// 排序按钮
	private void addSortButton()
	{
		addButton(605, 5, 40, 70, 20,
				Component.translatable(sortAscending ? SORT_ASC_KEYS[sortIndex] : SORT_DESC_KEYS[sortIndex]),
				b -> cycleSort());
	}

	// 点击
	private void cycleSort()
	{
		if (sortAscending)
		{
			sortAscending = false;
		}
		else
		{
			sortAscending = true;
			sortIndex = (sortIndex + 1) % SORT_DIMS;
		}
		buildingOffset = 0;
		showPage();
	}

	private void buildBlueprintButtons()
	{
		hoverMap.clear();
		String query = searchField != null ? searchField.getValue().trim() : "";
		SchematicRegistry reg = SchematicRegistry.getInstance();

		// 自定义页打开时先重扫目录，玩家新增/删除蓝图无需重启
		if (selectedType == BuildingType.CUSTOM)
		{
			reg.refreshCustom();
		}
		currentBlueprints = reg.getByType(selectedType);

		if (!query.isEmpty())
		{
			String lower = query.toLowerCase();
			// 按搜索模式过滤：建筑=名称（英文名或本地化中文名都匹配），作者=作者
			currentBlueprints = currentBlueprints.stream()
					.filter(d -> searchByAuthor
							? d.getAuthor().toLowerCase().contains(lower)
							: (d.getName().toLowerCase().contains(lower)
								|| BuildingNameLocalizer.localize(d.getName()).toLowerCase().contains(lower)))
					.toList();
		}

		// 格式筛选
		if (selectedFormat != null)
		{
			currentBlueprints = currentBlueprints.stream()
					.filter(d -> d.getFormat() == selectedFormat)
					.toList();
		}

		// 排序名称
		Comparator<SchematicData> cmp = switch (sortIndex)
		{
			case 1 -> Comparator.comparingDouble(d ->
					d.getTotalSolidBlocks() * com.wenzai.neosim.Config.CREDITS_PER_BLOCK.get());
			case 2 -> Comparator.comparing(SchematicData::getAuthor, String.CASE_INSENSITIVE_ORDER);
			default -> Comparator.comparing(d -> BuildingNameLocalizer.localize(d.getName()),
					String.CASE_INSENSITIVE_ORDER);
		};
		if (!sortAscending) cmp = cmp.reversed();
		List<SchematicData> sorted = new java.util.ArrayList<>(currentBlueprints);
		sorted.sort(cmp);
		currentBlueprints = sorted;

		int colW = (width - 20) / COLS;

		int x = 5, y = 60, idx = 1;
		int perRow = 0;
		buildingsOnPage = 0;

		for (int i = buildingOffset; i < currentBlueprints.size() && buildingsOnPage < PER_PAGE; i++)
		{
			SchematicData data = currentBlueprints.get(i);
			String author = data.hasKnownAuthor() ? data.getAuthor()
					: Component.translatable(P + "unknownAuthor").getString();

			double cost = data.getTotalSolidBlocks() * com.wenzai.neosim.Config.CREDITS_PER_BLOCK.get();
			Button bpBtn = addButton(idx, x, y, colW, 20,
					Component.literal(BuildingNameLocalizer.localize(data.getName())),
					b -> onBlueprintPicked(data));

			// 悬停预览映射
			hoverMap.put(bpBtn, data);

			// 资金不足时禁用蓝图选择
			bpBtn.active = canAfford(cost);
			addButton(idx + 200, x, y + 19, colW, 14,
					Component.literal(data.getDimensionString()), null).active = false;

			addButton(idx + 250, x, y + 32, colW, 14,
					Component.translatable(P + "cost", String.format("%.2f", cost)), null).active = false;
			addButton(idx + 300, x, y + 45, colW, 14,
					Component.translatable(P + "blocks", data.getTotalSolidBlocks()), null).active = false;
			addButton(idx + 400, x, y + 58, colW, 14,
					Component.literal(author), null).active = false;

			x += colW;
			idx++;
			buildingsOnPage++;
			perRow++;

			if (perRow >= COLS)
			{
				x = 5;
				y += ROW_H;
				perRow = 0;
			}
		}

		// 自定义页：在最后一个蓝图按钮之后的空位添加"添加"按钮
		if (selectedType == BuildingType.CUSTOM && buildingsOnPage < PER_PAGE)
		{
			addButton(700, x, y, colW, 20,
					Component.translatable(P + "add"),
					b -> openCustomFolder());
		}

		if (buildingOffset > 0)
		{
			addButton(500, 5, height - 20, 75, 20,
					Component.translatable(P + "prevPage"),
					b ->
					{
						buildingOffset = Math.max(0, buildingOffset - PER_PAGE);
						showPage();
					});
		}
		if (buildingOffset + buildingsOnPage < currentBlueprints.size())
		{
			addButton(501, width - 80, height - 20, 75, 20,
					Component.translatable(P + "nextPage"),
					b ->
					{
						buildingOffset += buildingsOnPage;
						showPage();
					});
		}
	}

	private void onBlueprintPicked(SchematicData data)
	{
		// 新建筑重置预览视角，每次进入需求页视角一致
		previewYaw = -30.0F;
		previewPitch = 18.0F;
		previewZoom = 1.0F;
		previousPage = currentPage;
		selectedBuilding = data;
		SELECTED_BUILDING.put(constructorPos, data.getName());
		materialOffset = 0;
		currentPage = 6;
		showPage();
	}

	// 打开自定义蓝图目录
	private void openCustomFolder()
	{
		try
		{
			java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
					.resolve("NeoSim").resolve("Buildings");
			if (!java.nio.file.Files.exists(dir))
			{
				java.nio.file.Files.createDirectories(dir);
			}

			switch (net.minecraft.Util.getPlatform())
			{
				case WINDOWS ->
						Runtime.getRuntime().exec(new String[]{"explorer.exe", dir.toString()});
				case OSX ->
						Runtime.getRuntime().exec(new String[]{"open", dir.toString()});
				case LINUX, SOLARIS, UNKNOWN ->
						Runtime.getRuntime().exec(new String[]{"xdg-open", dir.toString()});
			}
		}
		catch (Exception e)
		{
			com.mojang.logging.LogUtils.getLogger().error(
					"NeoSim-GUI: failed to open custom buildings folder — {}", e.getMessage());
		}
	}

	// 资金是否足够
	private boolean canAfford(double cost)
	{
		if (com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode() == 2)
		{
			return true;
		}
		return com.wenzai.neosim.client.ClientDataHolder.getInstance().getCredit() >= cost;
	}

	private void showRequirementsPage()
	{
		if (selectedBuilding == null) return;
		addButton(1001, width / 2 - 100, height - 25, 100, 20,
				Component.translatable(P + "goBack"),
				b ->
				{
					currentPage = previousPage;
					showPage();
				});
		addButton(1000, width / 2, height - 25, 100, 20,
				Component.translatable(P + "preview"),
				b -> onPreview());

		// 预览面板顶部：重置视角按钮
		addButton(1002, previewPanelX() + previewPanelW() - 62, previewPanelY() + 4, 58, 18,
				Component.translatable(P + "resetView"),
				b ->
				{
					previewYaw = -30.0F;
					previewPitch = 18.0F;
					previewZoom = 1.0F;
				});

		// 材料类型超过一页时显示上一页/下一页
		List<MaterialCalculator.MaterialEntry> materials = MaterialCalculator.calculate(selectedBuilding,
				com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode());
		int perPage = materialRowsPerPage() * MAT_COLS;
		if (perPage < materials.size())
		{
			int maxOffset = Math.max(0, ((materials.size() - 1) / perPage) * perPage);
			materialOffset = Math.max(0, Math.min(materialOffset, maxOffset));

			if (materialOffset > 0)
			{
				addButton(1200, 5, height - 25, 75, 20,
						Component.translatable(P + "prevPage"),
						b ->
						{
							materialOffset = Math.max(0, materialOffset - perPage);
							showPage();
						});
			}
			if (materialOffset + perPage < materials.size())
			{
				addButton(1201, width - 80, height - 25, 75, 20,
						Component.translatable(P + "nextPage"),
						b ->
						{
							materialOffset += perPage;
							showPage();
						});
			}
		}
	}

	private void showStatusPage()
	{
		addButton(1100, width / 2 - 50, height - 25, 100, 20,
				Component.translatable(P + "goBack"),
				b ->
				{
					currentPage = 0;
					showPage();
				});
	}

	// 选择规划页：自动取最近地块 → 选方案 → 确认
	private void showTerraformPage()
	{
		clearWidgets();
		addButton(1500, width / 2 - 100, height - 25, 100, 20,
				Component.translatable(P + "goBack"),
				b ->
				{
					currentPage = 0;
					showPage();
				});

		// 未雇佣建筑师：只画提示（drawTerraformPage），不提供跳转按钮
		if (WORKER_MAP.get(constructorPos) == null)
		{
			return;
		}

		// 方案列表（不显示地块相关内容；无地块时确认会给出聊天提示）
		int y = 60;
		for (TerraformPlan plan : TerraformPlan.values())
		{
			addButton(1600 + plan.ordinal(), 10, y, 190, 20,
					Component.translatable(plan.labelKey()),
					b ->
					{
						selectedPlan = plan;
						showTerraformPage();
					});
			y += 24;
		}

		// 确认（与返回并排，避免覆盖）
		if (selectedPlan != null)
		{
			addButton(1700, width / 2, height - 25, 100, 20,
					Component.translatable(P + "confirmTerraform"),
					b -> confirmTerraform());
		}
	}

	// 确认整地：自动使用与模盒相邻标记构成的地块；单机直调 / 联机发包
	private void confirmTerraform()
	{
		if (selectedPlan == null) return;
		java.util.List<BlockPos> corners = adjacentRect();
		if (corners == null || corners.size() != 4)
		{
			if (minecraft != null && minecraft.player != null)
			{
				minecraft.player.displayClientMessage(
						Component.literal("§c请先用标记棒圈出矩形地块，且标记需与模盒相连"), false);
			}
			return;
		}
		int[] b = rectBounds(corners);
		int minX = b[0], minZ = b[1], maxX = b[2], maxZ = b[3], baselineY = b[4];

		if (minecraft != null && minecraft.hasSingleplayerServer())
		{
			ServerLevel level = minecraft.getSingleplayerServer().overworld();
			String city = com.wenzai.neosim.storage.ModSavedData.getActiveCityName();
			String err = TerraformEngine.start(level, city, constructorPos, selectedPlan,
					minX, minZ, maxX, maxZ, baselineY);
			if (err != null)
			{
				if (minecraft.player != null)
				{
					minecraft.player.displayClientMessage(Component.literal(err), false);
				}
			}
			else
			{
				selectedPlan = null;
				currentPage = 0;
				showPage();
			}
		}
		else if (minecraft != null)
		{
			net.neoforged.neoforge.network.PacketDistributor.sendToServer(
					new com.wenzai.neosim.network.ClientToServerPayloads.TerraformStartPayload(
							constructorPos, selectedPlan.name(),
							minX, minZ, maxX, maxZ, baselineY));
			selectedPlan = null;
			currentPage = 0;
			showPage();
		}
	}

	// 当前维度活动矩形（与光幕同源）
	private java.util.List<java.util.List<BlockPos>> rectsInDimension()
	{
		if (minecraft == null || minecraft.level == null) return List.of();
		if (MarkerBeamRenderer.getCachedDim() == null
				|| !MarkerBeamRenderer.getCachedDim().equals(minecraft.level.dimension()))
		{
			return List.of();
		}
		return MarkerBeamRenderer.getCachedRects();
	}

	// 与模盒相邻标记构成的地块四角（无则 null；同农业/矿业盒规则）
	private java.util.List<BlockPos> adjacentRect()
	{
		java.util.List<java.util.List<BlockPos>> rects = rectsInDimension();
		BlockPos[] neighbors = {
				constructorPos.above(), constructorPos.below(),
				constructorPos.north(), constructorPos.south(),
				constructorPos.east(), constructorPos.west()
		};
		for (BlockPos n : neighbors)
		{
			for (java.util.List<BlockPos> corners : rects)
			{
				if (corners.size() == 4 && corners.contains(n)) return corners;
			}
		}
		return null;
	}

	// 矩形四角 → {minX, minZ, maxX, maxZ, baselineY}
	private int[] rectBounds(java.util.List<BlockPos> corners)
	{
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : corners)
		{
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		return new int[] { minX, minZ, maxX, maxZ, corners.get(0).getY() };
	}

	// 选择规划页绘制：方案说明/费率（画在方案按钮右侧，不重叠）
	private void drawTerraformPage(GuiGraphics gfx)
	{
		if (WORKER_MAP.get(constructorPos) == null)
		{
			gfx.drawString(font, Component.translatable(P + "noArchitect"), 10, 80, 0xFFAAAA);
			return;
		}
		if (selectedPlan != null)
		{
			gfx.drawString(font, Component.translatable(selectedPlan.descKey()), 210, 80, 0xCCCCCC);
			gfx.drawString(font,
					Component.translatable(P + "terraformRate", String.format("%.2f",
							com.wenzai.neosim.Config.TERRAFORM_CREDIT_PER_BLOCK.get())),
					210, 94, 0xFFFFAA);
		}
	}

	// 当前缺少材料页
	private void showMissingMaterialsPage()
	{
		// 重新查找任务，确保拿到控制箱坐标（扫箱子范围与服务端取料一致）
		activeTask = com.wenzai.neosim.building.ConstructionEngine.findTask(constructorPos);
		buildMissingPageButtons();
		requestMissingScan();
	}

	// 重建页面按钮（进入页面、扫描完成、翻页时调用；不重新触发扫描）
	private void rebuildMissingPageButtons()
	{
		clearWidgets();
		buildMissingPageButtons();
	}

	private void buildMissingPageButtons()
	{
		addButton(1300, width / 2 - 100, height - 25, 100, 20,
				Component.translatable(P + "goBack"),
				b ->
				{
					currentPage = 0;
					showPage();
				});
		addButton(1301, width / 2, height - 25, 100, 20,
				Component.translatable(P + "refresh"),
				b -> showPage());

		// 缺少材料过多时分页
		int perPage = materialRowsPerPage() * MAT_COLS;
		if (perPage < missingMaterials.size())
		{
			int maxOffset = Math.max(0, ((missingMaterials.size() - 1) / perPage) * perPage);
			missingOffset = Math.max(0, Math.min(missingOffset, maxOffset));

			if (missingOffset > 0)
			{
				addButton(1302, 5, height - 25, 75, 20,
						Component.translatable(P + "prevPage"),
						b ->
						{
							missingOffset = Math.max(0, missingOffset - perPage);
							rebuildMissingPageButtons();
						});
			}
			if (missingOffset + perPage < missingMaterials.size())
			{
				addButton(1303, width - 80, height - 25, 75, 20,
						Component.translatable(P + "nextPage"),
						b ->
						{
							missingOffset += perPage;
							rebuildMissingPageButtons();
						});
			}
		}
	}

	// 当前页面要展示的建筑（已选蓝图优先，其次当前任务建筑）
	private SchematicData currentBuildingData()
	{
		if (selectedBuilding != null) return selectedBuilding;
		if (activeTask != null && activeTask.getBuilding() != null)
		{
			return activeTask.getBuilding().getSchematic();
		}
		return null;
	}

	// 请求重算缺少材料：在服务端线程扫描箱子并统计（客户端线程不可直接读服务端方块实体）
	private void requestMissingScan()
	{
		missingScanPending = true;
		missingMaterials = List.of();
		missingOffset = 0;

		SchematicData data = currentBuildingData();
		if (minecraft == null || !minecraft.hasSingleplayerServer() || data == null)
		{
			missingScanPending = false;
			return;
		}
		final byte mode = com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode();
		final String buildingName = data.getName();
		final net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
		server.execute(() ->
		{
			try
			{
				net.minecraft.server.level.ServerLevel level = server.overworld();
				List<net.minecraft.world.level.block.entity.ChestBlockEntity> chests = findChestsOnServer(level);
				List<MissingEntry> result = new java.util.ArrayList<>();
				SchematicData sd = SchematicRegistry.getInstance().get(buildingName);
				if (sd != null)
				{
					for (MaterialCalculator.MaterialEntry e : MaterialCalculator.calculate(sd, mode))
					{
						int have = com.wenzai.neosim.building.InventoryManager.countItems(chests, e.item);
						int missing = e.count - have;
						if (missing > 0)
						{
							result.add(new MissingEntry(e.item, missing));
						}
					}
					result.sort((a, b) -> Integer.compare(b.missing, a.missing));
				}
				final List<MissingEntry> out = result;
				minecraft.execute(() ->
				{
					missingMaterials = out;
					missingScanPending = false;

					// 只重建按钮（含分页），不再重新触发扫描，避免扫描循环
					if (currentPage == 10) rebuildMissingPageButtons();
				});
			}
			catch (Exception ex)
			{
				com.mojang.logging.LogUtils.getLogger().warn(
						"NeoSim-GUI: missing-material scan failed — {}", ex.toString());
				minecraft.execute(() -> missingScanPending = false);
			}
		});
	}

	// 在服务端线程扫描模盒旁（及任务控制箱旁）的箱子，范围与服务端建造取料一致
	private List<net.minecraft.world.level.block.entity.ChestBlockEntity> findChestsOnServer(
			net.minecraft.server.level.ServerLevel level)
	{
		List<net.minecraft.world.level.block.entity.ChestBlockEntity> chests = new java.util.ArrayList<>(
				com.wenzai.neosim.building.InventoryManager.findNearbyChests(level, constructorPos));
		com.wenzai.neosim.building.ConstructionTask task =
				com.wenzai.neosim.building.ConstructionEngine.findTask(constructorPos);
		if (task != null && task.getBuilding() != null)
		{
			BlockPos cp = task.getBuilding().getControlBoxPos();
			if (cp != null && !cp.equals(constructorPos))
			{
				for (net.minecraft.world.level.block.entity.ChestBlockEntity chest :
						com.wenzai.neosim.building.InventoryManager.findNearbyChests(level, cp))
				{
					if (!chests.contains(chest)) chests.add(chest);
				}
			}
		}
		return chests;
	}

	// 当前状态页绘制
	private void drawStatus(GuiGraphics gfx)
	{
		int x = 10;
		int y = 60;

		// 目标建筑
		gfx.drawString(font, Component.translatable(P + "statusBuilding"),
				x, y, 0xFFFFFF);
		y += 14;
		if (selectedBuilding != null)
		{
			gfx.drawString(font, BuildingNameLocalizer.localize(selectedBuilding.getName()),
					x + 20, y, 0xCCCCCC);
		}
		else if (activeTask != null)
		{
			// 任务存在时显示任务建筑
			gfx.drawString(font, activeTask.getBuilding().getSchematicName(), x + 20, y, 0xCCCCCC);
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
		}
		y += 24;

		// 所选建筑师
		gfx.drawString(font, Component.translatable(P + "statusBuilder"),
				x, y, 0xFFFFFF);
		y += 14;
		String worker = assignedWorker != null ? assignedWorker
				: (activeTask != null && activeTask.getBuilding().getBuilderName() != null
				? activeTask.getBuilding().getBuilderName()
				: null);
		if (worker != null)
		{
			gfx.drawString(font, worker, x + 20, y, 0xCCCCCC);
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
		}
		y += 24;

		// 当前建造状态
		gfx.drawString(font, Component.translatable(P + "statusState"),
				x, y, 0xFFFFFF);
		y += 14;
		if (activeTask != null)
		{
			String stateName = switch (activeTask.getState())
			{
				case IDLE -> "statusState.idle";
				case WAITING_FOR_WORKER -> "statusState.waitingWorker";
				case WORKER_ASSIGNED -> "statusState.workerAssigned";
				case LOADING_BLUEPRINT -> "statusState.loading";
				case WAITING_FOR_RESOURCES -> "statusState.waiting";
				case BUILDING -> "statusState.building";
				case COMPLETE -> "statusState.complete";
			};
			gfx.drawString(font, Component.translatable(P + stateName), x + 20, y, 0xCCCCCC);
			y += 14;

			// 建造进度
			int progress = activeTask.getProgress();
			int total = activeTask.getTotal();
			gfx.drawString(font, Component.translatable(P + "statusProgress", progress, total),
					x + 20, y, 0xCCCCCC);
			y += 24;
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
			y += 24;
		}

		// 在状态显示服务端缓存的缺料
		gfx.drawString(font, Component.translatable(P + "statusMaterials"),
				x, y, 0xFFFFFF);
		y += 14;
		if (activeTask != null
				&& activeTask.getState() == com.wenzai.neosim.building.BuildingInstance.BuildState.WAITING_FOR_RESOURCES)
		{
			net.minecraft.world.item.Item missing = activeTask.getLastMissingMaterial();
			if (missing != null)
			{
				gfx.drawString(font, missing.getDescription(), x + 20, y, 0xCCCCCC);
			}
			else
			{
				gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
			}
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
		}
	}

	// 材料列表每页行数
	private int materialRowsPerPage()
	{
		int available = Math.max(40, height - 116 - 30);
		return Math.max(4, available / 13);
	}

	private void drawRequirements(GuiGraphics gfx)
	{
		SchematicData data = selectedBuilding;
		if (data == null) return;

		int x = 10;
		int y = 60;

		// 左上角信息块
		double cost = data.getTotalSolidBlocks() * com.wenzai.neosim.Config.CREDITS_PER_BLOCK.get();
		gfx.drawString(font, Component.translatable(P + "dimensions", data.getDimensionString()),
				x, y, 0xFFFFFF);
		y += 14;
		gfx.drawString(font, Component.translatable(P + "cost", String.format("%.2f", cost)),
				x, y, 0xFFFFFF);
		y += 14;
		gfx.drawString(font, Component.translatable(P + "totalBlocks", data.getTotalSolidBlocks()),
				x, y, 0xFFFFFF);
		y += 14;

		// 材料需求
		List<MaterialCalculator.MaterialEntry> materials = MaterialCalculator.calculate(data,
				com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode());
		if (materials.isEmpty())
		{
			String key = com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode() == 2
					? P + "noMaterialsCreative" : P + "noMaterials";
			gfx.drawString(font, Component.translatable(key), x, y, 0xAAAAAA);
			return;
		}

		// 把偏移钳制到合法页边界，避免建筑切换后残留
		int perPage = materialRowsPerPage() * MAT_COLS;
		int maxOffset = Math.max(0, ((materials.size() - 1) / perPage) * perPage);
		materialOffset = Math.max(0, Math.min(materialOffset, maxOffset));

		int page = materialOffset / perPage + 1;
		int pages = (materials.size() + perPage - 1) / perPage;
		Component countLine = Component.translatable(P + "materials", materials.size());
		if (pages > 1)
		{
			countLine = countLine.copy().append("   " + page + "/" + pages);
		}
		gfx.drawString(font, countLine, x, y, 0xFFFFFF);
		y += 14;

		int gap = 24;

		int colW = (width / 2 - 40 - gap * (MAT_COLS - 1)) / MAT_COLS;

		// 材料列表
		float S = 0.8F;
		int sx = Math.round(x / S);
		int sy = Math.round(y / S);
		int sColW = Math.round(colW / S);
		int sGap = Math.round(52 / S);
		int sRow = Math.round(11 / S);

		gfx.pose().pushPose();
		gfx.pose().scale(S, S, 1.0F);
		for (int i = 0; i < perPage && materialOffset + i < materials.size(); i++)
		{
			MaterialCalculator.MaterialEntry e = materials.get(materialOffset + i);
			int cx = sx + (i % MAT_COLS) * (sColW + sGap);
			int cy = sy + (i / MAT_COLS) * sRow;

			// 右侧数量右对齐；左侧名称与数量保持间距，超宽截断避免重叠
			String count = e.formatted();
			int countX = cx + sColW - font.width(count);
			int maxNameW = Math.max(8, countX - cx - 4);
			gfx.drawString(font, font.plainSubstrByWidth(e.item.getDescription().getString(), maxNameW, true),
					cx, cy, 0xCCCCCC);
			gfx.drawString(font, count, countX, cy, 0xCCCCCC);
		}
		gfx.pose().popPose();

	}

	// 当前缺少材料列表绘制
	private void drawMissingMaterials(GuiGraphics gfx)
	{
		SchematicData data = currentBuildingData();
		if (data == null)
		{
			gfx.drawCenteredString(font, Component.translatable(P + "missingNone"),
					width / 2, 60, 0xAAAAAA);
			return;
		}

		int x = 10;
		int y = 60;

		// 扫描中
		if (missingScanPending)
		{
			gfx.drawString(font, Component.translatable(P + "scanning"), x, y, 0xAAAAAA);
			return;
		}

		// 创造模式：全部方块免费
		if (com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode() == 2)
		{
			gfx.drawString(font, Component.translatable(P + "noMaterialsCreative"), x, y, 0xAAAAAA);
			return;
		}
		if (missingMaterials.isEmpty())
		{
			gfx.drawString(font, Component.translatable(P + "missingNone"), x, y, 0xAAAAAA);
			return;
		}

		// 把偏移钳制到合法页边界
		int perPage = materialRowsPerPage() * MAT_COLS;
		int maxOffset = Math.max(0, ((missingMaterials.size() - 1) / perPage) * perPage);
		missingOffset = Math.max(0, Math.min(missingOffset, maxOffset));

		int page = missingOffset / perPage + 1;
		int pages = (missingMaterials.size() + perPage - 1) / perPage;
		Component countLine = Component.translatable(P + "missingCount", missingMaterials.size());
		if (pages > 1)
		{
			countLine = countLine.copy().append("   " + page + "/" + pages);
		}
		gfx.drawString(font, countLine, x, y, 0xFFFFFF);
		y += 14;

		int gap = 24;
		int colW = (width / 2 - 40 - gap * (MAT_COLS - 1)) / MAT_COLS;

		float S = 0.8F;
		int sx = Math.round(x / S);
		int sy = Math.round(y / S);
		int sColW = Math.round(colW / S);
		int sGap = Math.round(52 / S);
		int sRow = Math.round(11 / S);

		gfx.pose().pushPose();
		gfx.pose().scale(S, S, 1.0F);
		for (int i = 0; i < perPage && missingOffset + i < missingMaterials.size(); i++)
		{
			MissingEntry e = missingMaterials.get(missingOffset + i);
			int cx = sx + (i % MAT_COLS) * (sColW + sGap);
			int cy = sy + (i / MAT_COLS) * sRow;

			// 右侧：只显示该材料总共缺少的数量
			Component line = Component.translatable(P + "missingLine", e.missing());
			int lineX = cx + sColW - font.width(line);

			// 左侧材料名：与缺料数字保持间距，超宽截断避免重叠
			String name = e.item().getDescription().getString();
			int maxNameW = Math.max(8, lineX - cx - 4);
			gfx.drawString(font, font.plainSubstrByWidth(name, maxNameW, true), cx, cy, 0xCCCCCC);
			gfx.drawString(font, line, lineX, cy, 0xCCCCCC);
		}
		gfx.pose().popPose();
	}

	private void onPreview()
	{
		if (selectedBuilding != null)
		{
			SchematicPreviewManager.getInstance().enterPreview(selectedBuilding, constructorPos);
			if (minecraft != null)
			{
				minecraft.setScreen(new PreviewAdjustGui(selectedBuilding, constructorPos));
			}
		}
	}

	// 解雇NPC，恢复AI
	private void releaseNpcFromSite(String npcName)
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		net.minecraft.server.level.ServerLevel level = minecraft.getSingleplayerServer().overworld();
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof com.wenzai.neosim.npc.Entity npc && npcName.equals(npc.getNpcName()))
			{
				npc.releaseFromSite();
				break;
			}
		}
		// 解雇后立即保存
		com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(level);
	}

	// 找到指定NPC实体并传送
	private void assignNpcToSite(String npcName)
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		net.minecraft.server.level.ServerLevel level = minecraft.getSingleplayerServer().overworld();
		int count = 0;
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof com.wenzai.neosim.npc.Entity npc)
			{
				count++;
				if (npcName.equals(npc.getNpcName()))
				{
					// 服务端：未成年不可雇佣
					if (!npc.isAdult())
					{
						com.mojang.logging.LogUtils.getLogger().warn(
								"NeoSim-GUI: NPC '{}' is underage (age={}), hire refused",
								npcName, npc.getAge());
						return;
					}

					// 服务端：产假中不可雇佣
					if (npc.getPregnancyStage() > 0.0F)
					{
						com.mojang.logging.LogUtils.getLogger().warn(
								"NeoSim-GUI: NPC '{}' is on maternity leave, hire refused",
								npcName);
						return;
					}
					npc.assignToSite(constructorPos);

					// 雇佣后立即保存
					com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(level);
					return;
				}
			}
		}
		// 未加载的NPC：在服务端线程从档案恢复并直接生成在模盒上方（GUI线程不可直接改服务端世界）
		net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
		if (server != null)
		{
			com.mojang.logging.LogUtils.getLogger().info(
					"NeoSim-GUI: NPC '{}' not loaded, scheduling restore at site", npcName);
			server.execute(() ->
			{
				net.minecraft.server.level.ServerLevel serverLevel = server.overworld();
				String cityName = com.wenzai.neosim.storage.ModSavedData.getActiveCityName();
				if (cityName.isEmpty())
				{
					// 保底：恢复失败时清掉雇佣记录，任务回到等待工人，避免永久卡住
					com.wenzai.neosim.NeoSim.WORKER_MAP.remove(constructorPos);
					com.mojang.logging.LogUtils.getLogger().warn(
							"NeoSim-GUI: hire '{}' cancelled — no active city", npcName);
					return;
				}

				com.google.gson.JsonObject json =
						com.wenzai.neosim.storage.NpcData.load(serverLevel, cityName, npcName);
				if (json == null)
				{
					// 保底：档案不存在（可能刚死亡被删档），清掉雇佣记录
					com.wenzai.neosim.NeoSim.WORKER_MAP.remove(constructorPos);
					com.mojang.logging.LogUtils.getLogger().warn(
							"NeoSim-GUI: hire '{}' cancelled — npc file missing", npcName);
					return;
				}

				// 档案校验：未成年不可雇佣
				if (json.has("age")
						&& json.get("age").getAsInt() < com.wenzai.neosim.Config.LIFE_ADULT_AGE.get())
				{
					com.wenzai.neosim.NeoSim.WORKER_MAP.remove(constructorPos);
					com.mojang.logging.LogUtils.getLogger().warn(
							"NeoSim-GUI: NPC '{}' is underage (age={}), hire refused",
							npcName, json.get("age").getAsInt());
					return;
				}

				// 档案校验：产假中不可雇佣
				if (json.has("pregnancy") && json.get("pregnancy").getAsFloat() > 0.0F)
				{
					com.wenzai.neosim.NeoSim.WORKER_MAP.remove(constructorPos);
					com.mojang.logging.LogUtils.getLogger().warn(
							"NeoSim-GUI: NPC '{}' is on maternity leave, hire refused",
							npcName);
					return;
				}

				com.wenzai.neosim.npc.Entity npc = com.wenzai.neosim.npc.Manage.spawnSingle(
						serverLevel, cityName, npcName, constructorPos);
				if (npc != null)
				{
					// 已生成在模盒上方，直接分配上工
					npc.assignToSite(constructorPos);

					// 雇佣后立即保存
					com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(serverLevel);
				}
				else
				{
					// 保底：实体创建失败，清掉雇佣记录
					com.wenzai.neosim.NeoSim.WORKER_MAP.remove(constructorPos);
					com.mojang.logging.LogUtils.getLogger().warn(
							"NeoSim-GUI: hire '{}' cancelled — failed to spawn NPC", npcName);
				}
			});
			return;
		}
		com.mojang.logging.LogUtils.getLogger().info("NeoSim-GUI: NPC '{}' not found among {} entities", npcName, count);
	}

	private void onBuildIt()
	{
		onClose();
	}

	private Button addButton(int id, int x, int y, int w, int h, Component label, Button.OnPress action)
	{
		Button btn = Button.builder(label, action != null ? action : b -> { })
				.pos(x, y).size(w, h).build();
		return addRenderableWidget(btn);
	}

	@Override
	public void onClose()
	{
		// 释放预览VBO显存
		BuildingPreviewRenderer.release();
		if (minecraft != null)
		{
			minecraft.setScreen(null);
			minecraft.mouseHandler.grabMouse();
		}
	}
}
