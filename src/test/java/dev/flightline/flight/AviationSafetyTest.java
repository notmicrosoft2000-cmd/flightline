package dev.flightline.flight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AviationSafetyTest {

    @Test
    void testFlightWarningFlags() {
        assertEquals(1 << 24, FlightWarning.TERRAIN);
        assertEquals(1 << 25, FlightWarning.TOO_LOW_TERRAIN);
        assertEquals(1 << 2, FlightWarning.PULL_UP);
        assertEquals(1 << 1, FlightWarning.SINK_RATE);
        assertEquals(1 << 12, FlightWarning.DONT_SINK);
        assertEquals(1 << 13, FlightWarning.TCAS_TRAFFIC);
        assertEquals(1 << 14, FlightWarning.TCAS_RESOLUTION);
        assertEquals(1 << 17, FlightWarning.TCAS_CLIMB);
        assertEquals(1 << 18, FlightWarning.TCAS_DESCEND);
    }

    @Test
    void testTcasResolutionComplementary() {
        // Test that when two aircraft have a vertical difference, one gets climb and the other gets descend
        Vec3 separationFromA = new Vec3(10.0, 5.0, 0.0);
        Vec3 separationFromB = separationFromA.scale(-1.0);

        int resolutionForA = TcasGeometry.verticalResolutionCommand(separationFromA.y);
        int resolutionForB = TcasGeometry.verticalResolutionCommand(separationFromB.y);

        assertEquals(FlightWarning.TCAS_DESCEND, resolutionForA);
        assertEquals(FlightWarning.TCAS_CLIMB, resolutionForB);
        assertNotEquals(resolutionForA, resolutionForB);
    }

    @Test
    void testMobToMobTcasDeterministicTieBreaker() {
        UUID mob1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mob2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Same altitude: tie-break by UUID guarantees opposite escape directions.
        double escape1 = TcasGeometry.mobToMobVerticalEscape(0.0, mob1, mob2);
        double escape2 = TcasGeometry.mobToMobVerticalEscape(0.0, mob2, mob1);

        assertEquals(-0.14, escape1, 1e-4);
        assertEquals(0.14, escape2, 1e-4);
        assertEquals(0.0, escape1 + escape2, 1e-4);

        // Clear vertical separation overrides the UUID tie-breaker.
        assertEquals(0.14, TcasGeometry.mobVerticalEscape(5.0), 1e-4);
        assertEquals(-0.14, TcasGeometry.mobVerticalEscape(-5.0), 1e-4);
        assertEquals(0.14, TcasGeometry.mobToMobVerticalEscape(5.0, mob1, mob2), 1e-4);
        assertEquals(-0.14, TcasGeometry.mobToMobVerticalEscape(-5.0, mob1, mob2), 1e-4);
    }

    @Test
    void testTcasTimeToClosestApproach() {
        // Head-on collision course: distance = 100 blocks, relative velocity = -2 blocks/tick
        Vec3 separation = new Vec3(100.0, 0.0, 0.0);
        Vec3 relativeVelocity = new Vec3(-2.0, 0.0, 0.0);

        double timeToClosest = TcasGeometry.timeToClosestApproachTicks(separation, relativeVelocity);
        assertEquals(50.0, timeToClosest, 1e-4);

        Vec3 closestSeparation = TcasGeometry.closestSeparation(separation, relativeVelocity);
        assertEquals(0.0, closestSeparation.length(), 1e-4);

        // Negligible relative speed yields zero time and leaves the separation unchanged.
        assertEquals(0.0, TcasGeometry.timeToClosestApproachTicks(separation, Vec3.ZERO), 1e-4);
        Vec3 stationaryClosest = TcasGeometry.closestSeparation(separation, Vec3.ZERO);
        assertEquals(separation.x, stationaryClosest.x, 1e-4);
        assertEquals(separation.y, stationaryClosest.y, 1e-4);
        assertEquals(separation.z, stationaryClosest.z, 1e-4);
    }
}
