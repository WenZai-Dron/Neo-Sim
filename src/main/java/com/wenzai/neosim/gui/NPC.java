package com.wenzai.neosim.gui;

import com.wenzai.neosim.npc.Entity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NPC extends Screen
{
    private final Entity npc;
    private String currentPage = "main";
    private EditBox inputBox;
    private Button buttonConfirmRename;
    private int skinPage = 0;
    private static final int SKINS_PER_PAGE = 8;
    private String[] currentSkinList;
    private int btnW, btnH;

    public NPC(Entity npc)
    {
        super(Component.translatable("gui.neosim.npc.title"));
        this.npc = npc;
    }

    // 初始化
    @Override
    protected void init()
    {
        btnW = this.width / 3;
        btnH = this.height / 13;
        showPage();
    }

    private void showPage()
    {
        this.clearWidgets();
        inputBox = null;
        buttonConfirmRename = null;

        switch (currentPage)
        {
            case "main"     -> buildMainPage();
            case "setSkin"  -> buildSetSkinPage();
            case "rename"   -> buildRenamePage();
        }
    }

    // 主页面
    private void buildMainPage()
    {
        int rightX = this.width - btnW - this.width / 12;
        int startY = this.height / 8;
        int gap = btnH + 6;

        // 设置皮肤
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.setSkin"), btn -> {
                    currentPage = "setSkin";
                    skinPage = 0;
                    refreshSkinList();
                    showPage();
                })
                .pos(rightX, startY)
                .size(btnW, btnH)
                .build());

        // 重命名
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.rename"), btn -> {
                    currentPage = "rename";
                    showPage();
                })
                .pos(rightX, startY + gap)
                .size(btnW, btnH)
                .build());
    }

    // 皮肤选择页面
    private void buildSetSkinPage()
    {
        int rightX = this.width - btnW - this.width / 12;
        int startY = this.height / 8;
        int gap = btnH + 4;

        if (currentSkinList == null)
        {
            refreshSkinList();
        }

        int totalPages = (currentSkinList.length + SKINS_PER_PAGE - 1) / SKINS_PER_PAGE;

        // 皮肤按钮
        for (int i = 0; i < SKINS_PER_PAGE; i++)
        {
            int idx = skinPage * SKINS_PER_PAGE + i;
            if (idx >= currentSkinList.length) break;

            String skinPath = currentSkinList[idx];
            String skinName = skinPath.substring(skinPath.lastIndexOf('/') + 1);

            this.addRenderableWidget(Button.builder(
                    Component.literal(skinName), btn -> {
                        npc.setSkin(skinPath);
                        currentPage = "main";
                        showPage();
                    })
                    .pos(rightX, startY + gap * i)
                    .size(btnW, btnH)
                    .build());
        }

        int navY = startY + gap * SKINS_PER_PAGE;
        int navBtnW = btnW / 2 - 2;

        // 上一页
        if (skinPage > 0)
        {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.neosim.npc.skin.prevPage"), btn -> {
                        skinPage--;
                        showPage();
                    })
                    .pos(rightX, navY)
                    .size(navBtnW, btnH)
                    .build());
        }

        // 下一页
        if (skinPage < totalPages - 1)
        {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.neosim.npc.skin.nextPage"), btn -> {
                        skinPage++;
                        showPage();
                    })
                    .pos(rightX + navBtnW + 4, navY)
                    .size(navBtnW, btnH)
                    .build());
        }

        // 返回
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.back"), btn -> {
                    currentPage = "main";
                    showPage();
                })
                .pos(rightX, navY + btnH + 4)
                .size(btnW, btnH)
                .build());
    }

    // 重命名页面
    private void buildRenamePage()
    {
        int rightX = this.width - btnW - this.width / 12;
        int startY = this.height / 8;
        int inputW = btnW;

        // 文本输入框，预填当前姓名
        inputBox = new EditBox(
                this.font,
                rightX, startY,
                inputW, btnH,
                Component.translatable("gui.neosim.npc.rename.input"));
        inputBox.setMaxLength(50);
        inputBox.setValue(npc.getNpcName());
        this.addRenderableWidget(inputBox);

        int smallBtnW = btnW / 2 - 4;

        // 确认
        buttonConfirmRename = Button.builder(
                Component.translatable("gui.neosim.npc.rename.confirm"), btn -> {
                    String newName = inputBox.getValue().trim();
                    if (!newName.isEmpty())
                    {
                        npc.setNpcName(newName);
                    }
                    currentPage = "main";
                    showPage();
                })
                .pos(rightX, startY + btnH + 6)
                .size(smallBtnW, btnH)
                .build();
        this.addRenderableWidget(buttonConfirmRename);

        // 取消
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.rename.cancel"), btn -> {
                    currentPage = "main";
                    showPage();
                })
                .pos(rightX + smallBtnW + 4, startY + btnH + 6)
                .size(smallBtnW, btnH)
                .build());
    }

    // 渲染
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 按钮等组件
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 信息
        renderNpcInfo(guiGraphics);

        // 输入框无文字时禁用确认
        if ("rename".equals(currentPage) && buttonConfirmRename != null && inputBox != null)
        {
            buttonConfirmRename.active = !inputBox.getValue().trim().isEmpty();
        }
    }

    // 信息
    private void renderNpcInfo(GuiGraphics guiGraphics)
    {
        int leftX = this.width / 12;
        int startY = this.height / 8;
        int lineH = this.height / 14;
        int color = 0xFFFFFF;
        int dimColor = 0xAAAAAA;

        // 姓名（金色）
        guiGraphics.drawString(this.font,
                Component.literal(npc.getNpcName()),
                leftX, startY, 0xFFD700);

        // 性别
        String sexDisplay = "male".equals(npc.getSex())
                ? Component.translatable("gui.neosim.npc.info.male").getString()
                : Component.translatable("gui.neosim.npc.info.female").getString();
        guiGraphics.drawString(this.font,
                Component.translatable("gui.neosim.npc.info.sex", sexDisplay),
                leftX, startY + lineH, color);

        // 城市
        String city = npc.getCityName();
        if (city.isEmpty()) city = Component.translatable("gui.neosim.npc.info.noCity").getString();
        guiGraphics.drawString(this.font,
                Component.translatable("gui.neosim.npc.info.city", city),
                leftX, startY + lineH * 2, color);

        // 生命值
        guiGraphics.drawString(this.font,
                Component.translatable("gui.neosim.npc.info.health",
                        String.format("%.0f", npc.getHealth()),
                        String.format("%.0f", npc.getMaxHealth())),
                leftX, startY + lineH * 3, color);

    }

    // 输入处理
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        // Esc
        if (keyCode == 256)
        {
            if (!"main".equals(currentPage))
            {
                currentPage = "main";
                showPage();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    // 辅助方法

    private void refreshSkinList()
    {
        String sex = npc.getSex();
        if (!"male".equals(sex) && !"female".equals(sex))
        {
            sex = "female";
        }
        currentSkinList = getSkinFiles(sex);
    }

    private static String[] getSkinFiles(String sex)
    {
        String[] maleSkins = {
                "skins/male/achr1d.png", "skins/male/daycrime.png", "skins/male/gohanssj.png",
                "skins/male/kazvran.png", "skins/male/nocqnameponer.png", "skins/male/peaq.png",
                "skins/male/poishii.png", "skins/male/radwool.png", "skins/male/theezku.png",
                "skins/male/whuz.png"
        };
        String[] femaleSkins = {
                "skins/female/anya03.png", "skins/female/b0mbies.png", "skins/female/blazerhack.png",
                "skins/female/fearlicia.png", "skins/female/kajikasu.png", "skins/female/khristinatina.png",
                "skins/female/lunatique.png", "skins/female/mewlee.png", "skins/female/osukaari.png",
                "skins/female/prueli.png"
        };
        return "male".equals(sex) ? maleSkins : femaleSkins;
    }
}
