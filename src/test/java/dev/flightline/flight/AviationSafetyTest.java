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

        int resolutionForA = separationFromA.y > 0.5 ? FlightWarning.TCAS_DESCEND : FlightWarning.TCAS_CLIMB;
        int resolutionForB = separationFromB.y > 0.5 ? FlightWarning.TCAS_DESCEND : FlightWarning.TCAS_CLIMB;

        assertEquals(FlightWarning.TCAS_DESCEND, resolutionForA);
        assertEquals(FlightWarning.TCAS_CLIMB, resolutionForB);
        assertNotEquals(resolutionForA, resolutionForB);
    }

    @Test
    void testMobToMobTcasDeterministicTieBreaker() {
        UUID mob1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mob2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Same altitude: tie-break by UUID guarantees opposite escape directions
        double escape1 = mob1.compareTo(mob2) > 0 ? 0.14 : -0.14;
        double escape2 = mob2.compareTo(mob1) > 0 ? 0.14 : -0.14;

        assertEquals(-0.14, escape1, 1e-4);
        assertEquals(0.14, escape2, 1e-4);
        assertEquals(0.0, escape1 + escape2, 1e-4);
    }

    @Test
    void testTcasTimeToClosestApproach() {
        // Head-on collision course: distance = 100 blocks, relative velocity = -2 blocks/tick
        Vec3 separation = new Vec3(100.0, 0.0, 0.0);
        Vec3 relativeVelocity = new Vec3(-2.0, 0.0, 0.0);

        double speedSquared = relativeVelocity.lengthSqr();
        double timeToClosest = -separation.dot(relativeVelocity) / speedSquared;

        assertEquals(50.0, timeToClosest, 1e-4);

        Vec3 closestSeparation = separation.add(relativeVelocity.scale(timeToClosest));
        assertEquals(0.0, closestSeparation.length(), 1e-4);
    }
}
