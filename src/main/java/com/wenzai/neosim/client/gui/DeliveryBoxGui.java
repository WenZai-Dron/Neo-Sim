// 快递盒 GUI：雇佣/解雇快递员、状态页、暂停/继续（仿 FarmingBoxGui）

package com.wenzai.neosim.client.gui;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wenzai.neosim.Config;
import com.wenzai.neosim.block.DeliveryBoxPersistence;
import com.wenzai.neosim.block.DeliveryEngine;
import com.wenzai.neosim.block.DeliveryTask;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.ModSavedData;
import com.wenzai.neosim.storage.NpcData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

public class DeliveryBoxGui extends Screen
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String P = "gui.neosim.DeliveryBox.";
	private static final java.util.Map<BlockPos, String> WORKER_MAP = com.wenzai.neosim.NeoSim.WORKER_MAP;

	private final HireListPanel hirePanel;

	private final BlockPos boxPos;
	private DeliveryBoxPersistence.DeliveryBoxRecord record;
	private DeliveryTask task;
	private int currentPage = 0;

	public DeliveryBoxGui(BlockPos boxPos)
	{
		super(Component.translatable(P + "title"));
		this.boxPos = boxPos;
		reload();
		this.hirePanel = new HireListPanel(new HireListPanel.WidgetHost()
		{
			@Override
			public <T extends AbstractWidget> T add(T widget) { return addRenderableWidget(widget); }
			@Override
			public void clear() { clearWidgets(); }
		}, boxPos, P, "courier", this::hire, () ->
		{
			currentPage = 0;
			showPage();
		});
	}

	// 客户端读取盒记录
	public static DeliveryBoxPersistence.DeliveryBoxRecord loadRecord(BlockPos pos)
	{
		String cityName = ModSavedData.getActiveCityName();
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
						else { currentPage = 1; showPage(); }
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

	// 动作
	private void hire(String name)
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		ServerLevel level = minecraft.getSingleplayerServer().overworld();
		DeliveryTask t = DeliveryEngine.findTask(boxPos);
		if (t == null) return;

		// 已加载：直接雇佣
		for (net.minecraft.world.entity.Entity e : level.getAllEntities())
		{
			if (e instanceof Entity npc && name.equals(npc.getNpcName()))
			{
				if (!npc.isAdult() || npc.getPregnancyStage() > 0.0F) return;
				t.hireWorker(name);
				DeliveryEngine.saveAll(level);
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
			DeliveryTask task2 = DeliveryEngine.findTask(boxPos);
			if (task2 == null) return;
			String city = task2.cityName();
			JsonObject json = NpcData.load(level, city, name);
			if (json == null) return;
			if (json.has("age") && json.get("age").getAsInt() < Config.LIFE_ADULT_AGE.get()) return;
			if (json.has("pregnancy") && json.get("pregnancy").getAsFloat() > 0.0F) return;
			Entity npc = com.wenzai.neosim.npc.Manage.spawnSingle(level, city, name, boxPos);
			if (npc != null)
			{
				task2.hireWorker(name);
				DeliveryEngine.saveAll(level);
			}
		});
		reload();
		currentPage = 0;
		showPage();
	}

	private void fireWorker()
	{
		if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
		DeliveryTask t = DeliveryEngine.findTask(boxPos);
		if (t != null)
		{
			t.fireWorker();
			DeliveryEngine.saveAll(minecraft.getSingleplayerServer().overworld());
		}
		reload();
		showPage();
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
				if (job.has("courier")) return job.get("courier").getAsInt();
			}
		}
		catch (Exception ignored) {}
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
