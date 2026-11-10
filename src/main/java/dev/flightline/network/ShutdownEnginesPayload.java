package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ShutdownEnginesPayload() implements CustomPacketPayload {
    public static final Type<ShutdownEnginesPayload> TYPE = new Type<>(FlightLine.id("shutdown_engines"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShutdownEnginesPayload> CODEC =
            StreamCodec.unit(new ShutdownEnginesPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
