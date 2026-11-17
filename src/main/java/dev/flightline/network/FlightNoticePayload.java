package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Short checklist feedback shown as a cockpit toast without opening chat. */
public record FlightNoticePayload(String message) implements CustomPacketPayload {
    public static final Type<FlightNoticePayload> TYPE = new Type<>(FlightLine.id("flight_notice"));

    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightNoticePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(200), FlightNoticePayload::message,
            FlightNoticePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
