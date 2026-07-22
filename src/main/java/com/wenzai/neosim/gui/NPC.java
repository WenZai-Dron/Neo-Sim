package com.wenzai.neosim.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.storage.FreezeNpcPayload;
import com.wenzai.neosim.storage.UpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public class NPC extends Screen
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Entity npc;
    private String currentPage = "main";
    private EditBox inputSurname, inputGivenName;
    private Button buttonConfirmRename;
    private int skinPage = 0;
    private static final int SKINS_PER_PAGE = 6;
    private String[] currentSkinList;
    private int btnW, btnH;

    // 皮肤下载
    private EditBox inputPlayerName;
    private Button buttonConfirmSkinDownload;
    private String skinDownloadStatus = null;
    private boolean skinDownloading = false;

    // 3D预览
    private Entity previewEntity;
    private String previewSkin = null;
    private final Map<Button, String> skinButtonMap = new HashMap<>();

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
        inputSurname = null;
        inputGivenName = null;
        buttonConfirmRename = null;
        inputPlayerName = null;
        buttonConfirmSkinDownload = null;
        skinDownloadStatus = null;
        skinButtonMap.clear();
        previewSkin = null;

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
                Component.translatable("gui.neosim.npc.setSkin"), Button-> {
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
                Component.translatable("gui.neosim.npc.rename"), Button-> {
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
        int startY = this.height / 12;
        int gap = btnH + 4;

        // 搜索玩家皮肤
        int searchBtnW = btnW / 3;
        int searchInputW = btnW - searchBtnW - 4;

        inputPlayerName = new EditBox(
                this.font,
                rightX, startY,
                searchInputW, btnH,
                Component.translatable("gui.neosim.npc.skin.searchPlayer"));
        inputPlayerName.setMaxLength(16);
        this.addRenderableWidget(inputPlayerName);

        buttonConfirmSkinDownload = Button.builder(
                Component.translatable("gui.neosim.npc.skin.searchConfirm"), btn -> {
                    String playerName = inputPlayerName.getValue().trim();
                    if (!playerName.isEmpty() && !skinDownloading)
                    {
                        downloadAndApplySkin(playerName);
                    }
                })
                .pos(rightX + searchInputW + 4, startY)
                .size(searchBtnW, btnH)
                .build();
        this.addRenderableWidget(buttonConfirmSkinDownload);

        if (skinDownloading)
        {
            buttonConfirmSkinDownload.active = false;
            inputPlayerName.setEditable(false);
        }

        // 皮肤列表从搜索栏下方开始
        int skinStartY = startY + btnH + 16;

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
            String displayName;
            if (skinPath.startsWith("file:"))
            {
                // 文件皮肤：去掉 "file:" 前缀和 ".png" 后缀
                String name = skinPath.substring(5);
                displayName = name.substring(0, name.lastIndexOf('.'));
            }
            else
            {
                // 内置资源：从路径中提取文件名
                String fileName = skinPath.substring(skinPath.lastIndexOf('/') + 1);
                displayName = fileName.substring(0, fileName.lastIndexOf('.'));
            }

            Button skinBtn = Button.builder(
                    Component.literal(displayName), btn -> {
                        npc.setSkin(skinPath);

                        // 同步到服务端，更新文件
                        PacketDistributor.sendToServer(new UpdatePayload(npc.getId(), "", "", skinPath));
                        currentPage = "main";
                        showPage();
                    })
                    .pos(rightX, skinStartY + gap * i)
                    .size(btnW, btnH)
                    .build();
            this.addRenderableWidget(skinBtn);

            // 记录按钮到皮肤路径的映射
            skinButtonMap.put(skinBtn, skinPath);
        }

        int navY = skinStartY + gap * SKINS_PER_PAGE;
        int navBtnW = btnW / 2 - 2;

        // 上一页
        if (skinPage > 0)
        {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.neosim.npc.skin.prevPage"), Button-> {
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
                    Component.translatable("gui.neosim.npc.skin.nextPage"), Button-> {
                        skinPage++;
                        showPage();
                    })
                    .pos(rightX + navBtnW + 4, navY)
                    .size(navBtnW, btnH)
                    .build());
        }

        // 返回
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.back"), Button-> {
                    currentPage = "main";
                    showPage();
                })
                .pos(rightX, navY + btnH + 4)
                .size(btnW, btnH)
                .build());

        // 打开皮肤文件夹
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.skin.openFolder"), btn -> {
                    Path skinsDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Skins");
                    try
                    {
                        if (!Files.exists(skinsDir))
                        {
                            Files.createDirectories(skinsDir);
                        }
                        net.minecraft.Util.getPlatform().openFile(skinsDir.toFile());
                    }
                    catch (IOException e)
                    {
                        LOGGER.error("NeoSim: Failed to open skins folder", e);
                    }
                })
                .pos(rightX, navY + btnH + 4 + btnH + 4)
                .size(btnW, btnH)
                .build());
    }

    // 重命名页面
    private void buildRenamePage()
    {
        int rightX = this.width - btnW - this.width / 12;
        int startY = this.height / 8;
        int inputW = btnW / 2 - 4;

        // 姓输入框
        inputSurname = new EditBox(
                this.font,
                rightX, startY,
                inputW, btnH,
                Component.translatable("gui.neosim.npc.rename.surname"));
        inputSurname.setMaxLength(10);
        inputSurname.setValue(npc.getNpcSurname());
        inputSurname.setResponder(text -> updateConfirmButtonState());
        this.addRenderableWidget(inputSurname);

        // 名输入框
        inputGivenName = new EditBox(
                this.font,
                rightX + inputW + 4, startY,
                inputW, btnH,
                Component.translatable("gui.neosim.npc.rename.givenName"));
        inputGivenName.setMaxLength(10);
        inputGivenName.setValue(npc.getNpcGivenName());
        inputGivenName.setResponder(text -> updateConfirmButtonState());
        this.addRenderableWidget(inputGivenName);

        int smallBtnW = btnW / 2 - 4;

        // 确认
        buttonConfirmRename = Button.builder(
                Component.translatable("gui.neosim.npc.rename.confirm"), Button-> {
                    String newSurname = inputSurname.getValue().trim();
                    String newGivenName = inputGivenName.getValue().trim();
                    if (!newSurname.isEmpty() && !newGivenName.isEmpty())
                    {
                        String fullName = newSurname + newGivenName;
                        npc.setNpcName(fullName);

                        // 同步到服务端，更新文件
                        PacketDistributor.sendToServer(new UpdatePayload(npc.getId(), newSurname, newGivenName, ""));
                    }
                    currentPage = "main";
                    showPage();
                })
                .pos(rightX, startY + btnH + 6)
                .size(smallBtnW, btnH)
                .build();
        this.addRenderableWidget(buttonConfirmRename);

        // 初始化确认按钮
        updateConfirmButtonState();

        // 取消
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.neosim.npc.rename.cancel"), Button-> {
                    currentPage = "main";
                    showPage();
                })
                .pos(rightX + smallBtnW + 4, startY + btnH + 6)
                .size(smallBtnW, btnH)
                .build());
    }

    // 根据输入框内容更新确认按钮的可用状态
    private void updateConfirmButtonState()
    {
        if (buttonConfirmRename != null && inputSurname != null && inputGivenName != null)
        {
            buttonConfirmRename.active = !inputSurname.getValue().trim().isEmpty()
                    && !inputGivenName.getValue().trim().isEmpty();
        }
    }

    // 重写默认背景，实现完全透明
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 啥也不干
    }

    // 渲染
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        // 别绘背景

        // 按钮等组件不调用
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 检测皮肤按钮焦点，更新预览皮肤
        if ("setSkin".equals(currentPage))
        {
            String hoveredSkin = null;
            for (Map.Entry<Button, String> entry : skinButtonMap.entrySet())
            {
                if (entry.getKey().isHovered())
                {
                    hoveredSkin = entry.getValue();
                    break;
                }
            }
            previewSkin = hoveredSkin;

            // 渲染下载状态文字
            if (skinDownloadStatus != null)
            {
                int rightX = this.width - btnW - this.width / 12;
                int startY = this.height / 8;
                int statusY = startY + btnH + 4;
                guiGraphics.drawString(this.font, Component.literal(skinDownloadStatus),
                        rightX, statusY, 0xFF5555);
            }
        }
        else
        {
            // 非皮肤选择页面时，预览皮肤为当前皮肤
            previewSkin = npc.getSkin();
        }

        // 信息
        renderNpcInfo(guiGraphics);

        // 3D模型
        renderEntityPreview(guiGraphics, mouseX, mouseY, partialTick);

    }

    // 信息
    private void renderNpcInfo(GuiGraphics guiGraphics)
    {
        int leftX = this.width / 12;
        int startY = this.height / 8;
        int lineH = (int)(this.height / 14 * 1.2f);
        int color = 0xFFFFFF;

        // 姓名（金色）
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(leftX, startY, 0);
        guiGraphics.pose().scale(1.2f, 1.2f, 1.0f);
        guiGraphics.drawString(this.font, Component.literal(npc.getNpcName()), 0, 0, 0xFFD700);
        guiGraphics.pose().popPose();

        // 性别
        String sexDisplay = "male".equals(npc.getSex())
                ? Component.translatable("gui.neosim.npc.info.male").getString()
                : Component.translatable("gui.neosim.npc.info.female").getString();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(leftX, startY + lineH, 0);
        guiGraphics.pose().scale(1.2f, 1.2f, 1.0f);
        guiGraphics.drawString(this.font, Component.translatable("gui.neosim.npc.info.sex", sexDisplay), 0, 0, color);
        guiGraphics.pose().popPose();

        // 城市
        String city = npc.getCityName();
        if (city.isEmpty()) city = Component.translatable("gui.neosim.npc.info.noCity").getString();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(leftX, startY + lineH * 2, 0);
        guiGraphics.pose().scale(1.2f, 1.2f, 1.0f);
        guiGraphics.drawString(this.font, Component.translatable("gui.neosim.npc.info.city", city), 0, 0, color);
        guiGraphics.pose().popPose();
    }

    // 左下角模型
    private void renderEntityPreview(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 初始化预览实体
        if (previewEntity == null)
        {
            previewEntity = new Entity(Entity.NPC.get(), mc.level);
            previewEntity.setSkin(npc.getSkin());
        }

        // 更新皮肤
        String skinToShow = (previewSkin != null) ? previewSkin : npc.getSkin();
        if (!skinToShow.equals(previewEntity.getSkin()))
        {
            previewEntity.setSkin(skinToShow);
        }

        // 位置
        int previewSize = (int)(this.height * 0.22);
        int previewX = this.width / 7;
        int previewY = this.height - previewSize / 6;

        // 计算模型朝向鼠标的角度
        double dx = previewX - mouseX;
        double dy = mouseY - previewY;
        float faceAngle = (float) Math.toDegrees(Math.atan2(dx, dy));

        // 渲染
        var poseStack = guiGraphics.pose();
        poseStack.pushPose();

        poseStack.translate(previewX, previewY, 1050.0F);
        poseStack.scale(1.0F, 1.0F, -1.0F);
        poseStack.translate(0.0, 0.0, 1000.0);
        poseStack.scale(previewSize, previewSize, previewSize);

        // 面朝观察者
        Quaternionf baseRot = (new Quaternionf()).rotateZ((float)Math.PI);
        poseStack.mulPose(baseRot);

        // 根据鼠标位置调整俯仰
        float rotX = (float)Math.atan((double)((this.height / 2.0F - mouseY) / 80.0F));
        Quaternionf pitchRot = Axis.XP.rotation(rotX * 20.0F * ((float)Math.PI / 180F));
        poseStack.mulPose(pitchRot);

        // Y轴面向鼠标指针
        float yaw = (float) Math.toRadians(faceAngle);
        Quaternionf yawRot = Axis.YP.rotation(yaw);
        poseStack.mulPose(yawRot);

        // 设置光照
        RenderSystem.setupGuiFlatDiffuseLighting(
                new org.joml.Vector3f(1.0F, 1.0F, 1.0F),
                new org.joml.Vector3f(0.0F, 0.0F, 0.0F));

        // 渲染实体
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        dispatcher.render(previewEntity, 0.0, 0.0, 0.0, 0.0F, 1.0F, poseStack, bufferSource, 15728880);
        bufferSource.endBatch();
        dispatcher.setRenderShadow(true);
        RenderSystem.enableDepthTest();

        poseStack.popPose();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    // GUI关闭时解冻NPC
    @Override
    public void onClose()
    {
        PacketDistributor.sendToServer(new FreezeNpcPayload(npc.getId(), false));
        super.onClose();
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
        List<String> skinList = new ArrayList<>();

        // 内置资源
        String prefix = "skins/" + sex;
        Minecraft mc = Minecraft.getInstance();

        var resources = mc.getResourceManager()
                .listResources(prefix, path -> path.getPath().endsWith(".png"));

        if (!resources.isEmpty())
        {
            resources.keySet().stream()
                    .map(ResourceLocation::getPath)
                    .sorted()
                    .forEach(skinList::add);
        }
        else
        {
            // 资源列表为空时使用硬编码列表
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
            skinList.addAll(Arrays.asList("male".equals(sex) ? maleSkins : femaleSkins));
        }

        // 文件皮肤
        Path skinsDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Skins");
        if (Files.isDirectory(skinsDir))
        {
            try (var files = Files.list(skinsDir))
            {
                files.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".png"))
                        .sorted()
                        .map(name -> "file:" + name)
                        .forEach(skinList::add);
            }
            catch (IOException ignored)
            {
                // 读取失败则跳过
            }
        }

        return skinList.toArray(new String[0]);
    }

    // 下载并应用玩家皮肤
    private void downloadAndApplySkin(String playerName)
    {
        skinDownloading = true;
        skinDownloadStatus = Component.translatable("gui.neosim.npc.skin.searching").getString();
        buttonConfirmSkinDownload.active = false;
        inputPlayerName.setEditable(false);

        CompletableFuture.supplyAsync(() -> {
            try
            {
                return fetchSkinFromMojang(playerName);
            }
            catch (Exception e)
            {
                LOGGER.error("NeoSim-downloadSkin: Failed for player {}", playerName, e);
                return "ERROR:" + e.getMessage();
            }
        }).thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                skinDownloading = false;

                if (result.startsWith("ERROR:"))
                {
                    skinDownloadStatus = "§c" + Component.translatable(
                            "gui.neosim.npc.skin.searchFailed", result.substring(6)).getString();
                    
                    // 恢复按钮
                    if (buttonConfirmSkinDownload != null)
                    {
                        buttonConfirmSkinDownload.active = true;
                    }
                    if (inputPlayerName != null)
                    {
                        inputPlayerName.setEditable(true);
                    }
                }
                else
                {
                    // 应用皮肤
                    String skinPath = "file:" + playerName + ".png";
                    npc.setSkin(skinPath);

                    // 同步到服务端
                    PacketDistributor.sendToServer(new UpdatePayload(npc.getId(), "", "", skinPath));

                    // 刷新列表并返回主页
                    refreshSkinList();
                    currentPage = "main";
                    showPage();
                }
            });
        });
    }

    // 联网获取玩家皮肤并保存
    private static String fetchSkinFromMojang(String playerName) throws Exception
    {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 搜索用户名
        HttpRequest uuidRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                .GET()
                .build();
        HttpResponse<String> uuidResponse = client.send(uuidRequest, HttpResponse.BodyHandlers.ofString());

        if (uuidResponse.statusCode() == 204 || uuidResponse.statusCode() == 404)
        {
            throw new Exception("Player not found: " + playerName);
        }
        if (uuidResponse.statusCode() != 200)
        {
            throw new Exception("Mojang API error (HTTP " + uuidResponse.statusCode() + ")");
        }

        JsonObject uuidJson = JsonParser.parseString(uuidResponse.body()).getAsJsonObject();
        String uuid = uuidJson.get("id").getAsString();

        // 搜索皮肤
        HttpRequest profileRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                .GET()
                .build();
        HttpResponse<String> profileResponse = client.send(profileRequest, HttpResponse.BodyHandlers.ofString());

        if (profileResponse.statusCode() != 200)
        {
            throw new Exception("Profile fetch failed (HTTP " + profileResponse.statusCode() + ")");
        }

        JsonObject profileJson = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
        JsonArray properties = profileJson.getAsJsonArray("properties");

        String skinUrl = null;
        for (int i = 0; i < properties.size(); i++)
        {
            JsonObject prop = properties.get(i).getAsJsonObject();
            if ("textures".equals(prop.get("name").getAsString()))
            {
                String base64Value = prop.get("value").getAsString();
                byte[] decoded = Base64.getDecoder().decode(base64Value);
                JsonObject texturesJson = JsonParser.parseString(new String(decoded)).getAsJsonObject();
                JsonObject skinObj = texturesJson.getAsJsonObject("textures").getAsJsonObject("SKIN");
                if (skinObj != null)
                {
                    skinUrl = skinObj.get("url").getAsString();
                }
                break;
            }
        }

        if (skinUrl == null)
        {
            throw new Exception("No skin found for player: " + playerName);
        }

        // 下载皮肤
        HttpRequest skinRequest = HttpRequest.newBuilder()
                .uri(URI.create(skinUrl))
                .GET()
                .build();
        HttpResponse<byte[]> skinResponse = client.send(skinRequest, HttpResponse.BodyHandlers.ofByteArray());

        if (skinResponse.statusCode() != 200)
        {
            throw new Exception("Skin download failed (HTTP " + skinResponse.statusCode() + ")");
        }

        // 保存
        Path skinsDir = FMLPaths.GAMEDIR.get().resolve("NeoSim").resolve("Skins");
        if (!Files.exists(skinsDir))
        {
            Files.createDirectories(skinsDir);
        }
        Path skinFile = skinsDir.resolve(playerName + ".png");
        Files.write(skinFile, skinResponse.body());

        LOGGER.info("NeoSim-downloadSkin: Saved skin for player {} to {}", playerName, skinFile.toAbsolutePath());
        return skinFile.toString();
    }
}
