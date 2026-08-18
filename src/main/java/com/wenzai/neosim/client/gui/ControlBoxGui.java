package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.building.ControlBoxPersistence;
import com.wenzai.neosim.building.ControlBoxPersistence.ControlBoxRecord;
import com.wenzai.neosim.building.ControlBoxPersistence.Resident;
import com.wenzai.neosim.client.BuildingNameLocalizer;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class ControlBoxGui extends Screen
{
	private static final String P = "gui.neosim.ControlBox.";

	// 布局常量：信息区/居民列表行位置
	private static final int INFO_TOP = 50;
	private static final int RESIDENTS_TOP = 122;
	private static final int ROW_H = 20;

	private final BlockPos boxPos;
	private ControlBoxRecord record;
	private boolean residential;
	private String currentPage = "main";
	private List<String> homelessNames = new ArrayList<>();

	public ControlBoxGui(BlockPos boxPos)
	{
		super(Component.translatable(P + "title"));
		this.boxPos = boxPos;
		this.record = loadRecord(boxPos);
		this.residential = isResidentialRecord(record);
	}

	// 客户端读取控制箱记录
	public static ControlBoxRecord loadRecord(BlockPos pos)
	{
		String cityName = ClientDataHolder.getInstance().getCityName();
		if (cityName.isEmpty()) return null;

		String saveName = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null)
		{
			saveName = mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		return ControlBoxPersistence.findRecord(saveName, cityName, pos);
	}

	public static boolean hasRecord(BlockPos pos)
	{
		return loadRecord(pos) != null;
	}

	// 住宅判定：蓝图注册表未加载时按非住宅只读回退
	private static boolean isResidentialRecord(ControlBoxRecord rec)
	{
		if (rec == null) return false;
		var schematic = SchematicRegistry.getInstance().get(rec.schematicName());
		return schematic != null && schematic.getType() == BuildingType.RESIDENTIAL;
	}

	// 请求服务端权威的无家名单（缺陷 C 结构性：不再客户端直读本地文件，多人下也可用）
	private void requestHomelessList()
	{
		homelessNames = List.of();
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new com.wenzai.neosim.network.ClientToServerPayloads.HomelessListRequestPayload());
	}

	// 收到服务端无家名单后重建子页
	public void applyHomelessList(List<String> names)
	{
		homelessNames = names != null ? names : List.of();
		if (currentPage.equals("homeless"))
		{
			init();
		}
	}

	// 生命周期
	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	protected void init()
	{
		clearWidgets();
		if (currentPage.equals("main"))
		{
			buildMainPage();
		}
		else
		{
			buildHomelessPage();
		}
	}

	private void buildMainPage()
	{
		// 底部按钮：关闭 / 安排入住（住宅）/ 清空全部住户（住宅且有居民）
		addRenderableWidget(Button.builder(Component.translatable(P + "close"), b -> onClose())
				.pos(width / 2 - 50, height - 30).size(100, 20).build());

		if (residential && record != null)
		{
			addRenderableWidget(Button.builder(Component.translatable(P + "moveIn"), b ->
			{
				currentPage = "homeless";
				requestHomelessList();
				init();
			}).pos(width / 2 - 160, height - 30).size(110, 20).build());

			Button evictAll = Button.builder(Component.translatable(P + "evictAll"), b -> sendAction((byte) 2, ""))
					.pos(width / 2 + 60, height - 30).size(110, 20).build();
			evictAll.active = !record.residents().isEmpty();
			addRenderableWidget(evictAll);

			// 每个居民行尾的驱逐按钮
			int y = RESIDENTS_TOP + ROW_H;
			for (Resident r : record.residents())
			{
				final String name = r.name();
				addRenderableWidget(Button.builder(Component.translatable(P + "evict"), b -> sendAction((byte) 1, name))
						.pos(width / 2 + 60, y).size(60, 16).build());
				y += ROW_H;
			}
		}
	}

	private void buildHomelessPage()
	{
		addRenderableWidget(Button.builder(Component.translatable(P + "back"), b ->
		{
			currentPage = "main";
			record = loadRecord(boxPos);
			init();
		}).pos(width / 2 - 50, height - 30).size(100, 20).build());

		int y = 70;
		for (String name : homelessNames)
		{
			final String n = name;
			addRenderableWidget(Button.builder(Component.translatable(P + "moveIn"), b -> sendAction((byte) 3, n))
					.pos(width / 2 + 60, y).size(70, 16).build());
			y += 22;
		}
	}

	// 发送管理动作；刷新由服务端处理完后的 ack 触发（避免读到旧文件）
	private void sendAction(byte action, String targetName)
	{
		PacketDistributor.sendToServer(new ClientToServerPayloads.ControlBoxManagePayload(boxPos, action, targetName));
	}

	// 服务端 ack 后刷新：重读记录并重建界面
	public void refresh()
	{
		record = loadRecord(boxPos);
		residential = isResidentialRecord(record);
		currentPage = "main";
		init();
	}

	public BlockPos boxPos()
	{
		return boxPos;
	}

	@Override
	public void render(GuiGraphics gfx, int mx, int my, float pt)
	{
		renderBackground(gfx, mx, my, pt);
		super.render(gfx, mx, my, pt);
		drawInfo(gfx);
	}

	private void drawInfo(GuiGraphics gfx)
	{
		gfx.drawCenteredString(font, Component.translatable(P + "title"), width / 2, 10, 0xFFFFFF);

		if (record == null)
		{
			gfx.drawCenteredString(font, Component.translatable(P + "noRecord"), width / 2, 60, 0xAAAAAA);
			return;
		}

		// 安排入住子页：只绘制子页内容，不叠加主信息区
		if (currentPage.equals("homeless"))
		{
			gfx.drawCenteredString(font, Component.translatable(P + "homelessTitle"), width / 2, 40, 0xFFFFFF);
			if (homelessNames.isEmpty())
			{
				gfx.drawCenteredString(font, Component.translatable(P + "noHomeless"), width / 2, 90, 0xAAAAAA);
			}
			else
			{
				int hy = 70;
				for (String n : homelessNames)
				{
					gfx.drawString(font, Component.literal(n), width / 2 - 100, hy, 0xCCCCCC);
					hy += 22;
				}
			}
			return;
		}

		int x = width / 2 - 100;

		// 建筑名
		gfx.drawString(font, Component.translatable(P + "building",
				BuildingNameLocalizer.localize(record.schematicName())), x, INFO_TOP, 0xFFFFFF);

		// 作者
		String author = record.author() != null && !record.author().isEmpty()
				? record.author()
				: Component.translatable(P + "none").getString();
		gfx.drawString(font, Component.translatable(P + "author", author), x, INFO_TOP + 24, 0xCCCCCC);

		// 所建玩家
		String placer = record.placerName() != null && !record.placerName().isEmpty()
				? record.placerName()
				: Component.translatable(P + "none").getString();
		gfx.drawString(font, Component.translatable(P + "placer", placer), x, INFO_TOP + 48, 0xCCCCCC);

		// 生活点占用（住宅）：右列，每生活点一行（空闲 / 住户: X）
		if (residential)
		{
			List<BlockPos> points = new ArrayList<>(record.livingPoints());
			if (points.isEmpty())
			{
				// 无生活点的住宅按1个控制箱位计
				points.add(record.boxPos());
			}
			int lx = width / 2 + 60;
			int ly = INFO_TOP;
			gfx.drawString(font, Component.translatable(P + "livingPoints",
					points.size(), record.residents().size()), lx, ly, 0xFFFFFF);
			ly += 14;
			for (BlockPos lp : points)
			{
				// 右侧列不侵入居民列表区
				if (ly >= RESIDENTS_TOP - 8) break;
				String occupant = occupantAt(record, lp);
				gfx.drawString(font, occupant == null
						? Component.translatable(P + "livingPointFree")
						: Component.translatable(P + "livingPointOccupant", occupant),
						lx, ly, 0xCCCCCC);
				ly += 14;
			}
		}

		// 居民列表（每行一个，与驱逐按钮对齐）
		gfx.drawString(font, Component.translatable(P + "residentsLabel"), x, RESIDENTS_TOP, 0xFFFFFF);
		int ry = RESIDENTS_TOP + ROW_H;
		if (record.residents().isEmpty())
		{
			gfx.drawString(font, Component.translatable(P + "none"), x, ry, 0xAAAAAA);
		}
		else
		{
			for (Resident r : record.residents())
			{
				gfx.drawString(font, Component.literal(r.name()), x, ry, 0xCCCCCC);
				ry += ROW_H;
			}
		}
	}

	// 该生活点（列）的住户名，空闲返回null
	private static String occupantAt(ControlBoxRecord rec, BlockPos lp)
	{
		for (Resident r : rec.residents())
		{
			if (r.x() == lp.getX() && r.z() == lp.getZ()) return r.name();
		}
		return null;
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
