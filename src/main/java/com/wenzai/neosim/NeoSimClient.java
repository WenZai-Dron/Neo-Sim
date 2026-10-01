package com.wenzai.neosim;

import com.mojang.blaze3d.platform.InputConstants;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.client.gui.City;
import com.wenzai.neosim.client.gui.CityInfoGui;
import com.wenzai.neosim.client.gui.NPC;
import com.wenzai.neosim.client.gui.Run;
import com.wenzai.neosim.client.render.MarkerBeamRenderer;
import com.wenzai.neosim.client.render.Model;
import com.wenzai.neosim.client.render.Renderer;
import com.wenzai.neosim.network.ClientToServerPayloads;
import com.wenzai.neosim.network.ServerToClientPayloads;
import com.wenzai.neosim.npc.Entity;
import com.wenzai.neosim.schematic.SchematicRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

// 此类不会在专用服务器上加载。从这里访问客户端代码是安全的。
@Mod(value = NeoSim.MOD_ID, dist = Dist.CLIENT)
// 你可以使用 EventBusSubscriber 自动注册类中所有带有 @SubscribeEvent 注解的静态方法
@EventBusSubscriber(modid = NeoSim.MOD_ID, value = Dist.CLIENT)
public class NeoSimClient
{
	private static int openGuiTimer = -1;
	private static ServerToClientPayloads.OpenGuiPayload.GuiType pendingGuiType = null;

	// 检测存档切换，用于重置客户端缓存数据
	private static ClientLevel lastLevel = null;

	// 城市信息 GUI 唯一入口：按 I 打开 / 再按 I 关闭（可在原版按键设置里改键）
	private static final KeyMapping KEY_CITY_INFO = new KeyMapping(
			"key.neosim.cityinfo",
			KeyConflictContext.IN_GAME,
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_I,
			"key.categories.neo_sim");

	// 内容表热重载计时（每 100 tick 检查 NeoSim/Json/）
	private static int contentCheckTimer = 0;

	// 已上报给服务端的界面语言（变化时重发，用于新建 NPC 选择姓名池）
	private static String reportedLanguage = "";

	// 按键注册：显式挂到 mod 事件总线（不依赖 @EventBusSubscriber 的总线推断），只注册一次
	static void registerKeyMappings(RegisterKeyMappingsEvent event)
	{
		event.register(KEY_CITY_INFO);
	}

	public NeoSimClient(IEventBus modEventBus, ModContainer container)
	{
		// 允许 NeoForge 为本模组的配置创建配置界面。
		// 可通过“模组界面 > 点击你的模组 > 点击配置”访问该配置界面。
		// 别忘了在 en_us.json 文件中为你的配置项添加翻译。
		container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

		// 城市信息 GUI 按键（I）：走 mod 事件总线显式注册，只注册一次
		modEventBus.addListener(NeoSimClient::registerKeyMappings);
	}

	@SubscribeEvent
	static void onClientSetup(FMLClientSetupEvent event)
	{
		// 一些客户端初始化代码
		NeoSim.LOGGER.info("HELLO FROM CLIENT SETUP");
		NeoSim.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

		// 预热模组作物注册表（客户端）：GUI 模组作物栏依赖检测结果
		com.wenzai.neosim.compat.crops.CropRegistry.all();

		// 界面个人设置：首启落盘模板，之后由内容表热重载统一刷新
		com.wenzai.neosim.client.ui.UiSettings.load();

		// 投影"纯色层"用的 1×1 纯白纹理：提前在渲染线程注册，避免第一次预览时现建
		event.enqueueWork(() -> com.wenzai.neosim.client.ui.WhiteTexture.location());

		SchematicRegistry.getInstance().initializeAsync();
	}

	// 注册实体渲染器
	@SubscribeEvent
	static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
	{
		event.registerEntityRenderer(Entity.NPC.get(), Renderer::new);
	}

	// 注册模型（宽臂和细臂）
	@SubscribeEvent
	static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event)
	{
		event.registerLayerDefinition(Renderer.LAYER, Model::createBodyLayer);
		event.registerLayerDefinition(Renderer.SLIM_LAYER, Model::createSlimBodyLayer);
	}

	public static void scheduleOpenGui(ServerToClientPayloads.OpenGuiPayload.GuiType guiType)
	{
		openGuiTimer = 200;
		pendingGuiType = guiType;
	}

	public static int getOpenGuiTimer()
	{
		return openGuiTimer;
	}

	@SubscribeEvent
	static void onKeyInput(InputEvent.Key event)
	{
		if (event.getKey() == GLFW.GLFW_KEY_GRAVE_ACCENT && event.getAction() == GLFW.GLFW_PRESS)
		{
			if (openGuiTimer > 0)
			{
				openGuiTimer = Math.max(1, openGuiTimer - 20);
			}
		}
	}

	@SubscribeEvent
	static void onClientTick(ClientTickEvent.Post event)
	{
		// 检测存档切换：level引用变化时重置客户端缓存
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != lastLevel)
		{
			lastLevel = mc.level;
			if (mc.level == null)
			{
				ClientDataHolder.getInstance().reset();
				Model.clearCache();
				MarkerBeamRenderer.clear();
			}
		}

		// 语言上报：进入世界后以及游戏语言被改动时，把当前语言告知服务端
		if (mc.level == null || mc.player == null || mc.getConnection() == null)
		{
			reportedLanguage = "";
		}
		else
		{
			String language = mc.getLanguageManager().getSelected();
			if (!language.equals(reportedLanguage))
			{
				reportedLanguage = language;
				PacketDistributor.sendToServer(new ClientToServerPayloads.ClientLocalePayload(language));
			}
		}

		// 城市信息 GUI：唯一入口就是 I 键
		while (KEY_CITY_INFO.consumeClick())
		{
			if (mc.screen instanceof CityInfoGui)
			{
				mc.setScreen(null);
			}
			else if (mc.screen == null && mc.level != null && mc.player != null)
			{
				mc.setScreen(new CityInfoGui());
			}
		}

		// 内容表热重载：客户端同样需要（预览/蓝图旋转与建造共用同一张表）
		contentCheckTimer++;
		if (contentCheckTimer >= 100)
		{
			contentCheckTimer = 0;
			com.wenzai.neosim.json.ContentReloader.reloadIfChanged();
		}

		if (openGuiTimer > 0)
		{
			// 玩家打开菜单时暂停倒计时，关闭菜单后继续
			if (mc.screen != null)
			{
				return;
			}

			openGuiTimer--;
			if (openGuiTimer == 0)
			{
				if (mc.player != null && mc.level != null && pendingGuiType != null)
				{
					switch (pendingGuiType)
					{
						case RUN -> mc.setScreen(new Run());
						case CITY -> mc.setScreen(new City());
					}
				}
				openGuiTimer = -1;
				pendingGuiType = null;
			}
		}
	}

	// 右键NPC时打开GUI，同时发送冻结包
	@SubscribeEvent
	static void onEntityInteract(PlayerInteractEvent.EntityInteract event)
	{
		if (event.getTarget() instanceof Entity npc)
		{
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.level != null)
			{
				mc.execute(() -> mc.setScreen(new NPC(npc)));
				PacketDistributor.sendToServer(new ClientToServerPayloads.FreezeNpcPayload(npc.getId(), true));
				event.setCancellationResult(InteractionResult.SUCCESS);
				event.setCanceled(true);
			}
		}
	}
}
