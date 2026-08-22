package com.wenzai.neosim.network;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.NeoSimClient;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.client.gui.ControlBoxGui;
import com.wenzai.neosim.client.gui.NPC;
import com.wenzai.neosim.life.Genealogy;
import com.wenzai.neosim.storage.SimData;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ServerToClientPayloads
{
	private ServerToClientPayloads()
	{
	}

	public record SyncDataPayload(SimData data, String cityName) implements CustomPacketPayload
	{
		public static final Type<SyncDataPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "sync_data"));

		public static final StreamCodec<ByteBuf, SyncDataPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.BYTE,         p -> p.data.mode(),
						ByteBufCodecs.SHORT,        p -> p.data.population(),
						ByteBufCodecs.VAR_INT,      p -> p.data.dayOfWeek(),
						ByteBufCodecs.VAR_INT,      p -> p.data.day(),
						ByteBufCodecs.DOUBLE,       p -> p.data.credit(),
						ByteBufCodecs.STRING_UTF8,  SyncDataPayload::cityName,
						(mode, population, dayOfWeek, day, credit, cityName) ->
								new SyncDataPayload(new SimData(mode, population, dayOfWeek, day, credit), cityName)
				);

		public static void handle(SyncDataPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() -> {
				ClientDataHolder.getInstance().updateData(payload.data(), payload.cityName());
				NeoSim.LOGGER.debug("NeoSim-SyncData(S→C): {}, cityName={}", payload.data(), payload.cityName());
			}).exceptionally(e -> {
				NeoSim.LOGGER.error("NeoSim-SyncDataPayload: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 通知客户端打开Run或City
	public record OpenGuiPayload(GuiType guiType) implements CustomPacketPayload
	{
		public enum GuiType
		{
			RUN,
			CITY
		}

		public static final Type<OpenGuiPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "open_gui"));

		public static final StreamCodec<ByteBuf, OpenGuiPayload> STREAM_CODEC = new StreamCodec<>()
		{
			@Override
			public @NotNull OpenGuiPayload decode(@NotNull ByteBuf buf)
			{
				return new OpenGuiPayload(GuiType.values()[buf.readByte()]);
			}

			@Override
			public void encode(@NotNull ByteBuf buf, @NotNull OpenGuiPayload payload)
			{
				buf.writeByte(payload.guiType().ordinal());
			}
		};

		public static void handle(OpenGuiPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() -> {
				NeoSimClient.scheduleOpenGui(payload.guiType());
				NeoSim.LOGGER.debug("NeoSim-OpenGuiPayload: Success, type={}", payload.guiType());
			}).exceptionally(e -> {
				NeoSim.LOGGER.error("NeoSim-OpenGuiPayload: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 定向该城市玩家通知材料短缺（服务端已按配置模板格式化）
	public record ResourceShortagePacket(String message) implements CustomPacketPayload
	{
		public static final Type<ResourceShortagePacket> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "resource_shortage"));

		public static final StreamCodec<ByteBuf, ResourceShortagePacket> STREAM_CODEC =
				ByteBufCodecs.STRING_UTF8.map(ResourceShortagePacket::new, ResourceShortagePacket::message);

		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}

		// 聊天栏提示缺料
		public static void handle(ResourceShortagePacket pkt, IPayloadContext ctx)
		{
			ctx.enqueueWork(() ->
			{
				if (ctx.player() != null)
				{
					ctx.player().displayClientMessage(Component.literal(pkt.message()), false);
				}
			});
		}
	}

	// 定向该城市玩家公告建造完工（服务端已按配置模板格式化）
	public record BuildingCompletePacket(String message) implements CustomPacketPayload
	{
		public static final Type<BuildingCompletePacket> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "building_complete"));

		public static final StreamCodec<ByteBuf, BuildingCompletePacket> STREAM_CODEC =
				ByteBufCodecs.STRING_UTF8.map(BuildingCompletePacket::new, BuildingCompletePacket::message);

		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}

		// 聊天栏显示完工公告
		public static void handle(BuildingCompletePacket pkt, IPayloadContext ctx)
		{
			ctx.enqueueWork(() ->
			{
				if (ctx.player() != null)
				{
					ctx.player().displayClientMessage(Component.literal(pkt.message()), false);
				}
			});
		}
	}

	// 定向该城市玩家公告整地完工（服务端已格式化）
	public record TerraformCompletePacket(String message) implements CustomPacketPayload
	{
		public static final Type<TerraformCompletePacket> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "terraform_complete"));

		public static final StreamCodec<ByteBuf, TerraformCompletePacket> STREAM_CODEC =
				ByteBufCodecs.STRING_UTF8.map(TerraformCompletePacket::new, TerraformCompletePacket::message);

		@Override
		public Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}

		// 聊天栏显示完工公告
		public static void handle(TerraformCompletePacket pkt, IPayloadContext ctx)
		{
			ctx.enqueueWork(() ->
			{
				if (ctx.player() != null)
				{
					ctx.player().displayClientMessage(Component.literal(pkt.message()), false);
				}
			});
		}
	}

	// 标记棒活动矩形同步
	public record MarkerSyncPayload(ResourceKey<Level> dim, List<List<BlockPos>> rectangles) implements CustomPacketPayload
	{
		public static final Type<MarkerSyncPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "marker_sync"));

		public static final StreamCodec<ByteBuf, MarkerSyncPayload> STREAM_CODEC =
				StreamCodec.composite(
						ResourceKey.streamCodec(Registries.DIMENSION), MarkerSyncPayload::dim,
						ByteBufCodecs.collection(ArrayList::new,
								ByteBufCodecs.collection(ArrayList::new, BlockPos.STREAM_CODEC)), MarkerSyncPayload::rectangles,
						MarkerSyncPayload::new
				);

		public static void handle(MarkerSyncPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() -> {
				com.wenzai.neosim.client.render.MarkerBeamRenderer.onSync(payload.dim(), payload.rectangles());
				NeoSim.LOGGER.debug("NeoSim-MarkerSync(S→C): dim={}, rects={}", payload.dim(), payload.rectangles());
			}).exceptionally(e -> {
				NeoSim.LOGGER.error("NeoSim-MarkerSync: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 控制箱管理动作完成通知（S→C）：客户端收到后刷新对应控制箱GUI
	public record ControlBoxAckPayload(BlockPos boxPos) implements CustomPacketPayload
	{
		public static final Type<ControlBoxAckPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "control_box_ack"));

		public static final StreamCodec<ByteBuf, ControlBoxAckPayload> STREAM_CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC,
						ControlBoxAckPayload::boxPos,
						ControlBoxAckPayload::new
				);

		public static void handle(ControlBoxAckPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (Minecraft.getInstance().screen instanceof ControlBoxGui gui
						&& gui.boxPos().equals(payload.boxPos()))
				{
					gui.refresh();
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-ControlBoxAck: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 族谱数据响应（S→C）
	public record FamilyDataPayload(String centerName, List<Genealogy.FamilyNode> nodes) implements CustomPacketPayload
	{
		public static final Type<FamilyDataPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "family_data"));

		// FamilyNode 的流编解码器
		public static final StreamCodec<ByteBuf, Genealogy.FamilyNode> NODE_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, Genealogy.FamilyNode::name,
						ByteBufCodecs.STRING_UTF8, Genealogy.FamilyNode::sex,
						ByteBufCodecs.STRING_UTF8, Genealogy.FamilyNode::partner,
						ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
								Genealogy.FamilyNode::parents,
						ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
								Genealogy.FamilyNode::children,
						Genealogy.FamilyNode::new
				);

		public static final StreamCodec<ByteBuf, FamilyDataPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8,
						FamilyDataPayload::centerName,
						ByteBufCodecs.collection(ArrayList::new, NODE_CODEC),
						FamilyDataPayload::nodes,
						FamilyDataPayload::new
				);

		public static void handle(FamilyDataPayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (Minecraft.getInstance().screen instanceof NPC gui)
				{
					gui.onFamilyData(payload.centerName(), payload.nodes());
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-FamilyData: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// ===== 城市系统（S→C，原 CityPayloads）=====

	// 服务器城市列表响应（S→C）
	public record CityListResponsePayload(List<String> cities) implements CustomPacketPayload
	{
		public static final Type<CityListResponsePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "city_list_response"));

		public static final StreamCodec<ByteBuf, CityListResponsePayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
						CityListResponsePayload::cities,
						CityListResponsePayload::new);

		public static void handle(CityListResponsePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (net.minecraft.client.Minecraft.getInstance().screen
						instanceof com.wenzai.neosim.client.gui.City gui)
				{
					gui.applyCityList(payload.cities());
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-CityListResponse: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 可雇佣市民列表响应（S→C）
	public record HireListResponsePayload(List<HireEntry> entries) implements CustomPacketPayload
	{
		public record HireEntry(String name, int level, int age, boolean maternity, boolean hiredElsewhere)
				implements CustomPacketPayload
		{
			public static final Type<HireEntry> TYPE =
					new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "hire_entry"));

			public static final StreamCodec<ByteBuf, HireEntry> STREAM_CODEC =
					StreamCodec.composite(
							ByteBufCodecs.STRING_UTF8, HireEntry::name,
							ByteBufCodecs.VAR_INT, HireEntry::level,
							ByteBufCodecs.VAR_INT, HireEntry::age,
							ByteBufCodecs.BOOL, HireEntry::maternity,
							ByteBufCodecs.BOOL, HireEntry::hiredElsewhere,
							HireEntry::new);

			@Override
			public @NotNull Type<? extends CustomPacketPayload> type()
			{
				return TYPE;
			}
		}

		public static final Type<HireListResponsePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "hire_list_response"));

		public static final StreamCodec<ByteBuf, HireListResponsePayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.collection(ArrayList::new, HireEntry.STREAM_CODEC),
						HireListResponsePayload::entries,
						HireListResponsePayload::new);

		public static void handle(HireListResponsePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc.screen instanceof com.wenzai.neosim.client.gui.HireListPanel.HostScreen host)
				{
					host.onHireList(payload.entries());
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-HireListResponse: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 雇佣状态同步（S→C；workerName 为空=已解雇），客户端更新 WORKER_MAP 缓存并刷新 GUI
	public record WorkerUpdatePayload(net.minecraft.core.BlockPos boxPos, String workerName) implements CustomPacketPayload
	{
		public static final Type<WorkerUpdatePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "worker_update"));

		public static final StreamCodec<ByteBuf, WorkerUpdatePayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.BlockPos.STREAM_CODEC, WorkerUpdatePayload::boxPos,
						ByteBufCodecs.STRING_UTF8, WorkerUpdatePayload::workerName,
						WorkerUpdatePayload::new);

		public static void handle(WorkerUpdatePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				if (payload.workerName().isEmpty())
				{
					com.wenzai.neosim.NeoSim.WORKER_MAP.remove(payload.boxPos());
				}
				else
				{
					com.wenzai.neosim.NeoSim.WORKER_MAP.put(payload.boxPos(), payload.workerName());
				}
				// 刷新打开的对应 GUI
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc.screen instanceof com.wenzai.neosim.client.gui.HireListPanel.HostScreen host)
				{
					host.onWorkerUpdate(payload.boxPos());
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-WorkerUpdate: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 缺料响应（S→C）
	public record MissingScanResponsePayload(net.minecraft.core.BlockPos boxPos,
			List<MissingEntry> entries) implements CustomPacketPayload
	{
		public record MissingEntry(net.minecraft.world.item.Item item, int count) {}

		public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, MissingEntry> ENTRY_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.registry(net.minecraft.core.registries.Registries.ITEM), MissingEntry::item,
						ByteBufCodecs.VAR_INT, MissingEntry::count,
						MissingEntry::new);

		public static final Type<MissingScanResponsePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "missing_scan_response"));

		public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, MissingScanResponsePayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.BlockPos.STREAM_CODEC, MissingScanResponsePayload::boxPos,
						ByteBufCodecs.collection(ArrayList::new, ENTRY_CODEC),
						MissingScanResponsePayload::entries,
						MissingScanResponsePayload::new);

		public static void handle(MissingScanResponsePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc.screen instanceof com.wenzai.neosim.client.gui.BuildingConstructorGui gui)
				{
					gui.applyMissingScan(payload.entries());
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-MissingScanResponse: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}

	// 无家 NPC 名单响应（S→C）
	public record HomelessListResponsePayload(List<String> names) implements CustomPacketPayload
	{
		public static final Type<HomelessListResponsePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "homeless_list_response"));

		public static final StreamCodec<ByteBuf, HomelessListResponsePayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
						HomelessListResponsePayload::names,
						HomelessListResponsePayload::new);

		public static void handle(HomelessListResponsePayload payload, IPayloadContext context)
		{
			context.enqueueWork(() ->
			{
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				if (mc.screen instanceof com.wenzai.neosim.client.gui.ControlBoxGui gui)
				{
					gui.applyHomelessList(payload.names());
				}
			}).exceptionally(e ->
			{
				NeoSim.LOGGER.error("NeoSim-HomelessListResponse: Fail", e);
				return null;
			});
		}

		@Override
		public @NotNull Type<? extends CustomPacketPayload> type()
		{
			return TYPE;
		}
	}
}
