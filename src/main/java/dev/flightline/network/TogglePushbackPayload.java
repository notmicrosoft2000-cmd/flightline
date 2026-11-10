package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record TogglePushbackPayload() implements CustomPacketPayload {
    public static final Type<TogglePushbackPayload> TYPE = new Type<>(FlightLine.id("toggle_pushback"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TogglePushbackPayload> CODEC =
            StreamCodec.unit(new TogglePushbackPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
