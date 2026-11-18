package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record StartEnginesPayload() implements CustomPacketPayload {
    public static final Type<StartEnginesPayload> TYPE = new Type<>(FlightLineIds.id("start_engines"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StartEnginesPayload> CODEC =
            StreamCodec.unit(new StartEnginesPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
