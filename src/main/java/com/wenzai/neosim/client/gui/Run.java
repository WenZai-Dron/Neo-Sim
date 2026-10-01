package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.network.ClientToServerPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

public class Run extends Screen
{
	private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath("neo_sim", "neo_sim_logo_run.png");

	public Run()
	{
		super(Component.translatable("gui.neosim.run.title"));
	}

	// 禁止通过Esc关闭界面
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}

	byte mode = 0;
	private Button buttonNormal, buttonCreative, buttonHardcore, buttonClose;
	private final Component tipNormal = Component.translatable("gui.neosim.run.tipNormal"),
							tipCreative = Component.translatable("gui.neosim.run.tipCreative"),
							tipHardcore = Component.translatable("gui.neosim.run.tipHardcore");

	// 初始化按钮组件
	@Override
	protected void init()
	{
		int btnPosX = this.width / 3;
		int btnSizeH = this.height / 13;

		buttonNormal = Button.builder(Component.translatable("gui.neosim.run.buttonNormal"), btn -> {
			mode = 1;
			buttonCreative.active = true;
			buttonHardcore.active = true;
			btn.active = false;
			if ( mode != 0 )
			{
				buttonClose.active = true;
			}
		})
				.pos(btnPosX, this.height / 3)
				.size(btnPosX + this.width / 24, btnSizeH)
				.build();
		this.addRenderableWidget(buttonNormal);

		buttonCreative = Button.builder(Component.translatable("gui.neosim.run.buttonCreative"), btn -> {
			mode = 2;
			buttonNormal.active = true;
			buttonHardcore.active = true;
			btn.active = false;
			if ( mode != 0 )
			{
				buttonClose.active = true;
			}
		})
				.pos(btnPosX, this.height / 2)
				.size(btnPosX + this.width / 24, btnSizeH)
				.build();
		this.addRenderableWidget(buttonCreative);

		buttonHardcore = Button.builder(Component.translatable("gui.neosim.run.buttonHardcore"), btn -> {
			mode = 3;
			buttonNormal.active = true;
			buttonCreative.active = true;
			btn.active = false;
			if ( mode != 0 )
			{
				buttonClose.active = true;
			}
		})
				.pos(btnPosX, this.height * 2 / 3)
				.size(btnPosX + this.width / 24, btnSizeH)
				.build();
		this.addRenderableWidget(buttonHardcore);

		buttonClose = Button.builder(Component.translatable("gui.neosim.run.buttonClose"), btn -> {
			PacketDistributor.sendToServer(new ClientToServerPayloads.UpdatePayload(mode));
			onClose();
			Minecraft.getInstance().setScreen(new City());
		})
				.pos(btnPosX - this.width / 48, this.height * 5 / 6)
				.size(btnPosX + this.width / 12, btnSizeH)
				.build();
		this.addRenderableWidget(buttonClose);

		buttonClose.active = false;
	}

	// 渲染组件
	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
	{
		// 渲染背景
		this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

		// 调用父类渲染，即渲染按钮
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		int textPosX = this.width / 2 - this.width / 16;
		int btnSizeH = this.height / 13;
		int textGap = this.height / 54;

		guiGraphics.drawCenteredString(this.font, tipNormal, textPosX, this.height / 3 + btnSizeH + textGap, 0xFFFFFF00);
		guiGraphics.drawCenteredString(this.font, tipCreative, textPosX - this.width / 48, this.height / 2 + btnSizeH + textGap, 0xFFFFFF00);
		guiGraphics.drawCenteredString(this.font, tipHardcore, textPosX - this.width / 96, this.height * 2 / 3 + btnSizeH + textGap, 0xFFFFFF00);

		// 渲染Logo
		guiGraphics.blit(LOGO, textPosX - this.width / 12, 3, this.width / 3, this.height * 3 / 8, 0.0F, 0.0F, 400, 250, 400, 250);
	}
}
