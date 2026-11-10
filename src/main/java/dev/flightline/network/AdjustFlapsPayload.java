package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AdjustFlapsPayload(int delta) implements CustomPacketPayload {
    public static final Type<AdjustFlapsPayload> TYPE = new Type<>(FlightLine.id("adjust_flaps"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdjustFlapsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AdjustFlapsPayload::delta, AdjustFlapsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
