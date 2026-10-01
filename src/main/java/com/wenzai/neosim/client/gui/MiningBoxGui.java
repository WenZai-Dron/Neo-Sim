package com.wenzai.neosim.client.gui;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.block.MineTask;
import com.wenzai.neosim.block.PlotTask;
import com.wenzai.neosim.block.WorkBoxPersistence;
import com.wenzai.neosim.block.WorkPlotEngine;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

public class MiningBoxGui extends Screen implements HireListPanel.HostScreen
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String P = "gui.neosim.MiningBox.";

	private static final java.util.Map<BlockPos, String> WORKER_MAP = com.wenzai.neosim.NeoSim.WORKER_MAP;

	private final HireListPanel hirePanel;

	private final BlockPos boxPos;
	private WorkBoxPersistence.WorkBoxRecord record;
	private PlotTask task;
	private int currentPage = 0;
	private int draftMask;

	// 无任务时 worker 档案等级字段缓存（reload/refreshTask 时刷新，避免每帧读 JSON 文件）
	private String cachedWorkerKey = "";
	private int cachedWorkerLevel = -1;

	public MiningBoxGui(BlockPos boxPos)
	{
		super(Component.translatable(P + "title"));
		this.boxPos = boxPos;
		reload();
		this.hirePanel = new HireListPanel(new HireListPanel.WidgetHost()
		{
			@Override
			public <T extends AbstractWidget> T add(T widget)
			{
				return addRenderableWidget(widget);
			}

			@Override
			public void clear()
			{
				clearWidgets();
			}
		}, boxPos, P, 2, this::hire, () ->
		{
			currentPage = 0;
			showPage();
		});
	}

	// 客户端读取盒记录
	public static WorkBoxPersistence.WorkBoxRecord loadRecord(BlockPos pos)
	{
		String cityName = ClientDataHolder.getInstance().getCityName();
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

		// 任务/记录变化 → worker 等级缓存失效
		cachedWorkerKey = "";
		cachedWorkerLevel = -1;
	}

	@Override
	public boolean isPauseScreen()
	{
		return false;
	}

	@Override
	protected void init()
	{
		showPage();
	}

	@Override
	public void render(GuiGraphics gfx, int mx, int my, float pt)
	{
		renderBackground(gfx, mx, my, pt);
		super.render(gfx, mx, my, pt);
		if (currentPage == 0)
		{
			drawMain(gfx);
			drawDiscardButtonIcons(gfx);
		}
		else if (currentPage == 1) hirePanel.render(gfx);
		else
		{
			drawDiscardList(gfx);
			drawDiscardIcons(gfx);
		}
	}

	private void showPage()
	{
		clearWidgets();
		int centerPosX = width / 2;

		if (currentPage == 0)
		{
			addButton(1, centerPosX - 100, height - 30, 100, 20,
					Component.translatable(P + "close"), b -> onClose());

			boolean hasWorker = task != null && !task.getWorkerName().isEmpty();
			addButton(2, centerPosX, height - 30, 100, 20,
					hasWorker
							? Component.translatable(P + "fire", task.getWorkerName())
							: Component.translatable(P + "hire"),
					b ->
					{
						if (hasWorker) fireWorker();
						else
						{
							currentPage = 1;
							showPage();
						}
					});

			addButton(3, centerPosX - 100, height - 56, 200, 20,
					Component.translatable(P + "discards"),
					b ->
					{
						currentPage = 2;
						showPage();
					});

			addButton(4, centerPosX - 100, height - 80, 200, 20,
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
			addButton(999, centerPosX - 150, height - 25, 100, 20,
					Component.translatable(P + "goBack"),
					b ->
					{
						currentPage = 0;
						showPage();
					});
			addButton(998, centerPosX - 50, height - 25, 100, 20,
					Component.translatable(P + "apply"),
					b -> selectDiscards());

			int colPosX = centerPosX - 120;
			int colPosY = 40;
			draftMask = discards();
			addDiscardCheckbox(colPosX, colPosY, 1);
			colPosY += 24;
			addDiscardCheckbox(colPosX, colPosY, 2);
			colPosY += 24;
			addDiscardCheckbox(colPosX, colPosY, 4);
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

		WorkBoxText text = new WorkBoxText(gfx, font, width);

		// 绑定矩形
		if (record.bound())
		{
			int sizeW = record.rx2() - record.rx1() + 1;
			int sizeH = record.rz2() - record.rz1() + 1;
			sizeW = sizeW > 2 ? sizeW - 2 : sizeW;
			sizeH = sizeH > 2 ? sizeH - 2 : sizeH;
			text.line(Component.translatable(P + "rect", sizeW, sizeH), 0xFFFFFF);
		}
		else
		{
			text.line(Component.translatable(P + "unbound"), 0xFFAA55);
		}

		// 深度
		text.line(Component.translatable(P + "depth", depth()), 0xFFFFFF);

		// 矿工
		String worker = record.worker() != null ? record.worker() : "";
		if (!worker.isEmpty())
		{
			text.workerLine(Component.translatable(P + "worker", worker),
					Component.translatable(P + "workerLevel", workerLevel()));
		}
		else
		{
			text.line(Component.translatable(P + "workerNone"), 0xAAAAAA);
		}

		// 状态
		text.line(Component.translatable(P + "state", stateLabel()), 0xFFFFFF);
	}

	// 主页丢弃按钮右侧图标
	private void drawDiscardButtonIcons(GuiGraphics gfx)
	{
		int btnPosX = width / 2 - 100;
		int btnPosY = height - 56;
		int iconPosY = btnPosY + (20 - 16) / 2;
		java.util.List<java.util.List<ItemStack>> selected = new java.util.ArrayList<>();
		for (int bit : new int[] { 1, 2, 4 })
		{
			if ((discards() & bit) != 0) selected.add(categoryIcons(bit));
		}
		int iconPosX = btnPosX + 200 - 4 - selected.size() * 18;
		long idx = System.currentTimeMillis() / 1000L;
		for (java.util.List<ItemStack> icons : selected)
		{
			gfx.renderItem(icons.get((int) (idx % icons.size())), iconPosX, iconPosY);
			iconPosX += 18;
		}
	}

	// 动作（雇佣/解雇统一发包，由服务端 WorkerService 校验+落盘，WorkerUpdatePayload 回来刷新）
	private void hire(String name)
	{
		PacketDistributor.sendToServer(
				new com.wenzai.neosim.network.ClientToServerPayloads.HirePayload(boxPos, name));
		reload();
		currentPage = 0;
		showPage();
	}

	private void fireWorker()
	{
		PacketDistributor.sendToServer(
				new com.wenzai.neosim.network.ClientToServerPayloads.FirePayload(boxPos));
		reload();
		showPage();
	}

	@Override
	public void onHireList(java.util.List<com.wenzai.neosim.network.ServerToClientPayloads.HireListResponsePayload.HireEntry> entries)
	{
		if (hirePanel != null) hirePanel.onHireList(entries);
	}

	@Override
	public void onWorkerUpdate(net.minecraft.core.BlockPos pos)
	{
		if (pos.equals(boxPos))
		{
			reload();
			showPage();
		}
	}

	// 确认丢弃
	private void selectDiscards()
	{
		LOGGER.info("NeoSim-MineApply: task={}, mask={}",
				task == null ? "null" : task.getClass().getSimpleName(), draftMask);
		try
		{
			if (task instanceof MineTask mt)
			{
				mt.setDiscards(draftMask);
				reload();
			}
			else
			{
				// 更新内存记录
				if (record != null) record = record.withDiscards(draftMask);
				LOGGER.info("NeoSim-MineApply: no local task, record updated & packet sent mask={}", draftMask);
				PacketDistributor.sendToServer(
						new ClientToServerPayloads.WorkBoxApplyPayload((byte) 1, boxPos, draftMask, ""));
				refreshTask();
			}
		}
		catch (Exception e)
		{
			LOGGER.error("NeoSim-MineApply: exception", e);
		}
		currentPage = 0;
		showPage();
	}

	// 丢弃类别复选框
	private void addDiscardCheckbox(int posX, int posY, int bit)
	{
		Checkbox cb = Checkbox.builder(Component.translatable(P + catKey(bit)), font)
				.pos(posX, posY).selected((draftMask & bit) != 0)
				.onValueChange((c, v) ->
				{
					if (v) draftMask |= bit;
					else draftMask &= ~bit;
				})
				.build();
		addRenderableWidget(cb);
	}

	private static String catKey(int bit)
	{
		return switch (bit)
		{
			case 2 -> "catStone";
			case 4 -> "catSand";
			default -> "catDirt";
		};
	}

	// 丢弃选择页标题
	private void drawDiscardList(GuiGraphics gfx)
	{
		gfx.drawCenteredString(font, Component.translatable(P + "discardTitle"), width / 2, 10, 0xFFFFFF);
	}

	// 丢弃选择页
	private void drawDiscardIcons(GuiGraphics gfx)
	{
		int colPosX = width / 2 - 120;
		int colPosY = 40;
		for (int bit : new int[] { 1, 2, 4 })
		{
			List<ItemStack> icons = categoryIcons(bit);
			int iconPosX = colPosX + 240 - 6 - icons.size() * 16 - (icons.size() - 1) * 2;
			for (ItemStack icon : icons)
			{
				gfx.renderItem(icon, iconPosX, colPosY + 2);
				iconPosX += 18;
			}
			colPosY += 24;
		}
	}

	// 图标
	private static List<ItemStack> categoryIcons(int bit)
	{
		return switch (bit)
		{
			case 1 -> List.of(new ItemStack(Items.DIRT), new ItemStack(Items.GRASS_BLOCK));
			case 2 -> List.of(new ItemStack(Items.STONE), new ItemStack(Items.COBBLESTONE));
			case 4 -> List.of(new ItemStack(Items.SAND), new ItemStack(Items.RED_SAND));
			default -> List.of();
		};
	}

	// 展示辅助
	private int discards()
	{
		if (task != null) return task instanceof MineTask mt ? mt.getDiscards() : 0;
		return record != null ? record.discards() : 0;
	}

	private int depth()
	{
		if (task != null) return task instanceof MineTask mt ? mt.getDepth() : 0;
		return record != null ? record.depth() : 0;
	}

	private int workerLevel()
	{
		if (task != null) return (int) task.getJobLevel();
		String worker = record != null && record.worker() != null ? record.worker() : "";
		if (worker.isEmpty()) return 1;

		// 同一 worker 的等级缓存（reload/refreshTask 时失效），避免每帧读 JSON
		if (worker.equals(cachedWorkerKey) && cachedWorkerLevel >= 0) return cachedWorkerLevel;
		try
		{
			String cityName = ClientDataHolder.getInstance().getCityName();
			Minecraft mc = Minecraft.getInstance();
			String saveName = mc.getSingleplayerServer() != null
					? mc.getSingleplayerServer().getWorldData().getLevelName() : null;
			JsonObject json = saveName != null && !saveName.isEmpty()
					? NpcData.load(worker, cityName, saveName)
					: NpcData.load(worker, cityName);
			if (json != null && json.has("job"))
			{
				JsonObject job = json.getAsJsonObject("job");
				if (job.has("miner"))
				{
					cachedWorkerKey = worker;
					cachedWorkerLevel = job.get("miner").getAsInt();
					return cachedWorkerLevel;
				}
			}
		}
		catch (Exception ignored)
		{
		}
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
		String key = switch (st)
		{
			case WAITING_WORKER -> "state.waitingWorker";
			case WORKER_ASSIGNED -> "state.workerAssigned";
			case CHECKING_CHESTS -> "state.checkingChests";
			case MINING -> "state.mining";
			case WAITING_FOR_CHEST -> "state.waitingChest";
			case DEPLETED -> "state.depleted";
			default -> "state.idle";
		};
		return Component.translatable(P + key).getString();
	}

	private Button addButton(int id, int posX, int posY, int sizeW, int sizeH, Component label, Button.OnPress action)
	{
		Button btn = Button.builder(label, action != null ? action : b -> { })
				.pos(posX, posY).size(sizeW, sizeH).build();
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
