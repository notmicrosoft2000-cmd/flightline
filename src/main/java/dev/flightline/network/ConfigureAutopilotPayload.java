package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ConfigureAutopilotPayload(boolean engaged, int targetHeading, int targetAltitudeFeet,
                                       int targetVerticalSpeedFpm, int targetAirspeedKnots)
        implements CustomPacketPayload {
    public static final Type<ConfigureAutopilotPayload> TYPE = new Type<>(FlightLine.id("configure_autopilot"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureAutopilotPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ConfigureAutopilotPayload::engaged,
            ByteBufCodecs.VAR_INT, ConfigureAutopilotPayload::targetHeading,
            ByteBufCodecs.VAR_INT, ConfigureAutopilotPayload::targetAltitudeFeet,
            ByteBufCodecs.VAR_INT, ConfigureAutopilotPayload::targetVerticalSpeedFpm,
            ByteBufCodecs.VAR_INT, ConfigureAutopilotPayload::targetAirspeedKnots,
            ConfigureAutopilotPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
