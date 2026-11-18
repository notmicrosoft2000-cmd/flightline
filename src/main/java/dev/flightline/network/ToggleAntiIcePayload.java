package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleAntiIcePayload(boolean enabled) implements CustomPacketPayload {
    public static final Type<ToggleAntiIcePayload> TYPE = new Type<>(FlightLineIds.id("toggle_anti_ice"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAntiIcePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ToggleAntiIcePayload::enabled, ToggleAntiIcePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
