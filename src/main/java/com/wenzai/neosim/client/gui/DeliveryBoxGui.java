package com.wenzai.neosim.client.gui;

import com.google.gson.JsonObject;
import com.wenzai.neosim.block.DeliveryBoxPersistence;
import com.wenzai.neosim.block.DeliveryEngine;
import com.wenzai.neosim.block.DeliveryTask;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.network.ServerToClientPayloads;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class DeliveryBoxGui extends Screen implements HireListPanel.HostScreen
{
	private static final String P = "gui.neosim.DeliveryBox.";
	private static final java.util.Map<BlockPos, String> WORKER_MAP = com.wenzai.neosim.NeoSim.WORKER_MAP;

	private final HireListPanel hirePanel;

	private final BlockPos boxPos;
	private DeliveryBoxPersistence.DeliveryBoxRecord record;
	private DeliveryTask task;
	private int currentPage = 0;

	// 无任务时 worker 档案等级字段缓存（reload/refreshTask 时刷新，避免每帧读 JSON 文件）
	private String cachedWorkerKey = "";
	private int cachedWorkerLevel = -1;

	// 快递站材料页（页 2）：服务端扫描箱链后回包
	private static final int STATION_COLS = 2;
	private static final int STATION_ROW_H = 20;
	private List<ServerToClientPayloads.StationItemsResponsePayload.StationEntry> stationItems = List.of();
	private int stationChests = 0;
	private boolean stationPending = false;
	private boolean stationTimeout = false;
	private int stationOffset = 0;

	// 请求冷却（与服务端限流窗口对齐）与"发出去多久没回来"，避免请求被丢弃后一直停在"读取中"
	private static final long STATION_REQUEST_COOLDOWN_MS = 1000L;
	private static final long STATION_PENDING_TIMEOUT_MS = 3000L;
	private long stationRequestAt = 0L;
	private long stationPendingSince = 0L;

	public DeliveryBoxGui(BlockPos boxPos)
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
		}, boxPos, P, 3, this::hire, () ->
		{
			currentPage = 0;
			showPage();
		});
	}

	// 客户端读取盒记录
	public static DeliveryBoxPersistence.DeliveryBoxRecord loadRecord(BlockPos pos)
	{
		String cityName = ClientDataHolder.getInstance().getCityName();
		if (cityName.isEmpty()) return null;
		Minecraft mc = Minecraft.getInstance();
		String saveName = mc.getSingleplayerServer() != null
				? mc.getSingleplayerServer().getWorldData().getLevelName() : null;
		return DeliveryBoxPersistence.findRecord(saveName, cityName, pos);
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
				? DeliveryEngine.findTask(boxPos) : null;

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
		if (currentPage == 0) drawMain(gfx);
		else if (currentPage == 2) drawStationItems(gfx);
		else hirePanel.render(gfx);
	}

	// 按钮在 showPage 内重建（clearWidgets 后），文字由 drawXxx(gfx) 每帧绘制（与 FarmingBoxGui 一致）
	private void showPage()
	{
		clearWidgets();
		int centerPosX = width / 2;

		if (currentPage == 0)
		{
			boolean hasWorker = task != null
					? !task.getWorkerName().isEmpty()
					: (record != null && record.worker() != null && !record.worker().isEmpty());

			addButton(1, centerPosX - 100, height - 30, 100, 20,
					Component.translatable(P + "close"), b -> onClose());

			addButton(2, centerPosX, height - 30, 100, 20,
					hasWorker
							? Component.translatable(P + "fire")
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
					task != null && task.isPaused()
							? Component.translatable(P + "resume")
							: Component.translatable(P + "pause"),
					b -> togglePause());

			// 快递站材料：只读展示快递盒箱链里的物品
			addButton(4, centerPosX - 100, height - 82, 200, 20,
					Component.translatable(P + "station"), b -> openStationPage());
		}
		else if (currentPage == 2)
		{
			// 快递站材料页：刷新 / 翻页 / 返回
			int perPage = stationPerPage();

			addButton(1, centerPosX - 100, height - 30, 100, 20,
					Component.translatable(P + "goBack"), b ->
					{
						currentPage = 0;
						showPage();
					});
			addButton(2, centerPosX, height - 30, 100, 20,
					Component.translatable(P + "station.refresh"), b ->
					{
						stationOffset = 0;
						requestStationItems();
						showPage();
					});
			addButton(3, centerPosX - 100, height - 52, 100, 20,
					Component.translatable(P + "prev"), b ->
					{
						stationOffset = Math.max(0, stationOffset - perPage);
						showPage();
					});
			addButton(4, centerPosX, height - 52, 100, 20,
					Component.translatable(P + "next"), b ->
					{
						if (stationOffset + perPage < stationItems.size()) stationOffset += perPage;
						showPage();
					});
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

		boolean hasWorker = task != null
				? !task.getWorkerName().isEmpty()
				: (record != null && record.worker() != null && !record.worker().isEmpty());
		String workerName = task != null ? task.getWorkerName()
				: (record != null && record.worker() != null ? record.worker() : "");

		WorkBoxText text = new WorkBoxText(gfx, font, width);

		// 快递员行 + 等级
		if (hasWorker)
		{
			text.workerLine(Component.translatable(P + "worker", workerName),
					Component.translatable(P + "workerLevel", workerLevel()));
		}
		else
		{
			text.line(Component.translatable(P + "workerNone"), 0xAAAAAA);
		}

		// 状态行（含跳单原因）
		Component stateLine = Component.translatable(P + "state", stateLabel());
		if (task != null && !task.getLastSkipReason().getString().isEmpty())
		{
			stateLine = task.getLastSkipReason();
		}
		text.line(stateLine, 0xFFFFFF);

		// 当前配送行
		if (task != null && task.getCarryItem() != null)
		{
			String itemName = task.getCarryItem().getDescription().getString();
			int count = task.getCarryCount();
			text.line(Component.literal(itemName + " ×" + count), 0xFFFF80);
		}
	}

	// 动作（雇佣/解雇统一发包，由服务端 WorkerService 校验+落盘，WorkerUpdatePayload 回来刷新）
	private void hire(String name)
	{
		PacketDistributor.sendToServer(new ClientToServerPayloads.HirePayload(boxPos, name));
		reload();
		currentPage = 0;
		showPage();
	}

	private void fireWorker()
	{
		PacketDistributor.sendToServer(new ClientToServerPayloads.FirePayload(boxPos));
		reload();
		showPage();
	}

	@Override
	public void onHireList(List<ServerToClientPayloads.HireListResponsePayload.HireEntry> entries)
	{
		if (hirePanel != null) hirePanel.onHireList(entries);
	}

	@Override
	public void onWorkerUpdate(BlockPos pos)
	{
		if (pos.equals(boxPos))
		{
			reload();
			showPage();
		}
	}

	private void togglePause()
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		DeliveryTask t = DeliveryEngine.findTask(boxPos);
		if (t != null)
		{
			t.setPaused(!t.isPaused());
			DeliveryEngine.saveAll(minecraft.getSingleplayerServer().overworld());
		}
		reload();
		showPage();
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
				if (job.has("courier"))
				{
					cachedWorkerKey = worker;
					cachedWorkerLevel = job.get("courier").getAsInt();
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
		DeliveryTask.DeliveryState st;
		if (task != null)
		{
			st = task.getState();
		}
		else if (record != null && record.state() != null)
		{
			st = DeliveryTask.DeliveryState.valueOfSafe(record.state());
		}
		else
		{
			st = DeliveryTask.DeliveryState.IDLE;
		}
		String key = switch (st)
		{
			case WAITING_WORKER -> "state.waitingWorker";
			case WORKER_ASSIGNED -> "state.workerAssigned";
			case WALKING_TO_SITE -> "state.walking";
			case DEPOSITING -> "state.depositing";
			case RETURNING -> "state.returning";
			default -> "state.idle";
		};
		return Component.translatable(P + key).getString();
	}

	// ===== 快递站材料页 =====

	private void openStationPage()
	{
		currentPage = 2;
		stationOffset = 0;
		requestStationItems();
		showPage();
	}

	// 统一发包，服务端读快递盒箱子后回包（联机/单机一致）
	// 冷却期内不重复发包：服务端对同一玩家有 250ms 限流，重复请求会被丢弃，这里先把重复挡在本地
	private void requestStationItems()
	{
		long now = System.currentTimeMillis();
		if (now - stationRequestAt < STATION_REQUEST_COOLDOWN_MS) return;

		stationRequestAt = now;
		stationPending = true;
		stationPendingSince = now;
		stationTimeout = false;
		PacketDistributor.sendToServer(new ClientToServerPayloads.StationItemsRequestPayload(boxPos));
	}

	// 收到服务端箱链材料
	public void applyStationItems(int chestCount,
			List<ServerToClientPayloads.StationItemsResponsePayload.StationEntry> entries)
	{
		this.stationChests = chestCount;
		this.stationItems = entries != null ? entries : List.of();
		this.stationPending = false;
		this.stationTimeout = false;
		if (currentPage == 2) showPage();
	}

	private int stationPerPage()
	{
		int rows = Math.max(1, (height - 130) / STATION_ROW_H);
		return rows * STATION_COLS;
	}

	// 材料行：左图标 + 名称，右侧总量（名称超宽截断）
	private void drawStationItems(GuiGraphics gfx)
	{
		gfx.drawCenteredString(font, Component.translatable(P + "station.title"), width / 2, 10, 0xFFFFFF);

		int posX = width / 2 - 170;
		int posY = 38;

		// 回包迟迟不来（被限流丢弃/丢包）：到点自动解除，绝不停在"读取中"
		if (stationPending && System.currentTimeMillis() - stationPendingSince > STATION_PENDING_TIMEOUT_MS)
		{
			stationPending = false;
			stationTimeout = true;
		}

		if (stationPending)
		{
			gfx.drawString(font, Component.translatable(P + "station.scanning"), posX, posY, 0xAAAAAA);
			return;
		}

		if (stationTimeout)
		{
			gfx.drawString(font, Component.translatable(P + "station.timeout"), posX, posY, 0xFF8080);
			posY += 14;
		}

		gfx.drawString(font, Component.translatable(P + "station.header", stationChests, stationItems.size()),
				posX, posY, 0xFFFFFF);
		posY += 16;

		if (stationItems.isEmpty())
		{
			gfx.drawString(font, Component.translatable(P + "station.empty"), posX, posY, 0xAAAAAA);
			return;
		}

		int perPage = stationPerPage();
		int pages = (stationItems.size() + perPage - 1) / perPage;
		stationOffset = Math.max(0, Math.min(stationOffset, (pages - 1) * perPage));
		if (pages > 1)
		{
			gfx.drawString(font, Component.translatable(P + "station.page",
					stationOffset / perPage + 1, pages), posX, posY, 0x808080);
		}
		posY += 14;

		int colSizeW = 170;
		for (int i = 0; i < perPage && stationOffset + i < stationItems.size(); i++)
		{
			ServerToClientPayloads.StationItemsResponsePayload.StationEntry entry = stationItems.get(stationOffset + i);
			int cellPosX = posX + (i % STATION_COLS) * colSizeW;
			int cellPosY = posY + (i / STATION_COLS) * STATION_ROW_H;

			// 左：物品图标
			gfx.renderFakeItem(new ItemStack(entry.item()), cellPosX, cellPosY - 2);

			// 右：名称（超宽截断）+ 总量
			Component count = Component.literal("×" + entry.count());
			int countSizeW = font.width(count);
			int maxNameSizeW = Math.max(8, colSizeW - 24 - countSizeW - 4);
			String name = entry.item().getDescription().getString();
			gfx.drawString(font, font.plainSubstrByWidth(name, maxNameSizeW, true),
					cellPosX + 20, cellPosY + 3, 0xCCCCCC);
			gfx.drawString(font, count, cellPosX + colSizeW - countSizeW - 4, cellPosY + 3, 0xFFFF80);
		}
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
