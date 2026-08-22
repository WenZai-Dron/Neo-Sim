package com.wenzai.neosim.client.gui;

import com.wenzai.neosim.client.preview.FreeCamera;
import com.wenzai.neosim.client.preview.SchematicPreviewManager;
import com.wenzai.neosim.schematic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

public class PreviewAdjustGui extends Screen
{
	private static final String P = "gui.neosim.BuildingConstructor.";
	private final SchematicData selectedBuilding;
	private final BlockPos constructorPos;
	private Button buildButton;

	public PreviewAdjustGui(SchematicData selectedBuilding, BlockPos constructorPos)
	{
		super(Component.translatable(P + "previewAdjust"));
		this.selectedBuilding = selectedBuilding;
		this.constructorPos = constructorPos;
	}

	@Override
	public boolean isPauseScreen()
	{
		return false;
	}

	@Override
	protected void init()
	{
		int bw = 28;
		int bh = 20;
		int gap = 2;
		int left = 10;
		int rightCol = width - 70;

		// 操作按钮
		addButton(left, height / 2 - bh * 2, 60, bh, Component.translatable(P + "adjustUp"),
				b -> nudge(0, 1, 0));
		addButton(left, height / 2 - bh, 60, bh, Component.translatable(P + "adjustDown"),
				b -> nudge(0, -1, 0));
		addButton(left, height / 2 + gap, 60, bh, Component.translatable(P + "adjustMirror"),
				b -> mirror());
		addButton(left, height / 2 + bh + gap, 60, bh, Component.translatable(P + "adjustRotate"),
				b -> rotate());

		// 方向十字
		int baseX = rightCol;
		int baseY = height - 70;
		int cg = 12;
		int cx2 = baseX + bw;
		int cy2 = baseY + bh / 2;
		addButton(cx2 - bw / 2, cy2 - bh - cg, bw, bh, Component.translatable(P + "dirNorth"),
				b -> nudge(0, 0, -1));
		addButton(cx2 - bw / 2, cy2 + cg, bw, bh, Component.translatable(P + "dirSouth"),
				b -> nudge(0, 0, 1));
		addButton(cx2 - bw - cg, cy2 - bh / 2, bw, bh, Component.translatable(P + "dirWest"),
				b -> nudge(-1, 0, 0));
		addButton(cx2 + cg, cy2 - bh / 2, bw, bh, Component.translatable(P + "dirEast"),
				b -> nudge(1, 0, 0));

		// 居中底部
		int cx = width / 2;
		int bottomY = height - 60;
		addButton(cx - 130, bottomY, 80, 20,
				Component.translatable(P + "goBack"),
				b -> onGoBack());
		addButton(cx - 50, bottomY, 80, 20,
				Component.translatable(P + "soulOut"),
				b -> onSoulOut());
		buildButton = addButton(cx + 30, bottomY, 80, 20,
				Component.translatable(P + "buildIt"),
				b -> onBuildIt());

		// 旧版蓝图：显示"旋转方块朝向"按钮
		if (selectedBuilding.getFormat() == SchematicFormat.SIM_UKRAFT_TXT)
		{
			addButton(cx + 30, bottomY + 20, 80, 20,
					Component.translatable(P + "rotateBlockOrientation"),
					b -> rotateBlockOrientations());
		}

		// 检测碰撞，有冲突时禁用建造按钮
		updateBuildButton();
	}

	// 检查蓝图放置区域是否和已有方块冲突
	private void updateBuildButton()
	{
		if (buildButton == null || selectedBuilding == null) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null)
		{
			buildButton.active = false;
			buildButton.setMessage(Component.translatable(P + "buildIt"));
			return;
		}

		PreviewState state = SchematicPreviewManager.getInstance().getState();
		BlockPos origin = state.getOrigin();
		boolean hasCollision = false;

		int sx = selectedBuilding.getSizeX();
		int sy = selectedBuilding.getSizeY();
		int sz = selectedBuilding.getSizeZ();

		// 碰撞检测改为每4格抽样（避免每次调整全蓝图逐格扫描的卡顿；足够发现大体量冲突）
		int stride = 4;
		outer:
		for (int y = 0; y < sy; y++)
		{
			for (int z = 0; z < sz; z += stride)
			{
				for (int x = 0; x < sx; x += stride)
				{
					BlockState schemBlock = selectedBuilding.getBlockContainer().get(x, y, z);
					if (schemBlock.isAir()) continue;

					BlockPos worldPos = state.blueprintToWorld(x, y, z);
					BlockState worldBlock = mc.level.getBlockState(worldPos);

					// 植被/雪层/水视为空气，不阻碍放置
					if (!worldBlock.isAir()
							&& !(worldBlock.getBlock() instanceof BushBlock)
							&& !(worldBlock.getBlock() instanceof SnowLayerBlock)
							&& worldBlock.getBlock() != Blocks.WATER)
					{
						hasCollision = true;
						break outer;
					}
				}
			}
		}

		// 有冲突时按钮置灰，文字保持不变
		buildButton.active = !hasCollision;
		buildButton.setMessage(Component.translatable(P + "buildIt"));
	}

	@Override
	public void renderBackground(GuiGraphics gfx, int mx, int my, float pt)
	{
	}

	@Override
	public void render(GuiGraphics gfx, int mx, int my, float pt)
	{
		super.render(gfx, mx, my, pt);

		// 旧版蓝图：红色警告文字
		if (selectedBuilding.getFormat() == SchematicFormat.SIM_UKRAFT_TXT)
		{
			Component warn = Component.translatable(P + "oldBlueprintWarning");
			int cx = width / 2;
			int bottomY = height - 60;
			int btnX = cx + 30;
			int btnY = bottomY + 20;
			int tw = font.width(warn);
			int wx = Math.max(4, btnX - tw - 6);
			int wy = btnY + (20 - font.lineHeight) / 2;
			gfx.drawString(font, warn, wx, wy, 0xFF5555);
		}
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods)
	{
		if (key == 256)
		{
			SchematicPreviewManager.getInstance().cancelPreview();
			onClose();
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	@Override
	public void onClose()
	{
		if (!FreeCamera.isActive())
		{
			SchematicPreviewManager.getInstance().cancelPreview();
		}
		super.onClose();
		if (minecraft != null) minecraft.mouseHandler.grabMouse();
	}

	private void nudge(int dx, int dy, int dz)
	{
		SchematicPreviewManager.getInstance().getState().nudgeForward(dx, dy, dz);
		updateBuildButton();
	}

	private void rotate()
	{
		SchematicPreviewManager.getInstance().getState().rotate();
		updateBuildButton();
	}

	private void mirror()
	{
		SchematicPreviewManager.getInstance().getState().toggleMirror();
		updateBuildButton();
	}

	// 旋转方块朝向（只转朝向，不移动方块）
	private void rotateBlockOrientations()
	{
		if (selectedBuilding == null) return;
		LightweightBlockContainer container = selectedBuilding.getBlockContainer();
		int sx = container.getSizeX();
		int sy = container.getSizeY();
		int sz = container.getSizeZ();
		for (int y = 0; y < sy; y++)
		{
			for (int z = 0; z < sz; z++)
			{
				for (int x = 0; x < sx; x++)
				{
					BlockState s = container.get(x, y, z);
					if (s.isAir()) continue;

					// 依附方块除床外跳过；床只转朝向、不移格
					if (MaterialCalculator.isAttachedBlock(s) && !(s.getBlock() instanceof BedBlock)) continue;
					container.set(x, y, z, s.rotate(Rotation.CLOCKWISE_90));
				}
			}
		}
		container.invalidateCache();

		// 强制重建幽灵预览VBO与GUI预览网格
		SchematicPreviewManager.getInstance().getState().markNeedsRebuild();
		BuildingPreviewRenderer.release();
		updateBuildButton();
	}

	private void onGoBack()
	{
		SchematicPreviewManager.getInstance().cancelPreview();
		if (minecraft != null)
		{
			minecraft.setScreen(new BuildingConstructorGui(constructorPos, selectedBuilding));
		}
	}

	private void onSoulOut()
	{
		FreeCamera.enter();
		if (minecraft != null)
		{
			minecraft.setScreen(null);
		}
	}

	private void onBuildIt()
	{
		SchematicPreviewManager.getInstance().confirmPlacement();
		if (minecraft != null)
		{
			minecraft.setScreen(null);
			minecraft.mouseHandler.grabMouse();
		}
	}

	private Button addButton(int x, int y, int w, int h, Component label, Button.OnPress action)
	{
		return addRenderableWidget(Button.builder(label, action)
				.pos(x, y).size(w, h).build());
	}
}
