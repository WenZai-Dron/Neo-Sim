package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.client.BuildingNameLocalizer;
import com.wenzai.neosim.client.preview.SchematicPreviewManager;
import com.wenzai.neosim.schematic.BuildingType;
import com.wenzai.neosim.schematic.MaterialCalculator;
import com.wenzai.neosim.schematic.SchematicData;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

public class BuildingConstructorGui extends Screen
{
    private static final int ROW_H = 84;
    private static final int COLS = 3;
    private static final int ROWS = 2;
    private static final int PER_PAGE = COLS * ROWS;

    private static final String P = "gui.neosim.BuildingConstructor.";

    private int currentPage = 0;
    private int previousPage = 0;

    private BuildingType selectedType = null;
    private SchematicData selectedBuilding = null;

    private int buildingOffset = 0;
    private int buildingsOnPage = 0;

    private final BlockPos constructorPos;
    private static final java.util.Map<BlockPos, String> WORKER_MAP = com.wenzai.neosim.NeoSim.WORKER_MAP;
    
    // 缓存的已选蓝图
    private static final java.util.Map<BlockPos, String> SELECTED_BUILDING = new java.util.concurrent.ConcurrentHashMap<>();

    public static String getWorkerAt(BlockPos pos) { return WORKER_MAP.get(pos); }
    public static void clearWorkerAt(BlockPos pos) { WORKER_MAP.remove(pos); }
    public static void clearSelectedAt(BlockPos pos) { SELECTED_BUILDING.remove(pos); }
    private String assignedWorker = null;
    private com.wenzai.neosim.building.ConstructionTask activeTask = null;
    private EditBox searchField;
    private List<SchematicData> currentBlueprints = List.of();
    
    // 格式筛选
    private com.wenzai.neosim.schematic.SchematicFormat selectedFormat;

    // "特定格式"展开状态
    private boolean formatFilterExpanded;

    // 搜索模式：false=按建筑名搜索，true=按作者搜索
    private boolean searchByAuthor;

    public BuildingConstructorGui(BlockPos constructorPos)
    {
        super(Component.translatable(P + "title"));
        this.constructorPos = constructorPos;
        this.assignedWorker = WORKER_MAP.get(constructorPos);
        
        // 恢复跨会话的已选蓝图
        String selected = SELECTED_BUILDING.get(constructorPos);
        if (selected != null)
        {
            selectedBuilding = SchematicRegistry.getInstance().get(selected);
        }
    }

    // 从预览返回到需求页
    public BuildingConstructorGui(BlockPos constructorPos, SchematicData building)
    {
        this(constructorPos);
        this.selectedBuilding = building;
        this.currentPage = 6;
        this.previousPage = 2;
    }

    // 生命周期
    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() { showPage(); }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float pt)
    {
        renderBackground(gfx, mx, my, pt);
        super.render(gfx, mx, my, pt);
        drawHeader(gfx);
    }

    @Override
    public boolean charTyped(char c, int m)
    {
        if (searchField != null && searchField.isFocused())
        {
            return searchField.charTyped(c, m);
        }
        return super.charTyped(c, m);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods)
    {
        if (searchField != null && searchField.isFocused())
        {
            if (searchField.keyPressed(key, scan, mods))
            {
                return true;
            }
        }
        if (key == 256)
        {
            if (currentPage == 0)
            {
                onClose();
            }
            else if (currentPage == 1)
            {
                currentPage = 0;
                showPage();
            }
            else if (currentPage >= 2 && currentPage <= 5)
            {
                currentPage = 1;
                showPage();
            }
            else if (currentPage == 6)
            {
                currentPage = previousPage;
                showPage();
            }
            else if (currentPage == 8)
            {
                currentPage = 0;
                showPage();
            }
            else if (currentPage == 9)
            {
                currentPage = 1;
                showPage();
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // 首页
    private void drawHeader(GuiGraphics gfx)
    {
        gfx.drawCenteredString(font, Component.translatable(P + "title"), width / 2, 10, 0xFFFFFF);
        gfx.drawCenteredString(font, Component.translatable(P + "statusReady"), width / 2, 22, 0xAAFFFF);

        switch (currentPage)
        {
            case 0 -> gfx.drawCenteredString(font, Component.translatable(P + "page0.hint"),
                    width / 2, 100, 0xFFFFAA);
            case 1 -> gfx.drawCenteredString(font, Component.translatable(P + "page1.hint"),
                    width / 2, 80, 0xFFFFAA);
            case 2 -> gfx.drawCenteredString(font, Component.translatable(P + "page2.hint"),
                    width / 2, 45, 0xFFFFAA);
            case 3 -> gfx.drawCenteredString(font, Component.translatable(P + "page3.hint"),
                    width / 2, 45, 0xFFFFAA);
            case 4 -> gfx.drawCenteredString(font, Component.translatable(P + "page4.hint"),
                    width / 2, 45, 0xFFFFAA);
            case 5 -> gfx.drawCenteredString(font, Component.translatable(P + "page5.hint"),
                    width / 2, 45, 0xFFFFAA);
            case 6 ->
            {
                if (selectedBuilding != null)
                {
                    gfx.drawString(font,
                            Component.translatable(P + "page6.hint",
                                    BuildingNameLocalizer.localize(selectedBuilding.getName())),
                            10, 45, 0xFFFFAA);
                    drawRequirements(gfx);
                }
            }
            case 8 -> gfx.drawCenteredString(font, Component.translatable(P + "page8.hint"),
                    width / 2, 45, 0xFFFFAA);
            case 9 -> gfx.drawCenteredString(font, Component.translatable(P + "page9.hint"),
                    width / 2, 45, 0xFFFFAA);
        }

        if (currentPage == 8)
        {
            drawStatus(gfx);
        }
    }

    private void showPage()
    {
        clearWidgets();

        switch (currentPage)
        {
            case 0 -> showMainMenu();
            case 1 -> showTypeSelection();
            case 2 -> showBlueprintList(BuildingType.RESIDENTIAL);
            case 3 -> showBlueprintList(BuildingType.COMMERCIAL);
            case 4 -> showBlueprintList(BuildingType.INDUSTRIAL);
            case 5 -> showBlueprintList(BuildingType.OTHER);
            case 6 -> showRequirementsPage();
            case 7 -> showNpcList();
            case 8 -> showStatusPage();
            case 9 -> showBlueprintList(BuildingType.CUSTOM);
        }
    }

    private void showMainMenu()
    {
        // 按控制盒坐标查找模盒的建造任务
        if (activeTask == null)
        {
            activeTask = com.wenzai.neosim.building.ConstructionEngine.findTask(constructorPos);
        }

        // 检查任务是否完成
        if (activeTask != null && activeTask.getState() == com.wenzai.neosim.building.BuildingInstance.BuildState.COMPLETE)
        {
            activeTask = null;
        }

        int btnW = width / 4;
        int btnH = height / 13;
        int cx = width / 2;
        int row1Y = height * 5 / 8;
        int row2Y = row1Y + btnH;

        Component hireLabel = assignedWorker != null
                ? Component.translatable(P + "fireWorker", assignedWorker)
                : Component.translatable(P + "hireBuilder");
        addButton(1, cx - width * 3 / 8, row1Y, btnW, btnH,
                hireLabel,
                b ->
                {
                    if (assignedWorker != null)
                    {
                        releaseNpcFromSite(assignedWorker);
                        WORKER_MAP.remove(constructorPos);
                        assignedWorker = null;
                        showPage();
                    }
                    else
                    {
                        currentPage = 7;
                        showPage();
                    }
                });
        addButton(2, cx - width / 8, row1Y, btnW, btnH,
                Component.translatable(P + "chooseBuilding"),
                b ->
                {
                    currentPage = 1;
                    showPage();
                });
        Button statusBtn = addButton(3, cx + width / 8, row1Y, btnW, btnH,
                Component.translatable(P + "currentStatus"), b ->
                {
                    currentPage = 8;
                    showPage();
                });
        statusBtn.active = selectedBuilding != null || assignedWorker != null || activeTask != null;

        boolean hasTask = activeTask != null && activeTask.getState() != com.wenzai.neosim.building.BuildingInstance.BuildState.COMPLETE;
        Component cpLabel = !hasTask ? Component.translatable(P + "pause") : (activeTask.isPaused() ? Component.translatable(P + "continue") : Component.translatable(P + "pause"));
        Button cpBtn = addButton(4, cx - width * 3 / 8, row2Y, btnW, btnH,
                cpLabel,
                b ->
                {
                    if (activeTask != null)
                    {
                        if (activeTask.isPaused()) activeTask.resume();
                        else activeTask.pause();
                        showPage();
                    }
                });
        cpBtn.active = hasTask;
        addButton(5, cx - width / 8, row2Y, btnW, btnH,
                Component.translatable(P + "choosePlan"), b -> { /* Phase 5 */ });
        addButton(6, cx + width / 8, row2Y, btnW, btnH,
                Component.translatable(P + "moveBuilding"), b -> { /* Phase 5 */ });
    }

    private void showTypeSelection()
    {
        int btnW = width * 5 / 24;
        int btnH = height / 13;
        int cx = width / 2;
        int y = height * 5 / 8;

        addButton(7, cx - width * 5 / 12, y, btnW, btnH,
                Component.translatable(P + "typeResidential"),
                b ->
                {
                    currentPage = 2;
                    selectedType = BuildingType.RESIDENTIAL;
                    showPage();
                });
        addButton(8, cx - width * 5 / 24, y, btnW, btnH,
                Component.translatable(P + "typeCommercial"),
                b ->
                {
                    currentPage = 3;
                    selectedType = BuildingType.COMMERCIAL;
                    showPage();
                });
        addButton(9, cx, y, btnW, btnH,
                Component.translatable(P + "typeIndustrial"),
                b ->
                {
                    currentPage = 4;
                    selectedType = BuildingType.INDUSTRIAL;
                    showPage();
                });
        addButton(10, cx + width * 5 / 24, y, btnW, btnH,
                Component.translatable(P + "typeOther"),
                b ->
                {
                    currentPage = 5;
                    selectedType = BuildingType.OTHER;
                    showPage();
                });

        addButton(11, cx - btnW / 2, y + btnH + 6, btnW, btnH,
                Component.translatable(P + "typeCustom"),
                b ->
                {
                    currentPage = 9;
                    selectedType = BuildingType.CUSTOM;
                    showPage();
                });
    }

    private void showBlueprintList(BuildingType type)
    {
        // 切换建筑类型时重置翻页
        if (selectedType != type) buildingOffset = 0;
        selectedType = type;

        // 搜索模式切换按钮
        addButton(604, width / 2 - 137, height - 40, 60, 20,
                Component.translatable(P + (searchByAuthor ? "searchModeAuthor" : "searchModeBuilding")),
                b -> { searchByAuthor = !searchByAuthor; refreshBlueprintButtons(); });

        // 翻页/返回后不丢失已有搜索词
        String existing = searchField != null ? searchField.getValue() : "";
        searchField = new EditBox(font, width / 2 - 75, height - 40, 150, 20,
                Component.translatable(P + "search"));
        searchField.setMaxLength(20);
        searchField.setValue(existing);
        searchField.setFocused(true);
        
        searchField.setResponder(text -> refreshBlueprintButtons());
        addRenderableWidget(searchField);

        buildBlueprintButtons();

        buildFormatButtons();
    }

    private void buildFormatButtons()
    {
        int fy = height - 40;
        int fx = width / 2 + 80;
        int bw = 70;

        // 展开时隐藏"特定格式"按钮
        if (!formatFilterExpanded)
        {
            addButton(600, fx, fy, bw, 20,
                    Component.translatable(P + "filterSpecific"),
                    b -> { formatFilterExpanded = true; showPage(); });
        }
        else
        {
            Button all = addButton(601, fx, fy, bw, 20,
                    Component.translatable(P + "filterAll"),
                    b -> { selectedFormat = null; formatFilterExpanded = false; showPage(); });
            all.active = selectedFormat != null;

            Button txt = addButton(602, fx, fy - 22, bw, 20,
                    Component.translatable(P + "filterTxt"),
                    b -> { selectedFormat = com.wenzai.neosim.schematic.SchematicFormat.SIM_UKRAFT_TXT; formatFilterExpanded = false; showPage(); });
            txt.active = selectedFormat != com.wenzai.neosim.schematic.SchematicFormat.SIM_UKRAFT_TXT;

            Button lit = addButton(603, fx, fy - 44, bw, 20,
                    Component.translatable(P + "filterLitematic"),
                    b -> { selectedFormat = com.wenzai.neosim.schematic.SchematicFormat.LITEMATICA; formatFilterExpanded = false; showPage(); });
            lit.active = selectedFormat != com.wenzai.neosim.schematic.SchematicFormat.LITEMATICA;
        }
    }

    private void refreshBlueprintButtons()
    {
        String text = searchField != null ? searchField.getValue() : "";
        boolean focused = searchField != null && searchField.isFocused();
        buildingOffset = 0;

        clearWidgets();
        addButton(604, width / 2 - 137, height - 40, 60, 20,
                Component.translatable(P + (searchByAuthor ? "searchModeAuthor" : "searchModeBuilding")),
                b -> { searchByAuthor = !searchByAuthor; refreshBlueprintButtons(); });
        searchField = new EditBox(font, width / 2 - 75, height - 40, 150, 20,
                Component.translatable(P + "search"));
        searchField.setMaxLength(20);
        
        searchField.setValue(text);
        searchField.setFocused(focused);
        searchField.setResponder(t -> refreshBlueprintButtons());
        addRenderableWidget(searchField);

        buildBlueprintButtons();

        buildFormatButtons();
    }

    private void buildBlueprintButtons()
    {
        String query = searchField != null ? searchField.getValue().trim() : "";
        SchematicRegistry reg = SchematicRegistry.getInstance();
        
        // 自定义页打开时先重扫目录，玩家新增/删除蓝图无需重启
        if (selectedType == BuildingType.CUSTOM)
        {
            reg.refreshCustom();
        }
        currentBlueprints = reg.getByType(selectedType);

        if (!query.isEmpty())
        {
            String lower = query.toLowerCase();
            // 按搜索模式过滤：建筑=名称（英文名或本地化中文名都匹配），作者=作者
            currentBlueprints = currentBlueprints.stream()
                    .filter(d -> searchByAuthor
                            ? d.getAuthor().toLowerCase().contains(lower)
                            : (d.getName().toLowerCase().contains(lower)
                                || BuildingNameLocalizer.localize(d.getName()).toLowerCase().contains(lower)))
                    .toList();
        }

        // 格式筛选
        if (selectedFormat != null)
        {
            currentBlueprints = currentBlueprints.stream()
                    .filter(d -> d.getFormat() == selectedFormat)
                    .toList();
        }

        int colW = (width - 20) / COLS;
        int x = 5, y = 60, idx = 1;
        int perRow = 0;
        buildingsOnPage = 0;

        for (int i = buildingOffset; i < currentBlueprints.size() && buildingsOnPage < PER_PAGE; i++)
        {
            SchematicData data = currentBlueprints.get(i);
            String author = data.hasKnownAuthor() ? data.getAuthor()
                    : Component.translatable(P + "unknownAuthor").getString();

            double cost = data.getTotalSolidBlocks() * com.wenzai.neosim.Config.CREDITS_PER_BLOCK.get();
            Button bpBtn = addButton(idx, x, y, colW, 20,
                    Component.literal(BuildingNameLocalizer.localize(data.getName())),
                    b -> onBlueprintPicked(data));
            
            // 资金不足时禁用蓝图选择
            bpBtn.active = canAfford(cost);
            addButton(idx + 200, x, y + 19, colW, 14,
                    Component.literal(data.getDimensionString()), null).active = false;

            addButton(idx + 250, x, y + 32, colW, 14,
                    Component.translatable(P + "cost", String.format("%.2f", cost)), null).active = false;
            addButton(idx + 300, x, y + 45, colW, 14,
                    Component.translatable(P + "blocks", data.getTotalSolidBlocks()), null).active = false;
            addButton(idx + 400, x, y + 58, colW, 14,
                    Component.literal(author), null).active = false;

            x += colW;
            idx++;
            buildingsOnPage++;
            perRow++;

            if (perRow >= COLS)
            {
                x = 5;
                y += ROW_H;
                perRow = 0;
            }
        }

        // 自定义页：在最后一个蓝图按钮之后的空位添加"添加"按钮
        if (selectedType == BuildingType.CUSTOM && buildingsOnPage < PER_PAGE)
        {
            addButton(700, x, y, colW, 20,
                    Component.translatable(P + "add"),
                    b -> openCustomFolder());
        }

        if (buildingOffset > 0)
        {
            addButton(500, 5, height - 20, 75, 20,
                    Component.translatable(P + "prevPage"),
                    b ->
                    {
                        buildingOffset = Math.max(0, buildingOffset - PER_PAGE);
                        showPage();
                    });
        }
        if (buildingOffset + buildingsOnPage < currentBlueprints.size())
        {
            addButton(501, width - 80, height - 20, 75, 20,
                    Component.translatable(P + "nextPage"),
                    b ->
                    {
                        buildingOffset += buildingsOnPage;
                        showPage();
                    });
        }
    }

    private void onBlueprintPicked(SchematicData data)
    {
        previousPage = currentPage;
        selectedBuilding = data;
        SELECTED_BUILDING.put(constructorPos, data.getName());
        currentPage = 6;
        showPage();
    }

    // 打开自定义蓝图目录
    private void openCustomFolder()
    {
        try
        {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("NeoSim").resolve("Buildings");
            if (!java.nio.file.Files.exists(dir))
            {
                java.nio.file.Files.createDirectories(dir);
            }

            switch (net.minecraft.Util.getPlatform())
            {
                case WINDOWS ->
                        Runtime.getRuntime().exec(new String[]{"explorer.exe", dir.toString()});
                case OSX ->
                        Runtime.getRuntime().exec(new String[]{"open", dir.toString()});
                case LINUX, SOLARIS, UNKNOWN ->
                        Runtime.getRuntime().exec(new String[]{"xdg-open", dir.toString()});
            }
        }
        catch (Exception e)
        {
            com.mojang.logging.LogUtils.getLogger().error(
                    "NeoSim-GUI: failed to open custom buildings folder — {}", e.getMessage());
        }
    }

    // 资金是否足够
    private boolean canAfford(double cost)
    {
        if (com.wenzai.neosim.client.ClientDataHolder.getInstance().getMode() == 2)
        {
            return true;
        }
        return com.wenzai.neosim.client.ClientDataHolder.getInstance().getCredit() >= cost;
    }

    private void showRequirementsPage()
    {
        if (selectedBuilding == null) return;
        addButton(1001, width / 2 - 100, height - 25, 100, 20,
                Component.translatable(P + "goBack"),
                b ->
                {
                    currentPage = previousPage;
                    showPage();
                });
        addButton(1000, width / 2, height - 25, 100, 20,
                Component.translatable(P + "preview"),
                b -> onPreview());
    }

    private void showStatusPage()
    {
        addButton(1100, width / 2 - 50, height - 25, 100, 20,
                Component.translatable(P + "goBack"),
                b ->
                {
                    currentPage = 0;
                    showPage();
                });
    }

    // 当前状态页绘制
    private void drawStatus(GuiGraphics gfx)
    {
        int x = 10;
        int y = 60;

        // 目标建筑
        gfx.drawString(font, Component.translatable(P + "statusBuilding"),
                x, y, 0xFFFFFF);
        y += 14;
        if (selectedBuilding != null)
        {
            gfx.drawString(font, BuildingNameLocalizer.localize(selectedBuilding.getName()),
                    x + 20, y, 0xCCCCCC);
        }
        else if (activeTask != null)
        {
            // 任务存在时显示任务建筑
            gfx.drawString(font, activeTask.getBuilding().getSchematicName(), x + 20, y, 0xCCCCCC);
        }
        else
        {
            gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
        }
        y += 24;

        // 所选建筑师
        gfx.drawString(font, Component.translatable(P + "statusBuilder"),
                x, y, 0xFFFFFF);
        y += 14;
        String worker = assignedWorker != null ? assignedWorker
                : (activeTask != null && activeTask.getBuilding().getBuilderName() != null
                ? activeTask.getBuilding().getBuilderName()
                : null);
        if (worker != null)
        {
            gfx.drawString(font, worker, x + 20, y, 0xCCCCCC);
        }
        else
        {
            gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
        }
        y += 24;

        // 当前建造状态
        gfx.drawString(font, Component.translatable(P + "statusState"),
                x, y, 0xFFFFFF);
        y += 14;
        if (activeTask != null)
        {
            String stateName = switch (activeTask.getState())
            {
                case IDLE -> "statusState.idle";
                case WAITING_FOR_WORKER -> "statusState.waitingWorker";
                case WORKER_ASSIGNED -> "statusState.workerAssigned";
                case LOADING_BLUEPRINT -> "statusState.loading";
                case WAITING_FOR_RESOURCES -> "statusState.waiting";
                case BUILDING -> "statusState.building";
                case COMPLETE -> "statusState.complete";
            };
            gfx.drawString(font, Component.translatable(P + stateName), x + 20, y, 0xCCCCCC);
            y += 14;

            // 建造进度
            int progress = activeTask.getProgress();
            int total = activeTask.getTotal();
            gfx.drawString(font, Component.translatable(P + "statusProgress", progress, total),
                    x + 20, y, 0xCCCCCC);
            y += 24;
        }
        else
        {
            gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
            y += 24;
        }

        // 在状态显示服务端缓存的缺料
        gfx.drawString(font, Component.translatable(P + "statusMaterials"),
                x, y, 0xFFFFFF);
        y += 14;
        if (activeTask != null
                && activeTask.getState() == com.wenzai.neosim.building.BuildingInstance.BuildState.WAITING_FOR_RESOURCES)
        {
            net.minecraft.world.item.Item missing = activeTask.getLastMissingMaterial();
            if (missing != null)
            {
                gfx.drawString(font, missing.getDescription(), x + 20, y, 0xCCCCCC);
            }
            else
            {
                gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
            }
        }
        else
        {
            gfx.drawString(font, Component.translatable(P + "statusNone"), x + 20, y, 0xAAAAAA);
        }
    }

    private void drawRequirements(GuiGraphics gfx)
    {
        SchematicData data = selectedBuilding;
        if (data == null) return;

        int x = 10;
        int y = 60;
        gfx.drawString(font, Component.translatable(P + "dimensions", data.getDimensionString()),
                x, y, 0xFFFFFF);
        y += 14;

        // 费用
        double cost = data.getTotalSolidBlocks() * com.wenzai.neosim.Config.CREDITS_PER_BLOCK.get();
        gfx.drawString(font, Component.translatable(P + "cost", String.format("%.2f", cost)),
                x, y, 0xFFFFFF);
        y += 14;
        gfx.drawString(font, Component.translatable(P + "totalBlocks", data.getTotalSolidBlocks()),
                x, y, 0xFFFFFF);
        y += 20;

        // 材料需求
        List<MaterialCalculator.MaterialEntry> materials = MaterialCalculator.calculate(data);
        if (materials.isEmpty())
        {
            gfx.drawString(font, Component.translatable(P + "noMaterials"), x, y, 0xAAAAAA);
        }
        else
        {
            gfx.drawString(font, Component.translatable(P + "materials", materials.size()),
                    x, y, 0xFFFFFF);
            y += 14;

            int maxShow = (height - y - 100) / 13;
            for (int i = 0; i < Math.min(materials.size(), maxShow); i++)
            {
                MaterialCalculator.MaterialEntry e = materials.get(i);
                String name = e.item.getDescription().getString();
                gfx.drawString(font, name, x, y, 0xCCCCCC);
                gfx.drawString(font, e.formatted(), x + 180, y, 0xCCCCCC);
                y += 13;
            }
            if (materials.size() > maxShow)
            {
                gfx.drawString(font, Component.translatable(P + "moreTypes", materials.size() - maxShow),
                        x, y, 0xFFFFFF);
                y += 13;
            }
        }

    }

    private void onPreview()
    {
        if (selectedBuilding != null)
        {
            SchematicPreviewManager.getInstance().enterPreview(selectedBuilding, constructorPos);
            if (minecraft != null)
            {
                minecraft.setScreen(new PreviewAdjustGui(selectedBuilding, constructorPos));
            }
        }
    }

    private void showNpcList()
    {
        if (minecraft == null) return;

        int colW = (width - 20) / COLS;
        int x = 5, y = 60, idx = 1;

        // 从城市NPC文件读取
        String cityName = com.wenzai.neosim.storage.ModSavedData.getActiveCityName();
        List<String> npcNames = List.of();
        String saveName = "";
        if (!cityName.isEmpty())
        {
            if (minecraft.getSingleplayerServer() != null)
                saveName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
            if (!saveName.isEmpty())
                npcNames = com.wenzai.neosim.storage.NpcData.listNpcNames(cityName, saveName);
            else
                npcNames = com.wenzai.neosim.storage.NpcData.listNpcNames(cityName);
        }

        for (String name : npcNames)
        {
            // 读取NPC数据获取等级、年龄与产假状态
            int level = 1;
            int age = -1;
            boolean onMaternityLeave = false;

            try
            {
                com.google.gson.JsonObject json;
                if (!saveName.isEmpty())
                    json = com.wenzai.neosim.storage.NpcData.load(name, cityName, saveName);
                else
                    json = com.wenzai.neosim.storage.NpcData.load(name, cityName);

                if (json != null)
                {
                    if (json.has("job"))
                    {
                        com.google.gson.JsonObject job = json.getAsJsonObject("job");
                        if (job.has("architect"))
                            level = job.get("architect").getAsInt();
                    }
                    if (json.has("age"))
                        age = json.get("age").getAsInt();
                    
                    // 产假：孕期不可雇佣
                    if (json.has("pregnancy"))
                        onMaternityLeave = json.get("pregnancy").getAsFloat() > 0.0F;
                }
            }
            catch (Exception ignored) {}

            // 已被其他模盒雇佣的NPC不可再雇佣
            boolean hiredElsewhere = WORKER_MAP.entrySet().stream()
                    .anyMatch(e -> !e.getKey().equals(constructorPos) && e.getValue().equals(name));
            
            // 未成年不可雇佣
            boolean underage = age >= 0 && age < com.wenzai.neosim.Config.LIFE_ADULT_AGE.get();
            Button hireBtn = addButton(idx, x, y, colW, 20, Component.literal(name),
                    b ->
                    {
                        WORKER_MAP.put(constructorPos, name);
                        assignedWorker = name;
                        assignNpcToSite(name);
                        currentPage = 0;
                        showPage();
                    });
            hireBtn.active = !hiredElsewhere && !underage && !onMaternityLeave;
            addButton(idx + 100, x, y + 22, colW, 14,
                    Component.translatable(P + "builderLevel", level), null).active = false;

            x += colW;
            idx++;
            if (x + colW > width - colW)
            {
                x = 5;
                y += ROW_H;
            }
        }

        addButton(999, width / 2 - 50, height - 25, 100, 20,
                Component.translatable(P + "goBack"),
                b ->
                {
                    currentPage = 0;
                    showPage();
                });
    }

    // 解雇NPC，恢复AI
    private void releaseNpcFromSite(String npcName)
    {
        if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
        net.minecraft.server.level.ServerLevel level = minecraft.getSingleplayerServer().overworld();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof com.wenzai.neosim.npc.Entity npc && npcName.equals(npc.getNpcName()))
            {
                npc.releaseFromSite();
                break;
            }
        }
        // 解雇后立即保存
        com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(level);
    }

    // 找到指定NPC实体并传送
    private void assignNpcToSite(String npcName)
    {
        if (minecraft == null || !minecraft.hasSingleplayerServer()) return;
        net.minecraft.server.level.ServerLevel level = minecraft.getSingleplayerServer().overworld();
        int count = 0;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities())
        {
            if (e instanceof com.wenzai.neosim.npc.Entity npc)
            {
                count++;
                if (npcName.equals(npc.getNpcName()))
                {
                    // 服务端：未成年不可雇佣
                    if (!npc.isAdult())
                    {
                        com.mojang.logging.LogUtils.getLogger().warn(
                                "NeoSim-GUI: NPC '{}' is underage (age={}), hire refused",
                                npcName, npc.getAge());
                        return;
                    }
                    
                    // 服务端：产假中不可雇佣
                    if (npc.getPregnancyStage() > 0.0F)
                    {
                        com.mojang.logging.LogUtils.getLogger().warn(
                                "NeoSim-GUI: NPC '{}' is on maternity leave, hire refused",
                                npcName);
                        return;
                    }
                    npc.assignToSite(constructorPos);

                    // 雇佣后立即保存
                    com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(level);
                    return;
                }
            }
        }
        // 未加载的NPC：在服务端线程从档案恢复并直接生成在模盒上方（GUI线程不可直接改服务端世界）
        net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null)
        {
            com.mojang.logging.LogUtils.getLogger().info(
                    "NeoSim-GUI: NPC '{}' not loaded, scheduling restore at site", npcName);
            server.execute(() ->
            {
                net.minecraft.server.level.ServerLevel serverLevel = server.overworld();
                String cityName = com.wenzai.neosim.storage.ModSavedData.getActiveCityName();
                if (cityName.isEmpty()) return;

                com.google.gson.JsonObject json =
                        com.wenzai.neosim.storage.NpcData.load(serverLevel, cityName, npcName);
                if (json == null) return;

                // 档案校验：未成年不可雇佣
                if (json.has("age")
                        && json.get("age").getAsInt() < com.wenzai.neosim.Config.LIFE_ADULT_AGE.get())
                {
                    com.mojang.logging.LogUtils.getLogger().warn(
                            "NeoSim-GUI: NPC '{}' is underage (age={}), hire refused",
                            npcName, json.get("age").getAsInt());
                    return;
                }

                // 档案校验：产假中不可雇佣
                if (json.has("pregnancy") && json.get("pregnancy").getAsFloat() > 0.0F)
                {
                    com.mojang.logging.LogUtils.getLogger().warn(
                            "NeoSim-GUI: NPC '{}' is on maternity leave, hire refused",
                            npcName);
                    return;
                }

                com.wenzai.neosim.npc.Entity npc = com.wenzai.neosim.npc.Manage.spawnSingle(
                        serverLevel, cityName, npcName, constructorPos);
                if (npc != null)
                {
                    // 已生成在模盒上方，直接分配上工
                    npc.assignToSite(constructorPos);

                    // 雇佣后立即保存
                    com.wenzai.neosim.building.ConstructionEngine.saveAllTasks(serverLevel);
                }
            });
            return;
        }
        com.mojang.logging.LogUtils.getLogger().info("NeoSim-GUI: NPC '{}' not found among {} entities", npcName, count);
    }

    private void onBuildIt()
    {
        onClose();
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
