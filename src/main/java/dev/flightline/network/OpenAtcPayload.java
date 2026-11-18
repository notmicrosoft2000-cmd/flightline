package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record OpenAtcPayload() implements CustomPacketPayload {
    public static final Type<OpenAtcPayload> TYPE = new Type<>(FlightLineIds.id("open_atc"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAtcPayload> CODEC =
            StreamCodec.unit(new OpenAtcPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
