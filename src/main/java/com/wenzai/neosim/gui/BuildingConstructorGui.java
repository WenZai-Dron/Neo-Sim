package com.wenzai.neosim.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class BuildingConstructorGui extends Screen
{
    private int currentPage = 0;

    // 状态信息（暂时写死，后续接入数据）
    private String currentStatus = "Idle";
    private String buildingType = "Not chosen yet";

    public BuildingConstructorGui()
    {
        super(Component.translatable("gui.neosim.BuildingConstructor.title"));
    }

    @Override
    protected void init()
    {
        showPage();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // 标题
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.neosim.BuildingConstructor.title"), centerX, this.height / 16, 0xFFFFFF);

        // 状态信息
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.neosim.BuildingConstructor.status", currentStatus),
                centerX, this.height / 9, 0xAAFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.neosim.BuildingConstructor.buildingType", buildingType),
                centerX, this.height / 7, 0xAAFFFF);

        // 页面提示
        if (currentPage == 0)
        {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.neosim.BuildingConstructor.page0.hint"),
                    centerX, this.height * 3 / 8, 0xFFFFAA);
        }
        else if (currentPage == 1)
        {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.neosim.BuildingConstructor.page1.hint"),
                    centerX, this.height * 3 / 8, 0xFFFFAA);
        }
    }

    // GuiBuildingConstructor.showPage()
    private void showPage()
    {
        this.clearWidgets();

        int centerX = this.width / 2;
        int btnH = this.height / 13;

        if (currentPage == 0)
        {
            int btnW = this.width / 4;

            // 主菜单页
            // 雇佣工人/炒了XXX
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.hireFireWorker"), Button-> {
                // 没写
            })
                    .pos(centerX - this.width * 3 / 8, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());

            // 选择建筑
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.chooseBuilding"), Button-> {
                // 没写
            })
                    .pos(centerX - this.width / 8, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());

            // 建筑预览
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.buildingPreview"), Button-> {
                // 没写
            })
                    .pos(centerX + this.width / 8, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());

            // 继续/暂停
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.continuePause"), Button-> {
                // 没写
            })
                    .pos(centerX - this.width * 3 / 8, this.height * 5 / 8 + btnH)
                    .size(btnW, btnH)
                    .build());

            // 选择规划
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.choosePlan"), Button-> {
                // 没写
            })
                    .pos(centerX - this.width / 8, this.height * 5 / 8 + btnH)
                    .size(btnW, btnH)
                    .build());

            // 移动建筑
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.moveBuilding"), Button-> {
                // 没写
            })
                    .pos(centerX + this.width / 8, this.height * 5 / 8 + btnH)
                    .size(btnW, btnH)
                    .build());
        }
        else if (currentPage == 1)
        {
            int btnW = this.width * 5 / 24;

            // 选择建筑类型页
            // Residential → id=5
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.typeResidential"), Button-> {
                // 住宅列表
            })
                    .pos(centerX - this.width * 5 / 12, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());

            // Commercial → id=6
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.typeCommercial"), Button-> {
                // 商业列表
            })
                    .pos(centerX - this.width * 5 / 24, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());

            // Industrial → id=7
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.typeIndustrial"), Button-> {
                // 工业列表
            })
                    .pos(centerX, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());

            // Other → id=8
            this.addRenderableWidget(Button.builder(Component.translatable("gui.neosim.BuildingConstructor.typeOther"), Button-> {
                // 其他列表
            })
                    .pos(centerX + this.width * 5 / 24, this.height * 5 / 8)
                    .size(btnW, btnH)
                    .build());
        }
    }

    // 页面切换辅助方法（后续按钮事件中使用，还没写）

    @SuppressWarnings("unused")
    private void goToPage(int page)
    {
        this.currentPage = page;
        showPage();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
