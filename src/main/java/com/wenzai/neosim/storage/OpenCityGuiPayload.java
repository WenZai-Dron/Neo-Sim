// 服务端→客户端：通知客户端打开City

package com.wenzai.neosim.storage;

import com.wenzai.neosim.NeoSim;
import com.wenzai.neosim.NeoSimClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record OpenCityGuiPayload() implements CustomPacketPayload
{
    public static final Type<OpenCityGuiPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NeoSim.MOD_ID, "open_city_gui"));

    public static final StreamCodec<ByteBuf, OpenCityGuiPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenCityGuiPayload());

    public static void handle(OpenCityGuiPayload payload, IPayloadContext context)
    {
        context.enqueueWork(() -> {
            NeoSimClient.scheduleOpenCityGui();
            NeoSim.LOGGER.debug("NeoSim-OpenCityGui(S→C): Success");
        }).exceptionally(e -> {
            NeoSim.LOGGER.error("NeoSim-OpenCityGuiPayload: Fail", e);
            return null;
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}
