package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleGearPayload() implements CustomPacketPayload {
    public static final Type<ToggleGearPayload> TYPE = new Type<>(FlightLineIds.id("toggle_gear"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleGearPayload> CODEC =
            StreamCodec.unit(new ToggleGearPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
