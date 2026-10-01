package com.wenzai.neosim.client.gui.config;

import com.wenzai.neosim.schematic.BlueprintConverter;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

// 蓝图格式转换（城市信息 GUI 的「转换」页）。
// 只管一个目录：<gamedir>/NeoSim/Buildings 下的 .txt；旁边已经有同名 .litematic 的（= 转换过的）
// 不再列出，所以转完一份就从列表里消失，不会重复转。
// 页面结构跟配置页一样：路径行 / 状态行 / 列表 / 作者框 / 底部两行按钮，右下角留给「关闭」按钮。
public class ConvertPanel
{
	private static final String P = "gui.neosim.cityinfo.convert.";

	// 布局：路径行 / 状态行 / 选中信息 / 列表 / 作者框 / 底部两行（底部偏移与 ConfigPanel 一致）
	private static final int PATH_Y = 58;
	private static final int STATUS_Y = 72;
	private static final int INFO_Y = 88;
	private static final int LIST_TOP = 106;
	private static final int ROW_H = 18;
	private static final int ROW_BTN_H = 16;
	private static final int EDGE = 24;
	private static final int ACTION_Y_OFFSET = 46;
	private static final int PAGE_Y_OFFSET = 24;

	private ConfigPanel.Host host;

	private final List<BlueprintConverter.Source> rows = new ArrayList<>();
	private int page;
	private int rowsPerPage = 6;
	private int selected = -1;

	// build() 期间置位：EditBox.setValue 会触发 responder，重建过程中不能再次 rebuild
	private boolean building;

	private EditBox authorBox;
	private String authorDraft = "";

	// 后台线程只写这几个值，主线程读；完成后回主线程重载蓝图库
	private volatile boolean running;
	private volatile int doneCount;
	private volatile int totalCount;
	@Nullable
	private volatile String messageKey;
	private volatile Object[] messageArgs = new Object[0];
	@Nullable
	private volatile String detail;

	// ---- Host 调用 ----

	public void build(ConfigPanel.Host host)
	{
		this.host = host;
		this.building = true;

		rescan();

		rowsPerPage = Math.max(2, (host.height() - LIST_TOP - 76) / ROW_H);
		buildList(host);
		if (selected >= 0) buildAuthorBox(host);
		buildActionRows(host);

		this.building = false;
	}

	// 每次重建都重扫目录：文件是外部增删的，扫描很便宜（每份只读两行）。
	// 选中项按文件名找回，所以翻页/重建不会丢选中。
	private void rescan()
	{
		String previous = selectedSource() != null ? selectedSource().file().getFileName().toString() : null;

		rows.clear();
		rows.addAll(BlueprintConverter.listConvertible());

		selected = -1;
		if (previous != null)
		{
			for (int i = 0; i < rows.size(); i++)
			{
				if (rows.get(i).file().getFileName().toString().equals(previous))
				{
					selected = i;
					break;
				}
			}
		}
		// 选中的文件已经转换掉/被删掉：作者框跟着收起来
		if (selected < 0) authorDraft = "";

		int pages = pageCount();
		if (page >= pages) page = Math.max(0, pages - 1);
	}

	private int pageCount()
	{
		return Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
	}

	// 列表：一页 rowsPerPage 行，行为按钮（选中行置灰表示当前选中）
	private void buildList(ConfigPanel.Host host)
	{
		int listSizeW = host.width() - EDGE * 2;
		int start = page * rowsPerPage;

		for (int i = 0; i < rowsPerPage; i++)
		{
			int index = start + i;
			if (index >= rows.size()) break;

			BlueprintConverter.Source source = rows.get(index);
			Button row = Button.builder(rowLabel(source), b ->
					{
						selected = index;
						// 换选中：作者框跟着换成该文件自己的 AU（没有则玩家名）
						authorDraft = prefillAuthor(source);
						host.rebuild();
					})
					.pos(EDGE, LIST_TOP + i * ROW_H)
					.size(listSizeW, ROW_BTN_H)
					.build();
			row.active = index != selected;
			host.add(row);
		}
	}

	private Component rowLabel(BlueprintConverter.Source source)
	{
		String author = source.author() != null
				? source.author() : Component.translatable(P + "authorNone").getString();
		String note = source.problem() != null ? " · " + source.problem() : "";
		return Component.translatable(P + "row", source.fileName(), source.dimensions(), author,
				byteText(source.sizeBytes()) + note);
	}

	// 作者框：预填该文件自己的 AU=，没有就填玩家名；清空后由核心按 AU=/Unknown 兜底
	private void buildAuthorBox(ConfigPanel.Host host)
	{
		int inputPosY = host.height() - ACTION_Y_OFFSET - 24;
		int labelSizeW = authorLabelWidth(host.width());
		int inputSizeW = authorBoxWidth(host.width());

		authorBox = new EditBox(host.font(), EDGE + labelSizeW, inputPosY, inputSizeW, 16,
				Component.translatable(P + "author"));
		authorBox.setMaxLength(48);
		authorBox.setValue(authorDraft);
		authorBox.setResponder(text ->
		{
			// 只记草稿，不重建：重建会把正在输入的 EditBox 换成新实例（表现就是一次只能输一个字）
			authorDraft = text;
		});
		host.add(authorBox);
	}

	// 底部：第一行操作（转换 / 打开文件夹 / 刷新），第二行翻页
	private void buildActionRows(ConfigPanel.Host host)
	{
		BlueprintConverter.Source sel = selectedSource();
		boolean hasSelection = sel != null;
		int limit = host.width() - EDGE - ConfigPanel.CLOSE_RESERVE - 8;

		List<ConfigPanel.BottomAction> first = new ArrayList<>();
		first.add(new ConfigPanel.BottomAction(104, Component.translatable(P + "convertSelected"),
				hasSelection && !running, () -> convertSelected(sel)));
		first.add(new ConfigPanel.BottomAction(96, Component.translatable(P + "convertAll"),
				!rows.isEmpty() && !running, this::convertAll));
		first.add(new ConfigPanel.BottomAction(96, Component.translatable(P + "openFolder"),
				!running, this::openFolder));
		first.add(new ConfigPanel.BottomAction(72, Component.translatable(P + "rescan"),
				!running, host::rebuild));
		ConfigPanel.layoutActions(host, first, host.height() - ACTION_Y_OFFSET, limit);

		int pages = pageCount();
		List<ConfigPanel.BottomAction> second = new ArrayList<>();
		second.add(new ConfigPanel.BottomAction(64, Component.translatable(P + "prevPage"),
				page > 0 && !running, () ->
				{
					if (page > 0) page--;
					host.rebuild();
				}));
		second.add(new ConfigPanel.BottomAction(64, Component.translatable(P + "nextPage"),
				page < pages - 1 && !running, () ->
				{
					if (page < pages - 1) page++;
					host.rebuild();
				}));
		ConfigPanel.layoutActions(host, second, host.height() - PAGE_Y_OFFSET, limit);
	}

	@Nullable
	private BlueprintConverter.Source selectedSource()
	{
		return selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
	}

	// ---- 转换 ----

	private void convertSelected(@Nullable BlueprintConverter.Source source)
	{
		if (source == null) return;
		startConversion(List.of(source), true);
	}

	// 全部转换不给作者覆盖值：每份都用自己的 AU=，没有的走 Unknown
	private void convertAll()
	{
		startConversion(List.copyOf(rows), false);
	}

	private void startConversion(List<BlueprintConverter.Source> targets, boolean useBoxAuthor)
	{
		if (running || targets.isEmpty()) return;

		final String override = useBoxAuthor && authorBox != null ? authorBox.getValue() : null;
		final Minecraft mc = Minecraft.getInstance();
		final String operator = mc.player != null ? mc.player.getGameProfile().getName() : null;
		final ConfigPanel.Host current = host;

		running = true;
		doneCount = 0;
		totalCount = targets.size();
		messageKey = null;
		detail = null;

		// 立刻重建：进度行出现，按钮同时置灰
		if (current != null) current.rebuild();

		Thread worker = new Thread(() ->
		{
			int ok = 0;
			String firstError = null;
			for (BlueprintConverter.Source source : targets)
			{
				BlueprintConverter.Outcome outcome =
						BlueprintConverter.convert(source.file(), useBoxAuthor ? override : null, operator);
				if (outcome.ok()) ok++;
				else if (firstError == null) firstError = source.fileName() + " — " + outcome.error();
				doneCount++;
			}

			int failed = targets.size() - ok;
			messageKey = firstError == null ? P + "done" : P + "doneWithErrors";
			messageArgs = firstError == null ? new Object[]{ok} : new Object[]{ok, failed, firstError};
			detail = firstError;
			running = false;

			// 蓝图库回主线程重载（注册表是共享状态，别在工作线程里改）
			mc.execute(() ->
			{
				SchematicRegistry.getInstance().refreshCustom();
				if (current != null && mc.screen == current) current.rebuild();
			});
		}, "NeoSim-BlueprintConvert");
		worker.setDaemon(true);
		worker.start();
	}

	// ---- 渲染 ----

	public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick)
	{
		Font font = Minecraft.getInstance().font;
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int textMaxSizeW = screenWidth - EDGE * 2;

		gfx.drawString(font, clip(font, Component.translatable(P + "dir", dir().toAbsolutePath().toString()).getString(),
				textMaxSizeW), EDGE, PATH_Y, 0xAAAAAA);

		// 状态行：转换中显示进度，否则显示上一次结果
		if (running)
		{
			gfx.drawString(font, Component.translatable(P + "progress", doneCount, totalCount).getString(),
					EDGE, STATUS_Y, 0xFFFF55);
		}
		else if (messageKey != null)
		{
			gfx.drawString(font, Component.translatable(messageKey, messageArgs).getString(),
					EDGE, STATUS_Y, detail == null ? 0x55FF55 : 0xFFAA00);
			if (detail != null)
			{
				gfx.drawString(font, clip(font, detail, textMaxSizeW), EDGE, LIST_TOP - 12, 0xFF5555);
			}
		}
		else
		{
			gfx.drawString(font, Component.translatable(P + "hint").getString(), EDGE, STATUS_Y, 0xAAAAAA);
		}

		BlueprintConverter.Source sel = selectedSource();
		if (sel != null)
		{
			String author = sel.author() != null
					? sel.author() : Component.translatable(P + "authorNone").getString();
			gfx.drawString(font, Component.translatable(P + "info", sel.fileName(), sel.dimensions(), author,
					byteText(sel.sizeBytes())).getString(), EDGE, INFO_Y, 0xFFFFFF);
		}
		else if (rows.isEmpty())
		{
			gfx.drawString(font, Component.translatable(P + "empty").getString(), EDGE, LIST_TOP, 0xAAAAAA);
		}

		if (!rows.isEmpty())
		{
			gfx.drawString(font, Component.translatable(P + "pageInfo", page + 1, pageCount(), rows.size()).getString(),
					EDGE, LIST_TOP - 12, 0xAAAAAA);
		}

		// 作者框说明：贴着输入框右边
		if (selected >= 0)
		{
			gfx.drawString(font, Component.translatable(P + "authorHint").getString(),
					EDGE + authorLabelWidth(screenWidth) + authorBoxWidth(screenWidth) + 8,
					Minecraft.getInstance().getWindow().getGuiScaledHeight() - ACTION_Y_OFFSET - 20, 0xAAAAAA);
		}
	}

	private void openFolder()
	{
		Path dir = dir();
		try
		{
			Files.createDirectories(dir);
			Util.getPlatform().openFile(dir.toFile());
		}
		catch (IOException | RuntimeException e)
		{
			messageKey = P + "openFailed";
			messageArgs = new Object[]{String.valueOf(e.getMessage())};
			detail = String.valueOf(e.getMessage());
			if (host != null) host.rebuild();
		}
	}

	// 作者框左标签宽度
	private static int authorLabelWidth(int screenWidth)
	{
		return Math.max(40, screenWidth / 12);
	}

	// 作者框输入框宽度
	private static int authorBoxWidth(int screenWidth)
	{
		return Math.max(90, Math.min(180, screenWidth / 6));
	}

	// 自定义蓝图目录（转换核心持有，这里只是取用）
	private static Path dir()
	{
		return BlueprintConverter.customDir();
	}

	// 字节数量显示：小于 1 KB 用 B，否则保留一位小数
	private static String byteText(long bytes)
	{
		if (bytes < 1024L) return bytes + " B";
		return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0D);
	}

	// 作者框预填值：文件自己的 AU= 优先，没有就用当前玩家名（清空则交回核心按 AU=/Unknown 兜底）
	private static String prefillAuthor(BlueprintConverter.Source source)
	{
		if (source.author() != null && !source.author().isBlank()) return source.author();

		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getGameProfile().getName() : "";
	}

	// 把一行文字裁到给定宽度（路径和错误信息可能很长）
	private static String clip(Font font, String text, int maxSizeW)
	{
		if (font.width(text) <= maxSizeW) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxSizeW - font.width("…"))) + "…";
	}
}
