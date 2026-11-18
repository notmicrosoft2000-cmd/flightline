package dev.flightline.flight;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Pure TCAS geometry helpers shared by player traffic, mob traffic and tests. */
public final class TcasGeometry {
    private static final double MOB_ESCAPE_RATE = 0.14;
    private static final double MOB_VERTICAL_SEPARATION_THRESHOLD = 0.8;

    private TcasGeometry() {
    }

    /** Ticks until closest approach; zero when relative speed is negligible. */
    public static double timeToClosestApproachTicks(Vec3 separation, Vec3 relativeVelocity) {
        double relativeSpeedSquared = relativeVelocity.lengthSqr();
        return relativeSpeedSquared < 1.0e-6 ? 0.0
                : -separation.dot(relativeVelocity) / relativeSpeedSquared;
    }

    /** Relative position vector at closest approach. */
    public static Vec3 closestSeparation(Vec3 separation, Vec3 relativeVelocity) {
        return separation.add(relativeVelocity.scale(timeToClosestApproachTicks(separation, relativeVelocity)));
    }

    /** Vertical resolution advisory for traffic at the given separation height. */
    public static int verticalResolutionCommand(double separationY) {
        return separationY > 0.5 ? FlightWarning.TCAS_DESCEND : FlightWarning.TCAS_CLIMB;
    }

    /** Vertical escape rate for a mob avoiding a player. */
    public static double mobVerticalEscape(double separationY) {
        return separationY >= 0.0 ? MOB_ESCAPE_RATE : -MOB_ESCAPE_RATE;
    }

    /** Deterministic complementary vertical escape rate for two mobs. */
    public static double mobToMobVerticalEscape(double separationY, UUID self, UUID other) {
        if (Math.abs(separationY) > MOB_VERTICAL_SEPARATION_THRESHOLD) {
            return separationY > 0.0 ? MOB_ESCAPE_RATE : -MOB_ESCAPE_RATE;
        }
        return self.compareTo(other) > 0 ? MOB_ESCAPE_RATE : -MOB_ESCAPE_RATE;
    }
}
