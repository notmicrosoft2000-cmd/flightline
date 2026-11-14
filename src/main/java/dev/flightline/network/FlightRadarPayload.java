package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record FlightRadarPayload(int ownX, int ownZ, int ownAltitudeFeet, float ownHeadingDegrees,
                                 int scopeBlocks, List<Contact> contacts) implements CustomPacketPayload {
    public static final Type<FlightRadarPayload> TYPE = new Type<>(FlightLine.id("flight_radar"));

    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightRadarPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FlightRadarPayload::ownX,
            ByteBufCodecs.VAR_INT, FlightRadarPayload::ownZ,
            ByteBufCodecs.VAR_INT, FlightRadarPayload::ownAltitudeFeet,
            ByteBufCodecs.FLOAT, FlightRadarPayload::ownHeadingDegrees,
            ByteBufCodecs.VAR_INT, FlightRadarPayload::scopeBlocks,
            ByteBufCodecs.collection(ArrayList::new, Contact.CODEC, 512), FlightRadarPayload::contacts,
            FlightRadarPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Contact(int entityId, String name, boolean player, boolean airborne, int x, int z,
                          int altitudeFeet, int speedKnots, float headingDegrees, int verticalSpeedFpm) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, Contact> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Contact::entityId,
                ByteBufCodecs.stringUtf8(64), Contact::name,
                ByteBufCodecs.BOOL, Contact::player,
                ByteBufCodecs.BOOL, Contact::airborne,
                ByteBufCodecs.VAR_INT, Contact::x,
                ByteBufCodecs.VAR_INT, Contact::z,
                ByteBufCodecs.VAR_INT, Contact::altitudeFeet,
                ByteBufCodecs.VAR_INT, Contact::speedKnots,
                ByteBufCodecs.FLOAT, Contact::headingDegrees,
                ByteBufCodecs.VAR_INT, Contact::verticalSpeedFpm,
                Contact::new
        );
    }
}
