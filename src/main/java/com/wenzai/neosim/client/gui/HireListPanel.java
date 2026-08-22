package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.Config;
import com.wenzai.neosim.network.ServerToClientPayloads.HireListResponsePayload.HireEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

// 工作盒统一雇佣页组件
public class HireListPanel
{
	// 拥有雇佣面板的屏幕需实现此接口，接收服务器列表/雇佣状态
	public interface HostScreen
	{
		// 服务器返回可雇佣列表（entries 可能为空=加载失败或无城市）
		default void onHireList(List<HireEntry> entries)
		{
		}

		// 雇佣/解雇状态变化（boxPos 对应本屏幕岗位时刷新）
		default void onWorkerUpdate(BlockPos boxPos)
		{
		}
	}
	// 每页 12 名：6 行 × 2 列
	private static final int PER_PAGE = 12;
	private static final int ROW_H = 24;
	private static final int GRID_TOP = 52;
	private static final int FILTER_Y = 30;

	// 公共雇佣页翻译键前缀
	private static final String H = "gui.neosim.hire.";

	// GUI 通过该接口向所属 Screen 增删控件（在 GUI 内部用 addRenderableWidget / clearWidgets 实现）
	public interface WidgetHost
	{
		<T extends AbstractWidget> T add(T widget);
		void clear();
	}

	// 一名候选 NPC 的展示快照
	private record NpcEntry(String name, int level, int age, boolean maternity, boolean hiredElsewhere)
	{
		boolean underage()
		{
			return age >= 0 && age < Config.LIFE_ADULT_AGE.get();
		}

		boolean hireable()
		{
			return !hiredElsewhere && !underage() && !maternity;
		}
	}

	// 向所属 Screen 增删控件的宿主
	private final WidgetHost host;
	private final BlockPos boxPos;
	// 语言键前缀（如 "gui.neosim.FarmingBox."）
	private final String langPrefix;
	// 职业类型（0=architect 1=farmer 2=miner 3=courier）
	private final int jobKind;
	// 雇佣动作回调
	private final Consumer<String> hireAction;
	// 返回主页
	private final Runnable goBack;

	// 服务器返回的候选缓存（进入雇佣页时 requestList 异步填充）
	private List<NpcEntry> cachedEntries = List.of();
	private List<NpcEntry> filtered = new java.util.ArrayList<>();
	private String searchText = "";
	private String minLevelText = "";
	private boolean hireableOnly;
	private int page;

	private EditBox searchField;
	private EditBox levelField;

	public HireListPanel(WidgetHost host, BlockPos boxPos, String langPrefix, int jobKind,
			Consumer<String> hireAction, Runnable goBack)
	{
		this.host = host;
		this.boxPos = boxPos;
		this.langPrefix = langPrefix;
		this.jobKind = jobKind;
		this.hireAction = hireAction;
		this.goBack = goBack;
	}

	// 进入雇佣页时调用：请求服务器列表、重置到第 1 页并重建
	public void build()
	{
		requestList();
		page = 0;
		applyFilter();
		rebuild();
	}

	// 请求服务器列表（每次进入雇佣页触发）
	public void requestList()
	{
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new com.wenzai.neosim.network.ClientToServerPayloads.HireListRequestPayload(boxPos, jobKind));
	}

	// 服务器返回列表后缓存（由 HostScreen 转发）
	public void onHireList(List<HireEntry> entries)
	{
		cachedEntries = entries.stream()
				.map(e -> new NpcEntry(e.name(), e.level(), e.age(), e.maternity(), e.hiredElsewhere()))
				.toList();
		applyFilter();
		rebuild();
	}

	// 每帧绘制：标题、页码、置灰原因短标、等级文字
	public void render(GuiGraphics gfx)
	{
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		gfx.drawCenteredString(mc.font, Component.translatable(langPrefix + "hireTitle"), w / 2, 10, 0xFFFFFF);

		if (filtered.isEmpty())
		{
			gfx.drawCenteredString(mc.font, Component.translatable(H + "none"), w / 2, h / 2, 0xAAAAAA);
			return;
		}

		int pages = (filtered.size() + PER_PAGE - 1) / PER_PAGE;
		gfx.drawCenteredString(mc.font,
				Component.translatable(H + "page", page + 1, pages), w / 2, h - 22, 0xFFFFFF);

		int colW = (w - 20) / 2;
		int start = page * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < filtered.size(); i++)
		{
			NpcEntry e = filtered.get(start + i);
			int col = i % 2;
			int row = i / 2;
			int x = 5 + col * (colW + 10);
			int y = GRID_TOP + row * ROW_H;

			// 等级文本紧挨名字按钮右缘（按钮宽 nameW = colW - 96）
			Component level = Component.translatable(H + "level", e.level());
			gfx.drawString(mc.font, level, x + colW - 92, y + 6, 0xCCCCCC);

			// 置灰原因短标跟在等级文本之后（仅不可雇佣时显示）
			if (!e.hireable())
			{
				Component reason = Component.translatable(reasonKey(e));
				gfx.drawString(mc.font, mc.font.plainSubstrByWidth(reason.getString(), 44, true),
						x + colW - 92 + mc.font.width(level) + 4, y + 6, 0x888888);
			}
		}
	}

	// 筛选（名字包含 + 等级 ≥ 最小等级 + 仅可雇佣）并按等级降序、同级按名字排序
	private void applyFilter()
	{
		String q = searchText.trim().toLowerCase();
		int minLevel = 0;
		try
		{
			minLevel = Integer.parseInt(minLevelText);
		}
		catch (NumberFormatException ignored)
		{
		}
		final int ml = minLevel;

		filtered = cachedEntries.stream()
				.filter(e -> q.isEmpty() || e.name().toLowerCase().contains(q))
				.filter(e -> ml <= 0 || e.level() >= ml)
				.filter(e -> !hireableOnly || e.hireable())
				.sorted(Comparator.comparingInt(NpcEntry::level).reversed()
						.thenComparing(NpcEntry::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
		int pages = Math.max(1, (filtered.size() + PER_PAGE - 1) / PER_PAGE);
		if (page >= pages) page = pages - 1;
	}

	// 重建全部控件：筛选栏 + 名字按钮网格 + 翻页 + 返回（仿 BuildingConstructorGui.refreshBlueprintButtons 的全量重建模式）
	private void rebuild()
	{
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		boolean searchFocused = searchField != null && searchField.isFocused();
		boolean levelFocused = levelField != null && levelField.isFocused();

		host.clear();

		// 筛选栏
		searchField = new EditBox(mc.font, 5, FILTER_Y, 140, 20,
				Component.translatable(H + "search"));
		searchField.setHint(Component.translatable(H + "searchHint"));
		searchField.setMaxLength(20);
		searchField.setValue(searchText);
		searchField.setResponder(t ->
		{
			searchText = t;
			page = 0;
			applyFilter();
			rebuild();
		});
		if (searchFocused) searchField.setFocused(true);
		host.add(searchField);

		levelField = new EditBox(mc.font, 150, FILTER_Y, 100, 20,
				Component.translatable(H + "minLevel"));
		levelField.setHint(Component.translatable(H + "minLevelHint"));
		levelField.setMaxLength(2);
		levelField.setValue(minLevelText);
		levelField.setResponder(t ->
		{
			minLevelText = t;
			page = 0;
			applyFilter();
			rebuild();
		});
		if (levelFocused) levelField.setFocused(true);
		host.add(levelField);

		Checkbox cb = Checkbox.builder(Component.translatable(H + "hireableOnly"), mc.font)
				.pos(255, FILTER_Y).selected(hireableOnly)
				.onValueChange((c, v) ->
				{
					hireableOnly = v;
					page = 0;
					applyFilter();
					rebuild();
				})
				.build();
		host.add(cb);

		// 名字按钮网格（两列，每格一名字按钮；置灰时 active=false + tooltip 原因）
		if (!filtered.isEmpty())
		{
			int colW = (w - 20) / 2;
			int nameW = colW - 96;
			int start = page * PER_PAGE;
			for (int i = 0; i < PER_PAGE && start + i < filtered.size(); i++)
			{
				NpcEntry e = filtered.get(start + i);
				int col = i % 2;
				int row = i / 2;
				int x = 5 + col * (colW + 10);
				int y = GRID_TOP + row * ROW_H;

				Button btn = Button.builder(
						Component.literal(mc.font.plainSubstrByWidth(e.name(), nameW - 4, true)),
						b -> hireAction.accept(e.name()))
						.pos(x, y).size(nameW, 20).build();
				btn.active = e.hireable();
				if (!e.hireable())
				{
					btn.setTooltip(Tooltip.create(Component.translatable(reasonKey(e))));
				}
				host.add(btn);
			}
		}

		// 翻页（边界置灰）
		int pages = (filtered.size() + PER_PAGE - 1) / PER_PAGE;
		Button prev = Button.builder(Component.translatable(H + "prev"),
				b ->
				{
					page = Math.max(0, page - 1);
					rebuild();
				})
				.pos(5, h - 24).size(75, 20).build();
		prev.active = page > 0;
		host.add(prev);

		Button next = Button.builder(Component.translatable(H + "next"),
				b ->
				{
					page = Math.min(pages - 1, page + 1);
					rebuild();
				})
				.pos(w - 80, h - 24).size(75, 20).build();
		next.active = page < pages - 1;
		host.add(next);

		// 返回
		host.add(Button.builder(Component.translatable(H + "back"),
				b -> goBack.run()).pos(w / 2 - 50, h - 48).size(100, 20).build());
	}

	private static String reasonKey(NpcEntry e)
	{
		if (e.underage()) return H + "reason.underage";
		if (e.maternity()) return H + "reason.maternity";
		return H + "reason.hired";
	}
}
