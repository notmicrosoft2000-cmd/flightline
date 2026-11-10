package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlightInputPayload(int controls) implements CustomPacketPayload {
    public static final Type<FlightInputPayload> TYPE = new Type<>(FlightLine.id("flight_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightInputPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, FlightInputPayload::controls, FlightInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
