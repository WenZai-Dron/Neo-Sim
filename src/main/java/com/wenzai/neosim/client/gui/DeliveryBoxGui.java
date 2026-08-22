package com.wenzai.neosim.client.gui;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

public class DeliveryBoxGui extends Screen implements HireListPanel.HostScreen
{
	private static final Logger LOGGER = LogUtils.getLogger();
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
		else hirePanel.render(gfx);
	}

	// 按钮在 showPage 内重建（clearWidgets 后），文字由 drawXxx(gfx) 每帧绘制（与 FarmingBoxGui 一致）
	private void showPage()
	{
		clearWidgets();
		int cx = width / 2;

		if (currentPage == 0)
		{
			boolean hasWorker = task != null
					? !task.getWorkerName().isEmpty()
					: (record != null && record.worker() != null && !record.worker().isEmpty());

			addButton(1, cx - 100, height - 30, 100, 20,
					Component.translatable(P + "close"), b -> onClose());

			addButton(2, cx, height - 30, 100, 20,
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

			addButton(3, cx - 100, height - 56, 200, 20,
					task != null && task.isPaused()
							? Component.translatable(P + "resume")
							: Component.translatable(P + "pause"),
					b -> togglePause());
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

		// 快递员行
		if (hasWorker)
		{
			int level = workerLevel();
			gfx.drawString(font, Component.translatable(P + "worker", workerName), 5, 40, 0xFFFFFF);
			gfx.drawString(font, Component.translatable(P + "workerLevel", level), width / 2 + 3, 40, 0xFFFFFF);
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "workerNone"), 5, 40, 0xFFFFFF);
		}

		// 状态行（含跳单原因）
		String stateText = stateLabel();
		if (task != null)
		{
			String skip = task.getLastSkipReason();
			if (!skip.isEmpty()) stateText = skip;
		}
		gfx.drawString(font, Component.translatable(P + "state", stateText), 5, 64, 0xFFFFFF);

		// 当前配送行
		if (task != null && task.getCarryItem() != null)
		{
			String itemName = task.getCarryItem().getDescription().getString();
			int count = task.getCarryCount();
			gfx.drawString(font, Component.literal(itemName + " ×" + count), 5, 88, 0xFFFF80);
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
