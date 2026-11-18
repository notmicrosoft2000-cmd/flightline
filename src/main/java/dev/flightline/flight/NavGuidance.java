package dev.flightline.flight;

import dev.flightline.network.NavDataPayload;

/** Pure navigation math shared by the server guidance loop, client displays and tests. */
public final class NavGuidance {
    public static final int FIX_CAPTURE_BLOCKS = 96;
    public static final int AIRPORT_CAPTURE_BLOCKS = 192;

    private NavGuidance() {
    }

    /** Bearing in Minecraft yaw convention: 0 = +Z, 90 = -X, 180 = -Z, 270 = +X. */
    public static double bearingDegrees(double fromX, double fromZ, double toX, double toZ) {
        return wrapDegrees(Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ)));
    }

    public static double distanceBlocks(double fromX, double fromZ, double toX, double toZ) {
        return Math.hypot(toX - fromX, toZ - fromZ);
    }

    /** Signed lateral offset from the leg course; positive is to the right of the course. */
    public static double crossTrackBlocks(double aircraftX, double aircraftZ,
                                          double startX, double startZ,
                                          double endX, double endZ) {
        double course = Math.toRadians(bearingDegrees(startX, startZ, endX, endZ));
        double courseX = -Math.sin(course);
        double courseZ = Math.cos(course);
        double dx = aircraftX - startX;
        double dz = aircraftZ - startZ;
        return dz * courseX - dx * courseZ;
    }

    /** Estimated minutes at a ground speed expressed in approximation knots. */
    public static double travelMinutes(double distanceBlocks, double speedKnots) {
        if (speedKnots <= 0.0 || distanceBlocks <= 0.0) return 0.0;
        return distanceBlocks * FlightTuning.KNOTS_PER_BLOCK_PER_TICK / (speedKnots * 1200.0);
    }

    public static boolean shouldSequence(double aircraftX, double aircraftZ,
                                         double targetX, double targetZ, boolean airport) {
        return distanceBlocks(aircraftX, aircraftZ, targetX, targetZ)
                <= (airport ? AIRPORT_CAPTURE_BLOCKS : FIX_CAPTURE_BLOCKS);
    }

    public static NavDataPayload.Runway approachRunway(NavDatabase database, FlightPlan plan) {
        if (database == null || plan == null || plan.arrivalRunwayId() <= 0) return null;
        return database.runway(plan.arrivalRunwayId());
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }
}
