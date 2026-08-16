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
	private ServerToClientPayloads() {}

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
		public Type<? extends CustomPacketPayload> type() { return TYPE; }

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
		public Type<? extends CustomPacketPayload> type() { return TYPE; }

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
		public Type<? extends CustomPacketPayload> type() { return TYPE; }

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
}
