package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AtcRadioPayload(String message, int senderEntityId) implements CustomPacketPayload {
    public AtcRadioPayload(String message) {
        this(message, -1);
    }

    public static final Type<AtcRadioPayload> TYPE = new Type<>(FlightLineIds.id("atc_radio"));

    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, AtcRadioPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(160), AtcRadioPayload::message,
            ByteBufCodecs.VAR_INT, AtcRadioPayload::senderEntityId,
            AtcRadioPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
