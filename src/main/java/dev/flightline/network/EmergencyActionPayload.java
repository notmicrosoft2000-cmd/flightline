package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client request to run one emergency procedure. Target 0/1 selects an engine where relevant. */
public record EmergencyActionPayload(int action, int target) implements CustomPacketPayload {
    public static final Type<EmergencyActionPayload> TYPE = new Type<>(FlightLine.id("emergency_action"));

    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, EmergencyActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EmergencyActionPayload::action,
            ByteBufCodecs.VAR_INT, EmergencyActionPayload::target,
            EmergencyActionPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
