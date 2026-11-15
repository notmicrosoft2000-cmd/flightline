package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SelectPlanePayload(String planeId) implements CustomPacketPayload {
    public static final Type<SelectPlanePayload> TYPE = new Type<>(FlightLine.id("select_plane"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectPlanePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SelectPlanePayload::planeId, SelectPlanePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
