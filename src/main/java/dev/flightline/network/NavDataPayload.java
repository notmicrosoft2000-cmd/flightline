package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** One-time navigation database snapshot sent to each client. */
public record NavDataPayload(int version, int spawnX, int spawnZ, List<Airport> airports,
                             List<Runway> runways, List<Fix> fixes) implements CustomPacketPayload {
    public static final int VERSION = 1;
    private static final int MAX_AIRPORTS = 16;
    private static final int MAX_RUNWAYS = 64;
    private static final int MAX_FIXES = 128;
    private static final int MAX_WAYPOINTS = 16;

    public static final Type<NavDataPayload> TYPE = new Type<>(FlightLineIds.id("nav_data"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, NavDataPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NavDataPayload::version,
            ByteBufCodecs.VAR_INT, NavDataPayload::spawnX,
            ByteBufCodecs.VAR_INT, NavDataPayload::spawnZ,
            ByteBufCodecs.collection(ArrayList::new, Airport.CODEC, MAX_AIRPORTS), NavDataPayload::airports,
            ByteBufCodecs.collection(ArrayList::new, Runway.CODEC, MAX_RUNWAYS), NavDataPayload::runways,
            ByteBufCodecs.collection(ArrayList::new, Fix.CODEC, MAX_FIXES), NavDataPayload::fixes,
            NavDataPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Airport(int id, String name, int centerX, int centerZ, int elevationY,
                          List<Integer> runwayIds) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, Airport> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Airport::id,
                ByteBufCodecs.STRING_UTF8, Airport::name,
                ByteBufCodecs.VAR_INT, Airport::centerX,
                ByteBufCodecs.VAR_INT, Airport::centerZ,
                ByteBufCodecs.VAR_INT, Airport::elevationY,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT, 8), Airport::runwayIds,
                Airport::new
        );
    }

    public record Runway(int id, int airportId, String name, int thresholdX, int thresholdZ,
                         float headingDegrees, float glideSlopeDegrees) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, Runway> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Runway::id,
                ByteBufCodecs.VAR_INT, Runway::airportId,
                ByteBufCodecs.STRING_UTF8, Runway::name,
                ByteBufCodecs.VAR_INT, Runway::thresholdX,
                ByteBufCodecs.VAR_INT, Runway::thresholdZ,
                ByteBufCodecs.FLOAT, Runway::headingDegrees,
                ByteBufCodecs.FLOAT, Runway::glideSlopeDegrees,
                Runway::new
        );

        public float reciprocalHeadingDegrees() {
            float reciprocal = headingDegrees + 180.0f;
            return reciprocal >= 360.0f ? reciprocal - 360.0f : reciprocal;
        }
    }

    public record Fix(int id, String name, int x, int z, int kind) {
        public static final int WAYPOINT = 0;
        public static final int VOR = 1;
        public static final int NDB = 2;

        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, Fix> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Fix::id,
                ByteBufCodecs.STRING_UTF8, Fix::name,
                ByteBufCodecs.VAR_INT, Fix::x,
                ByteBufCodecs.VAR_INT, Fix::z,
                ByteBufCodecs.VAR_INT, Fix::kind,
                Fix::new
        );
    }
}
