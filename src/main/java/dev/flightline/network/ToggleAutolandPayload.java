package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleAutolandPayload() implements CustomPacketPayload {
    public static final Type<ToggleAutolandPayload> TYPE = new Type<>(FlightLineIds.id("toggle_autoland"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAutolandPayload> CODEC =
            StreamCodec.unit(new ToggleAutolandPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
