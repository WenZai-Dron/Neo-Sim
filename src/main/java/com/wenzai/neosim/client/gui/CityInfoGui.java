package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.Config;
import com.wenzai.neosim.block.ControlBoxPersistence;
import com.wenzai.neosim.block.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.block.ControlBoxPersistence.Resident;
import com.wenzai.neosim.client.BuildingNameLocalizer;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.client.gui.config.ConfigPanel;
import com.wenzai.neosim.client.gui.config.ConvertPanel;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

// 城市信息 GUI：全模组唯一入口是按 I 键（再按一次关闭）
// 四个页签：概览（一行一个数据、两列）/ 居民（NPC - 建筑）/ 建筑（4 个类型按钮）/ 配置（5 个分类按钮，宿主给 ConfigPanel）
@OnlyIn(Dist.CLIENT)
public class CityInfoGui extends Screen implements ConfigPanel.Host
{
	private static final String P = "gui.neosim.cityinfo.";

	// 第 5 个页签「转换」不依赖城市数据（纯本地文件操作），渲染时要放在未加入城市的提示之前
	private static final String[] TAB_KEYS =
			{ "tab.overview", "tab.residents", "tab.buildings", "tab.config", "tab.convert" };

	// 建筑页的 4 个类型按钮
	private static final BuildingType[] BUILDING_TYPES =
			{ BuildingType.RESIDENTIAL, BuildingType.COMMERCIAL, BuildingType.INDUSTRIAL, BuildingType.OTHER };

	private static final int TAB_Y = 30;
	private static final int SUB_Y = 52;
	private static final int BODY_TOP = 56;
	private static final int BUILDING_LIST_TOP = 80;
	private static final int LIST_ROW_H = 20;
	private static final int TAB_H = 18;
	private static final int OVERVIEW_REFRESH_TICKS = 20;

	// ---- 状态 ----

	private int tab;
	private int page;
	private BuildingType buildingFilter = BuildingType.RESIDENTIAL;

	private List<ControlBoxRecord> records = List.of();
	private List<String> lines = new ArrayList<>();
	private List<String> homelessNames = List.of();
	private boolean homelessRequested;

	// 概览页每秒刷新一次（含游戏内时间，分钟粒度就够）
	private int overviewTick;

	private final ConfigPanel configPanel = new ConfigPanel();
	private final ConvertPanel convertPanel = new ConvertPanel();

	public CityInfoGui()
	{
		super(Component.translatable(P + "title"));
	}

	@Override
	public boolean isPauseScreen()
	{
		return false;
	}

	@Override
	public void tick()
	{
		super.tick();
		if (tab != 0) return;

		if (++overviewTick >= OVERVIEW_REFRESH_TICKS)
		{
			overviewTick = 0;
			rebuildLines();
		}
	}

	// ---- ConfigPanel.Host（配置页通过这五个方法增删控件 / 取尺寸） ----

	@Override
	public void add(AbstractWidget widget)
	{
		this.addRenderableWidget(widget);
	}

	@Override
	public void rebuild()
	{
		this.rebuildWidgets();
	}

	@Override
	public void focus(AbstractWidget widget)
	{
		this.setFocused(widget);
	}

	@Override
	public int width()
	{
		return this.width;
	}

	@Override
	public int height()
	{
		return this.height;
	}

	@Override
	public Font font()
	{
		return this.font;
	}

	// ---- 生命周期 ----

	@Override
	protected void init()
	{
		clearWidgets();
		records = loadRecords();

		buildTabRow();

		switch (tab)
		{
			case 1 -> buildResidentPage();
			case 2 -> buildBuildingPage();
			case 3 -> configPanel.build(this);
			case 4 -> convertPanel.build(this);
			default -> rebuildLines();
		}

		buildCloseButton();
	}

	private void buildTabRow()
	{
		// 页签从 4 个加到 5 个：下限收窄一点，窄窗口下不会顶到屏幕边
		int tabSizeW = Math.max(52, this.width / 11);
		int tabPosX = this.width / 2 - tabSizeW * TAB_KEYS.length / 2;

		for (int i = 0; i < TAB_KEYS.length; i++)
		{
			final int index = i;
			Button button = Button.builder(Component.translatable(P + TAB_KEYS[i]), b ->
					{
						tab = index;
						page = 0;
						this.rebuildWidgets();
					})
					.pos(tabPosX + i * tabSizeW, TAB_Y)
					.size(tabSizeW - 2, TAB_H)
					.build();
			button.active = i != tab;
			this.addRenderableWidget(button);
		}
	}

	private void buildCloseButton()
	{
		// 配置页/转换页底部两行按钮占满左侧，关闭按钮贴右下角
		// 预留宽度由 ConfigPanel.CLOSE_RESERVE 统一给出：面板排版时已经把这块钱扣掉，两边永远不会叠
		boolean config = tab == 3 || tab == 4;
		int closeSizeW = config ? ConfigPanel.CLOSE_RESERVE - 12 : 100;
		int closeSizeH = config ? 16 : 20;
		int closePosX = config ? this.width - ConfigPanel.CLOSE_RESERVE : this.width / 2 - 50;
		int closePosY = config ? this.height - 22 : this.height - 30;

		this.addRenderableWidget(Button.builder(Component.translatable(P + "close"), b -> this.onClose())
				.pos(closePosX, closePosY)
				.size(closeSizeW, closeSizeH)
				.build());
	}

	// 居民页：列表 + 向服务端要一次无家名单
	private void buildResidentPage()
	{
		rebuildLines();
		if (!homelessRequested)
		{
			homelessRequested = true;
			PacketDistributor.sendToServer(new ClientToServerPayloads.HomelessListRequestPayload());
		}
		addPager();
	}

	// 建筑页：4 个类型按钮（选中的置灰）+ 按类型过滤的列表
	private void buildBuildingPage()
	{
		int btnSizeW = Math.max(72, this.width / 8);
		int btnPosX = this.width / 2 - btnSizeW * BUILDING_TYPES.length / 2;

		for (int i = 0; i < BUILDING_TYPES.length; i++)
		{
			final BuildingType type = BUILDING_TYPES[i];
			Button button = Button.builder(
					Component.translatable(P + "type.count", typeLabel(type), countOfType(type)), b ->
					{
						buildingFilter = type;
						page = 0;
						rebuildLines();
						this.rebuildWidgets();
					})
					.pos(btnPosX + i * btnSizeW, SUB_Y)
					.size(btnSizeW - 2, TAB_H)
					.build();
			button.active = type != buildingFilter;
			this.addRenderableWidget(button);
		}

		rebuildLines();
		addPager();
	}

	private void addPager()
	{
		int perPage = perPage();
		int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
		int pagerPosY = this.height - 30;

		Button prev = Button.builder(Component.translatable(P + "prevPage"), b ->
				{
					if (page > 0) page--;
					this.rebuildWidgets();
				})
				.pos(this.width / 2 - 110, pagerPosY).size(60, 20).build();
		prev.active = page > 0;
		this.addRenderableWidget(prev);

		Button next = Button.builder(Component.translatable(P + "nextPage"), b ->
				{
					if (page < pages - 1) page++;
					this.rebuildWidgets();
				})
				.pos(this.width / 2 + 50, pagerPosY).size(60, 20).build();
		next.active = page < pages - 1;
		this.addRenderableWidget(next);
	}

	// 无家名单（服务端响应）
	public void applyHomelessList(List<String> names)
	{
		homelessNames = names != null ? names : List.of();
		if (this.minecraft != null)
		{
			rebuildLines();
			this.rebuildWidgets();
		}
	}

	// ---- 数据 ----

	private List<ControlBoxRecord> loadRecords()
	{
		String cityName = ClientDataHolder.getInstance().getCityName();
		if (cityName.isEmpty()) return List.of();
		return ControlBoxPersistence.loadClient(saveName(), cityName);
	}

	private String saveName()
	{
		Minecraft mc = Minecraft.getInstance();
		return mc.getSingleplayerServer() != null
				? mc.getSingleplayerServer().getWorldData().getLevelName() : null;
	}

	// 列表起始高度与每页行数（建筑页顶部多一行类型按钮）
	private int listTop()
	{
		return tab == 2 ? BUILDING_LIST_TOP : BODY_TOP;
	}

	private int perPage()
	{
		return Math.max(4, (this.height - 62 - listTop()) / LIST_ROW_H);
	}

	// 概览 / 居民 / 建筑 三页的行内容
	private void rebuildLines()
	{
		lines = new ArrayList<>();
		switch (tab)
		{
			case 0 -> addOverviewLines();
			case 1 -> addResidentLines();
			case 2 -> addBuildingLines();
			default ->
			{
			}
		}
	}

	// 概览页：一行一个数据，全部来自 HUD 的同一份数据源
	private void addOverviewLines()
	{
		ClientDataHolder data = ClientDataHolder.getInstance();

		lines.add(Component.translatable(P + "overview.city", data.getCityName()).getString());
		lines.add(Component.translatable(P + "overview.time", HudInfo.gameTime(Minecraft.getInstance())).getString());
		lines.add(Component.translatable(P + "overview.weekday", HudInfo.weekday(data.getDayOfWeek())).getString());
		lines.add(Component.translatable(P + "overview.day", data.getDay()).getString());
		lines.add(Component.translatable(P + "overview.population", data.getPopulation()).getString());
		lines.add(Component.translatable(P + "overview.credit", HudInfo.amount(data.getCredit())).getString());
		lines.add(Component.translatable(P + "overview.mode", HudInfo.modeName(data.getMode())).getString());
		lines.add(Component.translatable(P + "overview.populationMax", Config.MAX_POPULATION.get()).getString());
		lines.add(Component.translatable(P + "overview.buildings", records.size()).getString());
		lines.add(Component.translatable(P + "overview.homeless", homelessNames.size()).getString());
	}

	// 居民页：NPC - 建筑名（无家市民显示 NPC - 无家）
	private void addResidentLines()
	{
		String homelessTag = Component.translatable(P + "resident.homelessTag").getString();

		for (ControlBoxRecord record : records)
		{
			String building = BuildingNameLocalizer.localize(record.schematicName());
			for (Resident resident : record.residents())
			{
				lines.add(Component.translatable(P + "resident.row", resident.name(), building).getString());
			}
		}

		for (String name : homelessNames)
		{
			lines.add(Component.translatable(P + "resident.row", name, homelessTag).getString());
		}

		if (lines.isEmpty())
		{
			lines.add(Component.translatable(P + "resident.none").getString());
		}
	}

	// 建筑页：只列当前类型；「建筑 - NPC」，多个用「、」分隔，没有住户显示「空余」
	private void addBuildingLines()
	{
		String vacant = Component.translatable(P + "building.vacant").getString();

		for (ControlBoxRecord record : records)
		{
			if (effectiveType(record) != buildingFilter) continue;

			String building = BuildingNameLocalizer.localize(record.schematicName());
			String occupants = record.residents().isEmpty()
					? vacant
					: record.residents().stream().map(Resident::name).collect(Collectors.joining("、"));
			lines.add(Component.translatable(P + "building.row", building, occupants).getString());
		}

		if (lines.isEmpty())
		{
			lines.add(Component.translatable(P + "building.none").getString());
		}
	}

	// 建筑的有效类型：CUSTOM + 控制箱 视为住宅，其余 CUSTOM 归入其他
	private BuildingType effectiveType(ControlBoxRecord record)
	{
		var schematic = SchematicRegistry.getInstance().get(record.schematicName());
		if (schematic == null) return BuildingType.OTHER;
		if (schematic.isResidential()) return BuildingType.RESIDENTIAL;

		BuildingType type = schematic.getType();
		return type == BuildingType.CUSTOM ? BuildingType.OTHER : type;
	}

	private int countOfType(BuildingType type)
	{
		int count = 0;
		for (ControlBoxRecord record : records)
		{
			if (effectiveType(record) == type) count++;
		}
		return count;
	}

	private Component typeLabel(BuildingType type)
	{
		return Component.translatable(P + "type." + type.name().toLowerCase(Locale.ROOT));
	}

	private int countResidents()
	{
		int count = 0;
		for (ControlBoxRecord record : records) count += record.residents().size();
		return count;
	}

	// ---- 渲染 ----

	@Override
	public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick)
	{
		super.render(gfx, mouseX, mouseY, partialTick);

		gfx.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

		// 配置页 / 转换页整屏交给面板（转换页与城市数据无关，所以排在下面的未加入城市提示之前）
		if (tab == 3)
		{
			configPanel.render(gfx, mouseX, mouseY, partialTick);
			return;
		}

		if (tab == 4)
		{
			convertPanel.render(gfx, mouseX, mouseY, partialTick);
			return;
		}

		// 未加入城市：概览 / 居民 / 建筑 三页只给提示
		if (ClientDataHolder.getInstance().getCityName().isEmpty())
		{
			gfx.drawCenteredString(this.font, Component.translatable(P + "noCity").getString(),
					this.width / 2, BODY_TOP + 20, 0xAAAAAA);
			return;
		}

		if (tab == 0)
		{
			drawOverview(gfx);
		}
		else
		{
			drawPagedList(gfx);
		}
	}

	// 概览页：一行一个数据，排成两列（左列前半、右列后半），不翻页
	private void drawOverview(GuiGraphics gfx)
	{
		if (lines.isEmpty()) return;

		int perColumn = (lines.size() + 1) / 2;
		int colSizeW = Math.max(140, this.width / 2 - 30);

		for (int i = 0; i < lines.size(); i++)
		{
			gfx.drawString(this.font, lines.get(i),
					20 + (i / perColumn) * (colSizeW + 10),
					listTop() + (i % perColumn) * LIST_ROW_H, 0xFFFFFF);
		}
	}

	// 居民 / 建筑页：页码 + 逐行列表（居民页的无家市民用橙色区分）
	private void drawPagedList(GuiGraphics gfx)
	{
		int perPage = perPage();
		int start = page * perPage;
		int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);

		gfx.drawString(this.font, Component.translatable(P + "pageInfo", page + 1, pages, lines.size()).getString(),
				20, listTop() - 14, 0xAAAAAA);

		int linePosY = listTop();
		for (int i = start; i < Math.min(start + perPage, lines.size()); i++)
		{
			int color = tab == 1 && i >= countResidents() ? 0xFFAA00 : 0xFFFFFF;
			gfx.drawString(this.font, lines.get(i), 20, linePosY, color);
			linePosY += LIST_ROW_H;
		}
	}
}
