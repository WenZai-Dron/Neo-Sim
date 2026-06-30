// 服务端→客户端：通知客户端打开Run

package com.wenzai.neosim;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record OpenRunGuiPayload() implements CustomPacketPayload
{
    public static final Type<OpenRunGuiPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "open_run_gui"));

    public static final StreamCodec<ByteBuf, OpenRunGuiPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenRunGuiPayload());

    public static void handle(OpenRunGuiPayload payload, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            NeoSimClient.scheduleOpenRunGui();
            NeoSim.LOGGER.debug("NeoSim-OpenRunGui(S→C): Success");
        }).exceptionally(e -> {
            NeoSim.LOGGER.error("NeoSim-OpenRunGuiPayload: Fail", e);
            return null;
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}
