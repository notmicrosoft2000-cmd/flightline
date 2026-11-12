package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleSpoilersPayload(boolean deployed) implements CustomPacketPayload {
    public static final Type<ToggleSpoilersPayload> TYPE = new Type<>(FlightLine.id("toggle_spoilers"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleSpoilersPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, ToggleSpoilersPayload::deployed, ToggleSpoilersPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
