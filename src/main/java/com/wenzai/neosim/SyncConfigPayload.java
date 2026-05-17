package com.wenzai.neosim;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record SyncConfigPayload(int mode, int singleOrMulti) implements CustomPacketPayload
{
    public static final Type<SyncConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "sync_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, SyncConfigPayload::mode,
                    ByteBufCodecs.INT, SyncConfigPayload::singleOrMulti,
                    SyncConfigPayload::new
            );

    // 服务端接收
    public static void handle(SyncConfigPayload payload, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();

            // 获取存据并更新
            ModSavedData data = ModSavedData.get(player.serverLevel());
            data.setMode(payload.mode());
            data.setSingleOrMulti(payload.singleOrMulti());

            // 日志
            NeoSim.LOGGER.info(
                    "NeoSim-SyncConfigPayload: mode={}, singleOrMulti={}", payload.mode(), payload.singleOrMulti()
            );
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}