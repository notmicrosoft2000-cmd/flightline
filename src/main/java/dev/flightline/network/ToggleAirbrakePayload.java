package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleAirbrakePayload(boolean deployed) implements CustomPacketPayload {
    public static final Type<ToggleAirbrakePayload> TYPE = new Type<>(FlightLineIds.id("toggle_airbrake"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAirbrakePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, ToggleAirbrakePayload::deployed,
                    ToggleAirbrakePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
