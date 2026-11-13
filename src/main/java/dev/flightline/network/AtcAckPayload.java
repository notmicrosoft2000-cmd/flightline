package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AtcAckPayload(int controllerEntityId) implements CustomPacketPayload {
    public static final Type<AtcAckPayload> TYPE = new Type<>(FlightLine.id("atc_ack"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, AtcAckPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AtcAckPayload::controllerEntityId, AtcAckPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
