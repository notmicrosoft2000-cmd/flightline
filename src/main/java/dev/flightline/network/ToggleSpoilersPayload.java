package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleSpoilersPayload() implements CustomPacketPayload {
    public static final Type<ToggleSpoilersPayload> TYPE = new Type<>(FlightLine.id("toggle_spoilers"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleSpoilersPayload> CODEC =
            StreamCodec.unit(new ToggleSpoilersPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
