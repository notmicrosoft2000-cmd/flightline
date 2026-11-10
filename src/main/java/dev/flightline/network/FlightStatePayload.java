package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlightStatePayload(boolean enabled, int phase, float speed, float throttle, float rotationSpeed,
                                 float velocityX, float velocityY, float velocityZ)
        implements CustomPacketPayload {
    public static final Type<FlightStatePayload> TYPE = new Type<>(FlightLine.id("flight_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, FlightStatePayload::enabled,
            ByteBufCodecs.VAR_INT, FlightStatePayload::phase,
            ByteBufCodecs.FLOAT, FlightStatePayload::speed,
            ByteBufCodecs.FLOAT, FlightStatePayload::throttle,
            ByteBufCodecs.FLOAT, FlightStatePayload::rotationSpeed,
            ByteBufCodecs.FLOAT, FlightStatePayload::velocityX,
            ByteBufCodecs.FLOAT, FlightStatePayload::velocityY,
            ByteBufCodecs.FLOAT, FlightStatePayload::velocityZ,
            FlightStatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
