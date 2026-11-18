package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Authoritative flight plan state sent by the server. */
public record FlightPlanPayload(int revision, int statusId, int activeLeg, int originId,
                                int destinationId, int alternateId, int cruiseAltitudeFeet,
                                int departureRunwayId, int arrivalRunwayId, List<Integer> waypointIds)
        implements CustomPacketPayload {
    public static final Type<FlightPlanPayload> TYPE = new Type<>(FlightLineIds.id("flight_plan"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightPlanPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FlightPlanPayload::revision,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::statusId,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::activeLeg,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::originId,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::destinationId,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::alternateId,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::cruiseAltitudeFeet,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::departureRunwayId,
            ByteBufCodecs.VAR_INT, FlightPlanPayload::arrivalRunwayId,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT,
                    FileFlightPlanPayload.MAX_WAYPOINTS), FlightPlanPayload::waypointIds,
            FlightPlanPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
