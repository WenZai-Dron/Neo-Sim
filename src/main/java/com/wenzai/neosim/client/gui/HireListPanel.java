package com.wenzai.neosim.client.gui;

import com.google.gson.JsonObject;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

// 工作盒统一雇佣页组件：两列网格 + 名字搜索 + 最小等级过滤 + 仅可雇佣开关 + 每页12分页
// 四个工作盒 GUI（FarmingBoxGui / MiningBoxGui / DeliveryBoxGui / BuildingConstructorGui）
// 通过 WidgetHost 接入：组件只负责布局与数据，雇佣动作由各盒回调决定
public class HireListPanel
{
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
		boolean underage() { return age >= 0 && age < Config.LIFE_ADULT_AGE.get(); }
		boolean hireable() { return !hiredElsewhere && !underage() && !maternity; }
	}

	// 向所属 Screen 增删控件的宿主
	private final WidgetHost host;
	private final BlockPos boxPos;
	// 语言键前缀（如 "gui.neosim.FarmingBox."）
	private final String langPrefix;
	// 职业等级字段（farmer / miner / courier / architect）
	private final String jobField;
	// 雇佣动作回调
	private final Consumer<String> hireAction;
	// 返回主页
	private final Runnable goBack;

	private final List<NpcEntry> all = new ArrayList<>();
	private List<NpcEntry> filtered = new ArrayList<>();
	private String searchText = "";
	private String minLevelText = "";
	private boolean hireableOnly;
	private int page;

	private EditBox searchField;
	private EditBox levelField;

	public HireListPanel(WidgetHost host, BlockPos boxPos, String langPrefix, String jobField,
			Consumer<String> hireAction, Runnable goBack)
	{
		this.host = host;
		this.boxPos = boxPos;
		this.langPrefix = langPrefix;
		this.jobField = jobField;
		this.hireAction = hireAction;
		this.goBack = goBack;
	}

	// 进入雇佣页时调用：重读 NPC 名单与档案、重置到第 1 页并重建
	public void build()
	{
		loadNpcs();
		page = 0;
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

	// 从城市 NPC 档案读取名单与职业等级/年龄/产假/是否已被他处雇佣
	private void loadNpcs()
	{
		all.clear();
		String cityName = ModSavedData.getActiveCityName();
		if (cityName.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		String saveName = mc.getSingleplayerServer() != null
				? mc.getSingleplayerServer().getWorldData().getLevelName() : null;
		List<String> npcNames = (saveName != null && !saveName.isEmpty())
				? NpcData.listNpcNames(cityName, saveName)
				: NpcData.listNpcNames(cityName);

		for (String name : npcNames)
		{
			int level = 1;
			int age = -1;
			boolean maternity = false;
			try
			{
				JsonObject json = (saveName != null && !saveName.isEmpty())
						? NpcData.load(name, cityName, saveName)
						: NpcData.load(name, cityName);
				if (json != null)
				{
					if (json.has("job"))
					{
						JsonObject job = json.getAsJsonObject("job");
						if (job.has(jobField)) level = job.get(jobField).getAsInt();
					}
					if (json.has("age")) age = json.get("age").getAsInt();
					if (json.has("pregnancy")) maternity = json.get("pregnancy").getAsFloat() > 0.0F;
				}
			}
			catch (Exception ignored) {}

			boolean hiredElsewhere = com.wenzai.neosim.NeoSim.WORKER_MAP.entrySet().stream()
					.anyMatch(en -> !en.getKey().equals(boxPos) && en.getValue().equals(name));
			all.add(new NpcEntry(name, level, age, maternity, hiredElsewhere));
		}
	}

	// 筛选（名字包含 + 等级 ≥ 最小等级 + 仅可雇佣）并按等级降序、同级按名字排序
	private void applyFilter()
	{
		String q = searchText.trim().toLowerCase();
		int minLevel = 0;
		try { minLevel = Integer.parseInt(minLevelText); } catch (NumberFormatException ignored) {}
		final int ml = minLevel;

		filtered = all.stream()
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
