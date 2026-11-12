package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ConfigureIlsPayload(boolean active, float glideSlopeDegrees) implements CustomPacketPayload {
    public static final Type<ConfigureIlsPayload> TYPE = new Type<>(FlightLine.id("configure_ils"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureIlsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ConfigureIlsPayload::active,
            ByteBufCodecs.FLOAT, ConfigureIlsPayload::glideSlopeDegrees,
            ConfigureIlsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
