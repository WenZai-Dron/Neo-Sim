// 客户端→服务端：仅发送 mode 字段，避免传输无关数据（后续可能会改）

package com.wenzai.neosim.storage;

import com.wenzai.neosim.NeoSim;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record UpdatePayload(byte mode) implements CustomPacketPayload
{
    public static final Type<UpdatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "update"));

    public static final StreamCodec<ByteBuf, UpdatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BYTE,
                    UpdatePayload::mode,
                    UpdatePayload::new
            );

    public static void handle(UpdatePayload payload, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            ModSavedData savedData = ModSavedData.get(player.serverLevel());
            SimData updated = savedData.getData().withMode(payload.mode());
            savedData.setData(updated, player.serverLevel());
            NeoSim.LOGGER.debug("NeoSim-handle(C→S): mode={}", payload.mode());
        }).exceptionally(e -> {
            NeoSim.LOGGER.error("NeoSim-handle： Fail", e);
            return null;
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}
