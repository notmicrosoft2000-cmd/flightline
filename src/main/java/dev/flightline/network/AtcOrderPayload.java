package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AtcOrderPayload(int entityId, int order, int value, int referenceEntityId)
        implements CustomPacketPayload {
    public static final Type<AtcOrderPayload> TYPE = new Type<>(FlightLine.id("atc_order"));

    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, AtcOrderPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AtcOrderPayload::entityId,
            ByteBufCodecs.VAR_INT, AtcOrderPayload::order,
            ByteBufCodecs.VAR_INT, AtcOrderPayload::value,
            ByteBufCodecs.VAR_INT, AtcOrderPayload::referenceEntityId,
            AtcOrderPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final int HOLD = 0;
    public static final int TURN = 1;
    public static final int CLIMB = 2;
    public static final int DESCEND = 3;
    public static final int SPEED = 4;
    public static final int ORBIT = 5;
    public static final int RETURN = 6;
    public static final int FOLLOW = 7;
    public static final int RELEASE = 8;
}
