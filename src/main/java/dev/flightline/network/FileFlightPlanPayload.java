package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Pilot request to file, clear, or cancel a flight plan. */
public record FileFlightPlanPayload(int originId, int destinationId, List<Integer> waypointIds,
                                    int cruiseAltitudeFeet, int alternateId, Action action)
        implements CustomPacketPayload {
    public static final int MAX_WAYPOINTS = 16;

    public enum Action {
        FILE,
        CLEARANCE,
        CANCEL,
        LNAV,
        APPROACH,
        SYNC;

        public static Action fromId(int id) {
            return switch (id) {
                case 1 -> CLEARANCE;
                case 2 -> CANCEL;
                case 3 -> LNAV;
                case 4 -> APPROACH;
                case 5 -> SYNC;
                default -> FILE;
            };
        }

        public int id() {
            return switch (this) {
                case FILE -> 0;
                case CLEARANCE -> 1;
                case CANCEL -> 2;
                case LNAV -> 3;
                case APPROACH -> 4;
                case SYNC -> 5;
            };
        }
    }

    public static final Type<FileFlightPlanPayload> TYPE = new Type<>(FlightLineIds.id("file_flight_plan"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, FileFlightPlanPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FileFlightPlanPayload::originId,
            ByteBufCodecs.VAR_INT, FileFlightPlanPayload::destinationId,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT, MAX_WAYPOINTS),
                    FileFlightPlanPayload::waypointIds,
            ByteBufCodecs.VAR_INT, FileFlightPlanPayload::cruiseAltitudeFeet,
            ByteBufCodecs.VAR_INT, FileFlightPlanPayload::alternateId,
            ByteBufCodecs.VAR_INT, payload -> payload.action().id(),
            (origin, destination, waypoints, altitude, alternate, action) ->
                    new FileFlightPlanPayload(origin, destination, waypoints, altitude, alternate,
                            Action.fromId(action))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
