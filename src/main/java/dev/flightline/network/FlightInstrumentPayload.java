package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlightInstrumentPayload(int radioAltitudeFeet, int verticalSpeedFpm, float bankDegrees,
                                      float pitchDegrees, float headingDegrees, int gearState, int systems, int warnings,
                                      float trafficBearingDegrees, int trafficRangeBlocks, int trafficAltitudeFeet)
        implements CustomPacketPayload {
    public static final Type<FlightInstrumentPayload> TYPE = new Type<>(FlightLine.id("flight_instruments"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightInstrumentPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::radioAltitudeFeet,
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::verticalSpeedFpm,
            ByteBufCodecs.FLOAT, FlightInstrumentPayload::bankDegrees,
            ByteBufCodecs.FLOAT, FlightInstrumentPayload::pitchDegrees,
            ByteBufCodecs.FLOAT, FlightInstrumentPayload::headingDegrees,
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::gearState,
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::systems,
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::warnings,
            ByteBufCodecs.FLOAT, FlightInstrumentPayload::trafficBearingDegrees,
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::trafficRangeBlocks,
            ByteBufCodecs.VAR_INT, FlightInstrumentPayload::trafficAltitudeFeet,
            FlightInstrumentPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
