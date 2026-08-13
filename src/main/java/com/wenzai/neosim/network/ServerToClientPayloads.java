package com.wenzai.neosim.network;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.NeoSimClient;
import com.wenzai.neosim.client.ClientDataHolder;
import com.wenzai.neosim.storage.SimData;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

    // 定向该城市玩家通知材料短缺
    public record ResourceShortagePacket(String schematicName,
                                         String itemName,
                                         int neededCount,
                                         BlockPos constructorPos) implements CustomPacketPayload
    {
        public static final Type<ResourceShortagePacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "resource_shortage"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ResourceShortagePacket> STREAM_CODEC =
                StreamCodec.ofMember(ResourceShortagePacket::write, ResourceShortagePacket::new);

        private ResourceShortagePacket(RegistryFriendlyByteBuf buf)
        {
            this(buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readBlockPos());
        }

        private void write(RegistryFriendlyByteBuf buf)
        {
            buf.writeUtf(schematicName);
            buf.writeUtf(itemName);
            buf.writeInt(neededCount);
            buf.writeBlockPos(constructorPos);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        // 聊天栏提示缺料
        public static void handle(ResourceShortagePacket pkt, IPayloadContext ctx)
        {
            ctx.enqueueWork(() ->
            {
                if (ctx.player() != null)
                {
                    ctx.player().displayClientMessage(
                            Component.literal("§6建造 §f" + pkt.schematicName()
                                    + " §e缺少材料 §b" + pkt.itemName()), false);
                }
            });
        }
    }

    // 定向该城市玩家公告建造完工
    public record BuildingCompletePacket(String schematicName,
                                         BlockPos position) implements CustomPacketPayload
    {
        public static final Type<BuildingCompletePacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "building_complete"));

        public static final StreamCodec<RegistryFriendlyByteBuf, BuildingCompletePacket> STREAM_CODEC =
                StreamCodec.ofMember(BuildingCompletePacket::write, BuildingCompletePacket::new);

        private BuildingCompletePacket(RegistryFriendlyByteBuf buf)
        {
            this(buf.readUtf(), buf.readBlockPos());
        }

        private void write(RegistryFriendlyByteBuf buf)
        {
            buf.writeUtf(schematicName);
            buf.writeBlockPos(position);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        // 聊天栏显示完工公告
        public static void handle(BuildingCompletePacket pkt, IPayloadContext ctx)
        {
            ctx.enqueueWork(() ->
            {
                if (ctx.player() != null)
                {
                    ctx.player().displayClientMessage(
                            Component.literal("§f" + pkt.schematicName() + " §e已完工"), false);
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
}
