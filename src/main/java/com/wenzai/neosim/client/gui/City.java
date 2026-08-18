package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.network.ClientToServerPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class City extends Screen
{
	private static final int MAX_CITY_BUTTONS = 10;

	private int mode = 0;

	private EditBox inputBox;
	private Button buttonConfirm;
	private Button buttonChoose;

	// 单机城市列表缓存（init 时计算一次；渲染每帧不再 Files.list 扫盘；选择页提供手动刷新按钮）
	private List<String> cachedCities = null;

	public City()
	{
		super(Component.translatable("gui.neosim.city.title"));
	}

	@Override
	protected void init()
	{
		if (mode == 0)
		{
			initMainMenu();
		}
		else if (mode == 1)
		{
			initAddMode();
		}
		else
		{
			initChooseMode();
		}
	}

	// 主菜单：新增城市/选择城市
	private void initMainMenu()
	{
		int btnW = this.width / 3 + this.width / 7;
		int btnH = this.height / 13;
		int btnX = this.width / 2 - btnW / 2;

		Button buttonAdd = Button.builder(Component.translatable("gui.neosim.city.add"), btn -> {
			mode = 1;
			rebuildWidgets();
		})
				.pos(btnX, this.height / 2 - this.height / 7)
				.size(btnW, btnH)
				.build();
		this.addRenderableWidget(buttonAdd);

		buttonChoose = Button.builder(Component.translatable("gui.neosim.city.choose"), btn -> {
			mode = 2;
			rebuildWidgets();
		})
				.pos(btnX, this.height / 2 + this.height / 7)
				.size(btnW, btnH)
				.build();
		this.addRenderableWidget(buttonChoose);
	}

	// 新增城市
	private void initAddMode()
	{
		int inputW = this.width / 3;
		int inputH = this.height / 13;
		int btnW = this.width * 5 / 24;
		int btnH = this.height / 13;
		int centerX = this.width / 2;
		int centerY = this.height / 2;

		inputBox = new EditBox(this.font, centerX - inputW / 2, centerY - inputH * 3 / 2,
				inputW, inputH, Component.translatable("gui.neosim.city.title"));
		inputBox.setMaxLength(50);
		this.addRenderableWidget(inputBox);

		buttonConfirm = Button.builder(Component.translatable("gui.neosim.city.buttonConfirm"), btn -> {
			onConfirm();
		})
				.pos(centerX - btnW / 2, centerY)
				.size(btnW, btnH)
				.build();
		this.addRenderableWidget(buttonConfirm);
	}

	// 选择城市
	private void initChooseMode()
	{
		List<String> cities = getCachedCities();

		// 联机：城市列表向服务器请求（仅首次；响应到达后 applyCityList 重建，避免请求-响应循环）
		if (!Minecraft.getInstance().hasSingleplayerServer() && serverCities == null)
		{
			PacketDistributor.sendToServer(new ClientToServerPayloads.CityListRequestPayload());
		}

		int chooseBtnW = this.width / 3 + this.width / 7;
		int chooseBtnH = this.height / 13;
		int chooseBtnX = this.width / 2 - chooseBtnW / 2;
		int totalHeight = Math.min(cities.size(), MAX_CITY_BUTTONS) * (chooseBtnH + 5);
		int startY = this.height / 2 - totalHeight / 2;

		for (int i = 0; i < Math.min(cities.size(), MAX_CITY_BUTTONS); i++)
		{
			String cityName = cities.get(i);
			int btnY = startY + i * (chooseBtnH + 5);
			Button cityButton = Button.builder(Component.literal(cityName), btn -> {
				onSelectCity(cityName);
			})
					.pos(chooseBtnX, btnY)
					.size(chooseBtnW, chooseBtnH)
					.build();
			this.addRenderableWidget(cityButton);
		}

		// 手动刷新按钮（重新扫盘并重建列表）
		Button refreshBtn = Button.builder(Component.translatable("gui.neosim.city.refresh"), btn -> {
			cachedCities = null;
			rebuildWidgets();
		})
				.pos(chooseBtnX, startY + Math.min(cities.size(), MAX_CITY_BUTTONS) * (chooseBtnH + 5) + 8)
				.size(chooseBtnW, chooseBtnH)
				.build();
		this.addRenderableWidget(refreshBtn);
	}

	// 确认新增城市：统一发包，由服务端建目录/写档案/生成首个市民（模式为全服全局，Run 已发）
	private void onConfirm()
	{
		String cityName = inputBox.getValue().trim();
		if (!cityName.isEmpty())
		{
			PacketDistributor.sendToServer(new ClientToServerPayloads.CreateCityPayload(cityName));
		}
		onClose();
	}

	// 选择已有城市：统一发包，由服务端写入 player.json 并同步数据
	private void onSelectCity(String cityName)
	{
		PacketDistributor.sendToServer(new ClientToServerPayloads.JoinCityPayload(cityName));
		onClose();
	}

	// 列出已有城市：单机直接扫本地目录（结果缓存，仅在缓存为空/手动刷新时重扫）；联机用服务器列表
	private static List<String> listCities()
	{
		Minecraft mc = Minecraft.getInstance();
		if (!mc.hasSingleplayerServer())
		{
			return serverCities != null ? serverCities : List.of();
		}

		List<String> cities = new ArrayList<>();
		Path dataDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("data");
		if (!Files.isDirectory(dataDir)) return cities;

		String saveName = mc.getSingleplayerServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent().getFileName().toString();
		Path searchDir = dataDir.resolve(saveName);
		if (!Files.isDirectory(searchDir)) return cities;

		try (var entries = Files.list(searchDir))
		{
			for (Path entry : entries.toList())
			{
				if (Files.isDirectory(entry) && Files.exists(entry.resolve("npc")))
				{
					cities.add(entry.getFileName().toString());
				}
			}
		}
		catch (Exception e)
		{
			return cities;
		}
		return cities;
	}

	// 带缓存的单机城市列表（首次调用扫盘，之后复用；手动刷新按钮置空重扫）
	private List<String> getCachedCities()
	{
		if (cachedCities == null)
		{
			cachedCities = listCities();
		}
		return cachedCities;
	}

	// 联机：收到服务器城市列表后刷新"选择城市"页
	private static List<String> serverCities = null;

	public void applyCityList(List<String> cities)
	{
		serverCities = cities;
		if (mode == 2)
		{
			rebuildWidgets();
		}
	}

	// Esc回到主菜单
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (keyCode == 256)
		{
			if (mode != 0)
			{
				mode = 0;
				rebuildWidgets();
			}
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// 禁止通过Esc关闭界面
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

		// 无城市时，选择按钮不可点击（走缓存，不再每帧 Files.list）
		if (mode == 0 && buttonChoose != null)
		{
			buttonChoose.active = !getCachedCities().isEmpty();
		}

		// 输入框无文字时，确认按钮不可点击
		if (mode == 1 && buttonConfirm != null)
		{
			buttonConfirm.active = !inputBox.getValue().trim().isEmpty();
		}

		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}
}
