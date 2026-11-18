package dev.flightline.flight;

import dev.flightline.TestBootstrap;
import dev.flightline.network.FileFlightPlanPayload;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightPlanTest {
    @BeforeAll
    static void bootstrapRegistries() {
        TestBootstrap.ensure();
    }

    private static final NavDatabase DATABASE =
            NavDatabase.create(new BlockPos(0, 64, 0), -64, (x, z) -> 80);

    private static int airport(int index) {
        return DATABASE.airports().get(index).id();
    }

    private static int fix(int index) {
        return DATABASE.fixes().get(index).id();
    }

    private static FlightPlan plan() {
        return new FlightPlan(airport(0), airport(1), List.of(fix(0), fix(1)), 12_000, airport(2));
    }

    @Test
    void validatesAirportsFixesAltitudeAndWaypointList() {
        assertTrue(FlightPlan.validate(DATABASE, airport(0), airport(1), List.of(fix(0)),
                12_000, airport(2)).valid());

        assertFalse(FlightPlan.validate(DATABASE, 0, airport(1), List.of(fix(0)),
                12_000, airport(2)).valid());
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(0), List.of(fix(0)),
                12_000, airport(2)).valid());
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(1), List.of(fix(0)),
                12_000, airport(0)).valid());
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(1), List.of(fix(0)),
                99, airport(2)).valid());
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(1), List.of(fix(0)),
                40_001, airport(2)).valid());
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(1), List.of(-1),
                12_000, airport(2)).valid());
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(1),
                List.of(fix(0), fix(0)), 12_000, airport(2)).valid());

        List<Integer> tooMany = new ArrayList<>();
        for (int i = 0; i <= FileFlightPlanPayload.MAX_WAYPOINTS; i++) {
            tooMany.add(fix(i % DATABASE.fixes().size()));
        }
        assertFalse(FlightPlan.validate(DATABASE, airport(0), airport(1), tooMany,
                12_000, airport(2)).valid());
    }

    @Test
    void statusAndRunwayChangesAdvanceRevision() {
        FlightPlan plan = plan();
        int revision = plan.revision();

        plan.setStatus(FlightPlan.Status.FILED);
        assertEquals(revision + 1, plan.revision());
        plan.setStatus(FlightPlan.Status.FILED);
        assertEquals(revision + 1, plan.revision());

        plan.setRunways(1, 2);
        assertEquals(revision + 2, plan.revision());
        plan.setRunways(1, 2);
        assertEquals(revision + 2, plan.revision());

        plan.restore(FlightPlan.Status.ACTIVE.id(), 1, 3, 4, 41);
        assertEquals(FlightPlan.Status.ACTIVE, plan.status());
        assertEquals(1, plan.activeLeg());
        assertEquals(3, plan.departureRunwayId());
        assertEquals(4, plan.arrivalRunwayId());
        assertEquals(41, plan.revision());
    }

    @Test
    void activeLegSequencesThroughFixesThenDestination() {
        FlightPlan plan = plan();

        assertEquals(3, plan.legCount());
        assertEquals(fix(0), plan.activeLeg(DATABASE).id());
        assertTrue(plan.advanceLeg());
        assertEquals(fix(1), plan.activeLeg(DATABASE).id());
        assertTrue(plan.advanceLeg());
        assertEquals(airport(1), plan.activeLeg(DATABASE).id());
        assertTrue(plan.advanceLeg());
        assertNull(plan.activeLeg(DATABASE));
        assertFalse(plan.advanceLeg());

        plan.setActiveLeg(99);
        assertEquals(plan.legCount(), plan.activeLeg());
        plan.setActiveLeg(-5);
        assertEquals(0, plan.activeLeg());
        assertNotNull(plan.toPayload());
    }

    @Test
    void payloadPreservesPlanState() {
        FlightPlan plan = plan();
        plan.setStatus(FlightPlan.Status.CLEARED);
        plan.setRunways(1, 2);
        plan.advanceLeg();

        var payload = plan.toPayload();
        var restored = FlightPlan.fromPayload(payload);

        assertEquals(plan.revision(), restored.revision());
        assertEquals(plan.status(), restored.status());
        assertEquals(plan.activeLeg(), restored.activeLeg());
        assertEquals(plan.originId(), restored.originId());
        assertEquals(plan.destinationId(), restored.destinationId());
        assertEquals(plan.alternateId(), restored.alternateId());
        assertEquals(plan.cruiseAltitudeFeet(), restored.cruiseAltitudeFeet());
        assertEquals(plan.departureRunwayId(), restored.departureRunwayId());
        assertEquals(plan.arrivalRunwayId(), restored.arrivalRunwayId());
        assertEquals(plan.waypointIds(), restored.waypointIds());
    }
}
