package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleAutopilotPayload() implements CustomPacketPayload {
    public static final Type<ToggleAutopilotPayload> TYPE = new Type<>(FlightLineIds.id("toggle_autopilot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAutopilotPayload> CODEC =
            StreamCodec.unit(new ToggleAutopilotPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
