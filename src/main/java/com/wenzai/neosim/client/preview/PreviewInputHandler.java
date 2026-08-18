package com.wenzai.neosim.client.preview;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.schematic.PreviewState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

// 预览模式
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class PreviewInputHandler
{
	@SubscribeEvent
	public static void onKey(InputEvent.Key event)
	{
		SchematicPreviewManager mgr = SchematicPreviewManager.getInstance();
		if (!mgr.getState().isActive()) return;
		if (event.getAction() != GLFW.GLFW_PRESS) return;

		PreviewState state = mgr.getState();
		int key = event.getKey();

		switch (key)
		{
			case GLFW.GLFW_KEY_UP    -> state.nudgeForward(0, 0, 1);
			case GLFW.GLFW_KEY_DOWN  -> state.nudgeForward(0, 0, -1);
			case GLFW.GLFW_KEY_LEFT  -> state.nudgeForward(-1, 0, 0);
			case GLFW.GLFW_KEY_RIGHT -> state.nudgeForward(1, 0, 0);
			case GLFW.GLFW_KEY_ENTER ->
			{
				if (FreeCamera.isActive())
				{
					mgr.confirmPlacement();
					FreeCamera.exit();
				}
			}
			case GLFW.GLFW_KEY_ESCAPE ->
			{
				if (FreeCamera.isActive())
				{
					FreeCamera.returnToGui();
				}
				else
				{
					mgr.cancelPreview();
				}
			}
		}
	}
}
