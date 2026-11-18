package dev.flightline.flight;

import dev.flightline.TestBootstrap;
import dev.flightline.network.NavDataPayload;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavDatabaseTest {
    @BeforeAll
    static void bootstrapRegistries() {
        TestBootstrap.ensure();
    }

    private static NavDatabase database() {
        return NavDatabase.create(new BlockPos(100, 64, -200), -64, (x, z) -> 80);
    }

    @Test
    void generatesDeterministicUniqueIdentifiers() {
        NavDatabase first = database();
        NavDatabase second = database();

        assertEquals(first.snapshot(), second.snapshot());
        assertEquals(9, first.airports().size());
        assertEquals(18, first.runways().size());
        assertEquals(18, first.fixes().size());

        assertTrue(uniqueIds(first.airports().stream().map(NavDataPayload.Airport::id).toList()));
        assertTrue(uniqueIds(first.runways().stream().map(NavDataPayload.Runway::id).toList()));
        assertTrue(uniqueIds(first.fixes().stream().map(NavDataPayload.Fix::id).toList()));
    }

    @Test
    void runwaysAreReciprocalAndCenteredOnTheirAirport() {
        NavDatabase database = database();

        for (NavDataPayload.Airport airport : database.airports()) {
            List<NavDataPayload.Runway> ends = airport.runwayIds().stream()
                    .map(database::runway)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertEquals(2, ends.size());

            NavDataPayload.Runway first = ends.get(0);
            NavDataPayload.Runway second = ends.get(1);
            assertEquals(180.0, Math.abs(first.headingDegrees() - second.headingDegrees()), 1e-5);
            assertEquals(first.reciprocalHeadingDegrees(), second.headingDegrees(), 1e-5);
            assertNotNull(database.airport(first.airportId()));
            assertEquals(airport.id(), first.airportId());

            for (NavDataPayload.Runway runway : ends) {
                double[] vector = NavDatabase.headingVector(runway.headingDegrees());
                double thresholdDistance = Math.hypot(
                        airport.centerX() - (runway.thresholdX() + vector[0] * RunwayGenerator.RUNWAY_LENGTH / 2.0),
                        airport.centerZ() - (runway.thresholdZ() + vector[1] * RunwayGenerator.RUNWAY_LENGTH / 2.0));
                assertEquals(0.0, thresholdDistance, 1.5);
            }
        }
    }

    @Test
    void fixesStayInsideTheGeneratedGridNeighborhood() {
        NavDatabase database = database();
        double limit = RunwayGenerator.GRID_SPACING + 2 * 512.0 + 1.0;

        for (NavDataPayload.Fix fix : database.fixes()) {
            assertTrue(Math.abs(fix.x() - database.spawnX()) <= limit,
                    fix.name() + " outside X bounds");
            assertTrue(Math.abs(fix.z() - database.spawnZ()) <= limit,
                    fix.name() + " outside Z bounds");
        }
    }

    @Test
    void runwaySelectionMaximizesHeadwindComponent() {
        NavDatabase database = database();
        long gameTime = 12_000L;

        for (NavDataPayload.Airport airport : database.airports()) {
            WeatherField.Sample weather = WeatherField.sample(airport.centerX(), airport.centerZ(),
                    airport.elevationY(), false, false, 0.8f, gameTime);
            double[] flow = WeatherField.windVector(weather.surfaceWindDirectionDegrees(),
                    weather.surfaceWindKnots());
            NavDataPayload.Runway expected = null;
            double bestComponent = Double.POSITIVE_INFINITY;
            for (int id : airport.runwayIds()) {
                NavDataPayload.Runway runway = database.runway(id);
                assertNotNull(runway);
                double[] vector = NavDatabase.headingVector(runway.headingDegrees());
                double component = flow[0] * vector[0] + flow[1] * vector[1];
                if (component < bestComponent) {
                    bestComponent = component;
                    expected = runway;
                }
            }

            NavDataPayload.Runway selected = database.bestRunway(airport.id(), false, false, gameTime);
            assertNotNull(selected);
            assertEquals(expected.id(), selected.id(), airport.name());
        }
    }

    private static boolean uniqueIds(List<Integer> ids) {
        return new HashSet<>(ids).size() == ids.size();
    }
}
