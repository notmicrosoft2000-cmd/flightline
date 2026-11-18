package dev.flightline.flight;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavGuidanceTest {
    private static final NavDatabase DATABASE =
            NavDatabase.create(new BlockPos(0, 64, 0), -64, (x, z) -> 80);

    @Test
    void bearingUsesMinecraftYawConvention() {
        assertEquals(270.0, NavGuidance.bearingDegrees(0, 0, 10, 0), 1e-9);
        assertEquals(0.0, NavGuidance.bearingDegrees(0, 0, 0, 10), 1e-9);
        assertEquals(90.0, NavGuidance.bearingDegrees(0, 0, -10, 0), 1e-9);
        assertEquals(180.0, NavGuidance.bearingDegrees(0, 0, 0, -10), 1e-9);
    }

    @Test
    void distanceAndCrossTrackUseLegGeometry() {
        assertEquals(50.0, NavGuidance.distanceBlocks(10, 10, 40, 50), 1e-9);

        // Course 000 points +Z, so right of course is -X.
        assertTrue(NavGuidance.crossTrackBlocks(-10, 50, 0, 0, 0, 100) > 0.0);
        assertTrue(NavGuidance.crossTrackBlocks(10, 50, 0, 0, 0, 100) < 0.0);
        assertEquals(10.0, NavGuidance.crossTrackBlocks(-10, 50, 0, 0, 0, 100), 1e-9);

        // Course 270 points +X, so right of course is +Z.
        assertEquals(7.0, NavGuidance.crossTrackBlocks(50, 7, 0, 0, 100, 0), 1e-9);
    }

    @Test
    void travelMinutesUsesWorldSpeedConversion() {
        assertEquals(0.0, NavGuidance.travelMinutes(100.0, 0.0), 1e-9);
        assertEquals(1.08, NavGuidance.travelMinutes(64.8 * 20.0, 64.8), 1e-9);
        assertTrue(NavGuidance.travelMinutes(500.0, 250.0) > 0.0);
    }

    @Test
    void captureRadiusSeparatesFixesFromAirports() {
        assertTrue(NavGuidance.shouldSequence(90, 0, 100, 0, false));
        assertFalse(NavGuidance.shouldSequence(0, 0, 100, 0, false));
        assertTrue(NavGuidance.shouldSequence(150, 0, 100, 0, true));
        assertFalse(NavGuidance.shouldSequence(300, 0, 100, 0, true));
    }

    @Test
    void approachRunwayComesFromAssignedArrivalRunway() {
        int arrival = DATABASE.airports().get(1).runwayIds().get(1);
        FlightPlan plan = new FlightPlan(DATABASE.airports().get(0).id(),
                DATABASE.airports().get(1).id(), List.of(DATABASE.fixes().get(0).id()),
                9_000, DATABASE.airports().get(2).id());

        assertNull(NavGuidance.approachRunway(DATABASE, plan));
        plan.setRunways(DATABASE.airports().get(0).runwayIds().get(0), arrival);
        assertNotNull(NavGuidance.approachRunway(DATABASE, plan));
        assertEquals(arrival, NavGuidance.approachRunway(DATABASE, plan).id());
    }
}
