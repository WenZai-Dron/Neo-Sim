package com.wenzai.neosim.client.gui;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.FarmTask;
import com.wenzai.neosim.block.PlotTask;
import com.wenzai.neosim.block.WorkBoxPersistence;
import com.wenzai.neosim.block.WorkPlotEngine;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

public class FarmingBoxGui extends Screen
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String P = "gui.neosim.FarmingBox.";

	private static final java.util.Map<BlockPos, String> WORKER_MAP = com.wenzai.neosim.NeoSim.WORKER_MAP;

	private final HireListPanel hirePanel;

	private final BlockPos boxPos;
	private WorkBoxPersistence.WorkBoxRecord record;
	private PlotTask task;
	private int currentPage = 0;
	private java.util.Set<FarmTask.FarmType> selectedCrops;
	private java.util.Set<FarmTask.LivestockType> selectedLivestock;
	private java.util.Set<FarmTask.TreeType> selectedTrees;

	public FarmingBoxGui(BlockPos boxPos)
	{
		super(Component.translatable(P + "title"));
		this.boxPos = boxPos;
		reload();

		initTargetSelection();

		this.hirePanel = new HireListPanel(new HireListPanel.WidgetHost()
		{
			@Override
			public <T extends AbstractWidget> T add(T widget) { return addRenderableWidget(widget); }
			@Override
			public void clear() { clearWidgets(); }
		}, boxPos, P, "farmer", this::hire, () ->
		{
			currentPage = 0;
			showPage();
		});
	}

	// 客户端读取盒记录
	public static WorkBoxPersistence.WorkBoxRecord loadRecord(BlockPos pos)
	{
		String cityName = ModSavedData.getActiveCityName();
		if (cityName.isEmpty()) return null;
		Minecraft mc = Minecraft.getInstance();
		String saveName = mc.getSingleplayerServer() != null
				? mc.getSingleplayerServer().getWorldData().getLevelName() : null;
		return WorkBoxPersistence.findRecord(saveName, cityName, pos);
	}

	public static boolean hasRecord(BlockPos pos)
	{
		return loadRecord(pos) != null;
	}

	// 读服务端任务，服务器只记录
	private void reload()
	{
		this.record = loadRecord(boxPos);
		refreshTask();
	}

	private void refreshTask()
	{
		Minecraft mc = Minecraft.getInstance();
		this.task = (mc != null && mc.hasSingleplayerServer())
				? WorkPlotEngine.findTask(boxPos) : null;
	}

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	protected void init() { showPage(); }

	@Override
	public void render(GuiGraphics gfx, int mx, int my, float pt)
	{
		renderBackground(gfx, mx, my, pt);
		super.render(gfx, mx, my, pt);
		if (currentPage == 0) { drawMain(gfx); drawCropButtonIcons(gfx); }
		else if (currentPage == 1) hirePanel.render(gfx);
		else { drawCropList(gfx); drawCropIcons(gfx); }
	}

	private void showPage()
	{
		clearWidgets();
		int cx = width / 2;

		if (currentPage == 0)
		{
			addButton(1, cx - 100, height - 30, 100, 20,
					Component.translatable(P + "close"), b -> onClose());

			boolean hasWorker = task != null && !task.getWorkerName().isEmpty();
			addButton(2, cx, height - 30, 100, 20,
					hasWorker
							? Component.translatable(P + "fire", task.getWorkerName())
							: Component.translatable(P + "hire"),
					b ->
					{
						if (hasWorker) fireWorker();
						else { currentPage = 1; showPage(); }
					});

			addButton(3, cx - 100, height - 56, 200, 20,
					Component.translatable(P + "target"),
					b ->
					{
						currentPage = 2;
						showPage();
					});

			addButton(4, cx - 100, height - 80, 200, 20,
					task != null && task.isPaused()
							? Component.translatable(P + "resume")
							: Component.translatable(P + "pause"),
					b ->
					{
						if (task != null)
						{
							task.setPaused(!task.isPaused());
							reload();
							showPage();
						}
					});
		}
		else if (currentPage == 2)
		{
			addButton(999, cx - 150, height - 25, 100, 20,
					Component.translatable(P + "goBack"),
					b ->
					{
						currentPage = 0;
						showPage();
					});
			addButton(998, cx - 50, height - 25, 100, 20,
					Component.translatable(P + "apply"),
					b -> applyCrops());

			// 目标页：种植业/畜牧业/林业 三列等间距（-210 / -40 / +130，整体偏左）
			int left = cx - 210;
			int right = cx - 40;
			int y = 80;
			for (FarmTask.FarmType t : new FarmTask.FarmType[] {
					FarmTask.FarmType.WHEAT, FarmTask.FarmType.CARROT, FarmTask.FarmType.POTATO })
			{
				addCropCheckbox(left, y, t);
				y += 24;
			}
			y = 80;
			for (FarmTask.LivestockType t : FarmTask.LivestockType.values())
			{
				addLivestockCheckbox(right, y, t);
				y += 24;
			}

			// 林业列
			int forest = cx + 130;
			y = 80;
			for (FarmTask.TreeType t : FarmTask.TreeType.values())
			{
				addTreeCheckbox(forest, y, t);
				y += 24;
			}
		}
		else
		{
			// 雇佣页：统一 HireListPanel（含筛选栏、两列网格、分页、返回）
			hirePanel.build();
		}
	}

	private void drawMain(GuiGraphics gfx)
	{
		gfx.drawCenteredString(font, Component.translatable(P + "title"), width / 2, 10, 0xFFFFFF);

		if (record == null)
		{
			gfx.drawCenteredString(font, Component.translatable(P + "noRecord"), width / 2, 60, 0xAAAAAA);
			return;
		}

		int x = width / 2 - 120;
		int y = 45;

		// 绑定矩形
		if (record.bound())
		{
			int w = record.rx2() - record.rx1() + 1;
			int h = record.rz2() - record.rz1() + 1;
			w = w > 2 ? w - 2 : w;
			h = h > 2 ? h - 2 : h;
			gfx.drawString(font, Component.translatable(P + "rect", w, h), x, y, 0xFFFFFF);
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "unbound"), x, y, 0xFFAA55);
		}
		y += 18;

		// 显示牲畜
		if (isLivestockMode())
		{
			gfx.drawString(font, Component.translatable(P + "livestock"), x, y, 0xFFFFFF);
			y += 18;
		}

		// 林业
		if (isForestryMode())
		{
			gfx.drawString(font, Component.translatable(P + "forestry", forestrySummary()), x, y, 0xFFFFFF);
			y += 18;
		}

		// 农夫
		String worker = record.worker() != null ? record.worker() : "";
		if (!worker.isEmpty())
		{
			gfx.drawString(font, Component.translatable(P + "worker", worker), x, y, 0xFFFFFF);
			y += 14;
			gfx.drawString(font, Component.translatable(P + "workerLevel", workerLevel()), x + 12, y, 0xCCCCCC);
			y += 18;
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "workerNone"), x, y, 0xAAAAAA);
			y += 18;
		}

		// 状态
		gfx.drawString(font, Component.translatable(P + "state",
				stateLabel()), x, y, 0xFFFFFF);
	}

	// 主页图标（作物产物 + 牲畜产物 + 原木）；>7 个时缩小至 10px 分 2 排
	private void drawCropButtonIcons(GuiGraphics gfx)
	{
		int bx = width / 2 - 100;
		int by = height - 56;
		List<ItemStack> icons = selectedHomeIcons();
		int total = icons.size();
		int right = bx + 200 - 4;

		if (total <= 7)
		{
			int pitch = 12;
			int iy = by + (20 - 16) / 2;
			int ix = right - total * pitch;
			for (ItemStack icon : icons)
			{
				gfx.renderItem(icon, ix, iy, 0);
				ix += pitch;
			}
			return;
		}

		// >7：缩小至 10px、分 2 排右对齐
		int small = 10;
		int gap = 1;
		int row1 = (total + 1) / 2;
		int row2 = total - row1;
		int rowHeight = small + gap + small;
		int y0 = by + (20 - rowHeight) / 2;
		drawHomeIconRow(gfx, icons.subList(0, row1), right, y0, small);
		drawHomeIconRow(gfx, icons.subList(row1, total), right, y0 + small + gap, small);
	}

	// 单排小图标（pose 缩放渲染）
	private void drawHomeIconRow(GuiGraphics gfx, List<ItemStack> icons, int right, int y, int small)
	{
		int pitch = small + 1;
		int ix = right - ((icons.size() - 1) * pitch + small);
		for (ItemStack icon : icons)
		{
			gfx.pose().pushPose();
			gfx.pose().translate(ix, y, 0.0D);
			gfx.pose().scale(small / 16.0F, small / 16.0F, 1.0F);
			gfx.renderItem(icon, 0, 0, 0);
			gfx.pose().popPose();
			ix += pitch;
		}
	}

	// 首页图标列表：作物产物 → 牲畜产物 → 原木
	private List<ItemStack> selectedHomeIcons()
	{
		List<ItemStack> out = new java.util.ArrayList<>();
		List<FarmTask.FarmType> crops = task instanceof FarmTask ft
				? ft.getFarmTypes() : FarmTask.parseFarmTypes(record != null ? record.farmType() : null);
		for (FarmTask.FarmType t : crops)
		{
			if (t != FarmTask.FarmType.LIVESTOCK) out.add(cropIcon(t));
		}
		if (crops.contains(FarmTask.FarmType.LIVESTOCK))
		{
			for (FarmTask.LivestockType t : currentLivestockTypes()) out.add(livestockIcon(t));
		}
		for (FarmTask.TreeType t : currentForestryTypes()) out.add(treeIcon(t));
		return out;
	}

	// 原木图标
	private static ItemStack treeIcon(FarmTask.TreeType t)
	{
		return switch (t)
		{
			case OAK -> new ItemStack(Items.OAK_LOG);
			case BIRCH -> new ItemStack(Items.BIRCH_LOG);
			case SPRUCE -> new ItemStack(Items.SPRUCE_LOG);
			case JUNGLE -> new ItemStack(Items.JUNGLE_LOG);
			case ACACIA -> new ItemStack(Items.ACACIA_LOG);
			case CHERRY -> new ItemStack(Items.CHERRY_LOG);
		};
	}

	// 动作
	private void hire(String name)
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		ServerLevel level = minecraft.getSingleplayerServer().overworld();
		PlotTask t = WorkPlotEngine.findTask(boxPos);
		if (t == null) return;

		// 已加载：直接雇佣
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof Entity npc && name.equals(npc.getNpcName()))
			{
				if (!npc.isAdult() || npc.getPregnancyStage() > 0.0F) return;
				t.hireWorker(name);
				WorkPlotEngine.saveAll(level);
				reload();
				currentPage = 0;
				showPage();
				return;
			}
		}

		// 未加载：服务端线程恢复
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server == null) return;
		server.execute(() ->
		{
			PlotTask task2 = WorkPlotEngine.findTask(boxPos);
			if (task2 == null) return;
			String city = task2.cityName();
			JsonObject json = NpcData.load(level, city, name);
			if (json == null) return;
			if (json.has("age") && json.get("age").getAsInt() < com.wenzai.neosim.Config.LIFE_ADULT_AGE.get()) return;
			if (json.has("pregnancy") && json.get("pregnancy").getAsFloat() > 0.0F) return;
			Entity npc = com.wenzai.neosim.npc.Manage.spawnSingle(level, city, name, boxPos);
			if (npc != null)
			{
				task2.hireWorker(name);
				WorkPlotEngine.saveAll(level);
			}
		});
		reload();
		currentPage = 0;
		showPage();
	}

	private void fireWorker()
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		PlotTask t = WorkPlotEngine.findTask(boxPos);
		if (t != null)
		{
			t.fireWorker();
			WorkPlotEngine.saveAll(minecraft.getSingleplayerServer().overworld());
		}
		reload();
		showPage();
	}

	// 确认目标
	private void applyCrops()
	{
		StringBuilder sb = new StringBuilder();
		if (!selectedCrops.isEmpty())
		{
			List<FarmTask.FarmType> crops = new java.util.ArrayList<>(selectedCrops);
			crops.remove(FarmTask.FarmType.LIVESTOCK);
			crops.sort(java.util.Comparator.comparingInt(FarmTask.FarmType::ordinal));
			sb.append(FarmTask.farmTypesToCsv(crops));
		}
		if (!selectedLivestock.isEmpty())
		{
			List<FarmTask.LivestockType> ls = new java.util.ArrayList<>(selectedLivestock);
			ls.sort(java.util.Comparator.comparingInt(FarmTask.LivestockType::ordinal));
			if (sb.length() > 0) sb.append(",");

			// 子列表用+分隔，避免与顶层逗号冲突
			sb.append("LIVESTOCK:").append(ls.stream()
					.map(FarmTask.LivestockType::name)
					.collect(java.util.stream.Collectors.joining("+")));
		}
		if (!selectedTrees.isEmpty())
		{
			List<FarmTask.TreeType> ts = new java.util.ArrayList<>(selectedTrees);
			ts.sort(java.util.Comparator.comparingInt(FarmTask.TreeType::ordinal));
			if (sb.length() > 0) sb.append(",");
			sb.append("FORESTRY:").append(ts.stream()
					.map(FarmTask.TreeType::name)
					.collect(java.util.stream.Collectors.joining("+")));
		}
		applyCsv(FarmTask.normalizeFarmCsv(sb.toString()));
		currentPage = 0;
		showPage();
	}

	// 应用配置
	private void applyCsv(String normalized)
	{
		LOGGER.info("NeoSim-FarmApply: task={}, csv={}",
				task == null ? "null" : task.getClass().getSimpleName(), normalized);
		try
		{
			if (task instanceof FarmTask ft)
			{
				ft.applyFarmCsv(normalized);

				if (minecraft != null && minecraft.getSingleplayerServer() != null)
				{
					WorkPlotEngine.saveAll(minecraft.getSingleplayerServer().overworld());
				}
				reload();
			}
			else
			{
				// 更新内存记录
				if (record != null) record = record.withFarmType(normalized);
				LOGGER.info("NeoSim-FarmApply: no local task, record updated & packet sent csv={}", normalized);
				PacketDistributor.sendToServer(
						new ClientToServerPayloads.WorkBoxApplyPayload((byte) 0, boxPos, 0, normalized));
				refreshTask();
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-FarmApply: exception", e);
		}
	}

	// 作物复选框
	private void addCropCheckbox(int x, int y, FarmTask.FarmType t)
	{
		Checkbox cb = Checkbox.builder(Component.translatable(P + "type" + t.name()), font)
				.pos(x, y).selected(selectedCrops.contains(t))
				.onValueChange((c, v) ->
				{
					if (v) selectedCrops.add(t);
					else selectedCrops.remove(t);
				})
				.build();
		addRenderableWidget(cb);
	}

	// 牲畜复选框
	private void addLivestockCheckbox(int x, int y, FarmTask.LivestockType t)
	{
		Checkbox cb = Checkbox.builder(Component.translatable(P + "animal" + t.name()), font)
				.pos(x, y).selected(selectedLivestock.contains(t))
				.onValueChange((c, v) ->
				{
					if (v) selectedLivestock.add(t);
					else selectedLivestock.remove(t);
				})
				.build();
		addRenderableWidget(cb);
	}

	// 树种复选框
	private void addTreeCheckbox(int x, int y, FarmTask.TreeType t)
	{
		Checkbox cb = Checkbox.builder(Component.translatable(P + "tree" + t.name()), font)
				.pos(x, y).selected(selectedTrees.contains(t))
				.onValueChange((c, v) ->
				{
					if (v) selectedTrees.add(t);
					else selectedTrees.remove(t);
				})
				.build();
		addRenderableWidget(cb);
	}

	private void initTargetSelection()
	{
		List<FarmTask.FarmType> parsed = FarmTask.parseFarmTypes(
				record != null ? record.farmType() : null);
		selectedCrops = new java.util.HashSet<>(parsed);
		selectedCrops.remove(FarmTask.FarmType.LIVESTOCK);
		boolean hasLivestock = parsed.contains(FarmTask.FarmType.LIVESTOCK);
		selectedLivestock = hasLivestock
				? new java.util.HashSet<>(FarmTask.parseLivestockTypes(record.farmType()))
				: new java.util.HashSet<>();
		selectedTrees = new java.util.HashSet<>(FarmTask.parseForestryTypes(
				record != null ? record.farmType() : null));
	}

	// 模式读取
	private boolean isLivestockMode()
	{
		if (task instanceof FarmTask ft) return ft.isLivestockMode();
		return record != null && record.farmType() != null
				&& FarmTask.parseFarmTypes(record.farmType()).contains(FarmTask.FarmType.LIVESTOCK);
	}

	// 是否勾选了种植
	private boolean hasCrops()
	{
		for (FarmTask.FarmType t : FarmTask.parseFarmTypes(
				record != null ? record.farmType() : null))
		{
			if (t != FarmTask.FarmType.LIVESTOCK) return true;
		}
		return false;
	}

	// 目标页标题与列标题
	private void drawCropList(GuiGraphics gfx)
	{
		gfx.drawCenteredString(font, Component.translatable(P + "targetTitle"), width / 2, 10, 0xFFFFFF);
		gfx.drawString(font, Component.translatable(P + "industryPlant"), width / 2 - 210, 52, 0xFFFFFF);
		gfx.drawString(font, Component.translatable(P + "industryLivestock"), width / 2 - 40, 52, 0xFFFFFF);
		gfx.drawString(font, Component.translatable(P + "industryForestry"), width / 2 + 130, 52, 0xFFFFFF);
	}

	// 当前保存的牲畜列表
	private List<FarmTask.LivestockType> currentLivestockTypes()
	{
		if (task instanceof FarmTask ft) return ft.getLivestockTypes();
		return FarmTask.parseLivestockTypes(record != null ? record.farmType() : null);
	}

	// 当前保存的树种
	private List<FarmTask.TreeType> currentForestryTypes()
	{
		if (task instanceof FarmTask ft) return ft.getForestryTypes();
		return FarmTask.parseForestryTypes(record != null ? record.farmType() : null);
	}

	// 是否林业模式
	private boolean isForestryMode()
	{
		if (task instanceof FarmTask ft) return ft.isForestryMode();
		return record != null && !FarmTask.parseForestryTypes(record.farmType()).isEmpty();
	}

	// 林业行文本（树种列表）
	private String forestrySummary()
	{
		List<FarmTask.TreeType> ts = currentForestryTypes();
		if (ts.isEmpty()) return Component.translatable(P + "targetNone").getString();
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (FarmTask.TreeType t : ts)
		{
			if (!first) sb.append("、");
			sb.append(Component.translatable(P + "tree" + t.name()).getString());
			first = false;
		}
		return sb.toString();
	}

	// 目标页图标
	private void drawCropIcons(GuiGraphics gfx)
	{
		int left = width / 2 - 210;
		int right = width / 2 - 40;
		int forest = width / 2 + 130;
		int y = 80;
		for (FarmTask.FarmType t : new FarmTask.FarmType[] {
				FarmTask.FarmType.WHEAT, FarmTask.FarmType.CARROT, FarmTask.FarmType.POTATO })
		{
			Component label = Component.translatable(P + "type" + t.name());
			gfx.renderItem(cropIcon(t), left + 30 + font.width(label) + 4, y + 2, 16);
			y += 24;
		}
		y = 80;
		for (FarmTask.LivestockType t : FarmTask.LivestockType.values())
		{
			Component label = Component.translatable(P + "animal" + t.name());
			int ix = right + 30 + font.width(label) + 4;
			gfx.renderItem(livestockIcon(t), ix, y + 2, 16);
			gfx.renderItem(livestockFeedIcon(t), ix + 20, y + 2, 16);
			y += 24;
		}
		y = 80;
		for (FarmTask.TreeType t : FarmTask.TreeType.values())
		{
			Component label = Component.translatable(P + "tree" + t.name());
			int ix = forest + 30 + font.width(label) + 4;
			gfx.renderItem(treeIcon(t), ix, y + 2, 16);
			y += 24;
		}
	}

	// 图标
	private static ItemStack cropIcon(FarmTask.FarmType t)
	{
		return switch (t)
		{
			case WHEAT -> new ItemStack(Items.WHEAT);
			case CARROT -> new ItemStack(Items.CARROT);
			case POTATO -> new ItemStack(Items.POTATO);
			default -> ItemStack.EMPTY;
		};
	}

	// 产物图标
	private static ItemStack livestockIcon(FarmTask.LivestockType t)
	{
		return switch (t)
		{
			case CHICKEN -> new ItemStack(Items.CHICKEN);
			case PIG -> new ItemStack(Items.PORKCHOP);
			case COW -> new ItemStack(Items.BEEF);
			case SHEEP -> new ItemStack(Items.MUTTON);
		};
	}

	// 饲料图标
	private static ItemStack livestockFeedIcon(FarmTask.LivestockType t)
	{
		return switch (t)
		{
			case CHICKEN -> new ItemStack(Items.WHEAT_SEEDS);
			case PIG -> new ItemStack(Items.CARROT);
			case COW -> new ItemStack(Items.WHEAT);
			case SHEEP -> new ItemStack(Items.WHEAT);
		};
	}

	private int workerLevel()
	{
		if (task != null) return (int) task.getJobLevel();
		String worker = record != null && record.worker() != null ? record.worker() : "";
		if (worker.isEmpty()) return 1;
		try
		{
			String cityName = ModSavedData.getActiveCityName();
			Minecraft mc = Minecraft.getInstance();
			String saveName = mc.getSingleplayerServer() != null
					? mc.getSingleplayerServer().getWorldData().getLevelName() : null;
			JsonObject json = saveName != null && !saveName.isEmpty()
					? NpcData.load(worker, cityName, saveName)
					: NpcData.load(worker, cityName);
			if (json != null && json.has("job"))
			{
				JsonObject job = json.getAsJsonObject("job");
				if (job.has("farmer")) return job.get("farmer").getAsInt();
			}
		}
		catch (Exception ignored) {}
		return 1;
	}

	private String stateLabel()
	{
		PlotTask.PlotState st;
		if (task != null)
		{
			st = task.getState();
		}
		else if (record != null && record.state() != null)
		{
			st = PlotTask.PlotState.valueOfSafe(record.state());
		}
		else
		{
			st = PlotTask.PlotState.IDLE;
		}
		// 林业专属：仅林业模式下，田间作业显示「伐木中」，缺树苗显示「缺少树苗」
		if (isForestryMode() && !hasCrops()
				&& (st == PlotTask.PlotState.HARVEST || st == PlotTask.PlotState.TILL || st == PlotTask.PlotState.PLANT))
		{
			if (task instanceof FarmTask ft && ft.isMissingSaplings())
			{
				return Component.translatable(P + "state.waitingSapling").getString();
			}
			return Component.translatable(P + "state.chop").getString();
		}
		String key = switch (st)
		{
			case WAITING_WORKER -> "state.waitingWorker";
			case WORKER_ASSIGNED -> "state.workerAssigned";
			case CHECKING_CHESTS -> "state.checkingChests";
			case HARVEST -> "state.harvest";
			case TILL -> "state.till";
			case PLANT -> "state.plant";
			case WAITING_SEED -> "state.waitingSeed";
			case RAISE -> "state.raise";
			case WAITING_FEED -> "state.waitingFeed";
			case WAITING_FOR_CHEST -> "state.waitingChest";
			case DEPLETED -> "state.depleted";
			default -> "state.idle";
		};
		return Component.translatable(P + key).getString();
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
		if (minecraft != null)
		{
			minecraft.setScreen(null);
			minecraft.mouseHandler.grabMouse();
		}
	}
}
