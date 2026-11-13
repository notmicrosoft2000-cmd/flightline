package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ConfigureIlsPayload(boolean active, float glideSlopeDegrees, int thresholdX, int thresholdZ,
                                  float runwayHeadingDegrees) implements CustomPacketPayload {
    public ConfigureIlsPayload(boolean active, float glideSlopeDegrees) {
        this(active, glideSlopeDegrees, 0, 0, 0.0f);
    }

    public static final Type<ConfigureIlsPayload> TYPE = new Type<>(FlightLine.id("configure_ils"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureIlsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ConfigureIlsPayload::active,
            ByteBufCodecs.FLOAT, ConfigureIlsPayload::glideSlopeDegrees,
            ByteBufCodecs.VAR_INT, ConfigureIlsPayload::thresholdX,
            ByteBufCodecs.VAR_INT, ConfigureIlsPayload::thresholdZ,
            ByteBufCodecs.FLOAT, ConfigureIlsPayload::runwayHeadingDegrees,
            ConfigureIlsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
