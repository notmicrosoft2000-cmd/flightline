package dev.flightline.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherSimulationTest {

    @Test
    void naturalWindshearRequiresStormAndInterval() {
        assertFalse(WeatherSimulation.shouldStartWindshear(false, 0, 0, false));
        assertTrue(WeatherSimulation.shouldStartWindshear(true, 0, 0, false));
        assertFalse(WeatherSimulation.shouldStartWindshear(true, 1, 0, false));
        assertTrue(WeatherSimulation.shouldStartWindshear(true, 1, -1, false));
    }

    @Test
    void forcedWindshearStillRequiresStorm() {
        assertTrue(WeatherSimulation.shouldStartWindshear(true, 7, 123, true));
        assertFalse(WeatherSimulation.shouldStartWindshear(false, 7, 123, true));
    }

    @Test
    void severityMatchesTheExistingWindshearCurve() {
        assertEquals(0.55, WeatherSimulation.windshearSeverity(0), 1e-9);
        assertEquals(1.0, WeatherSimulation.windshearSeverity(70), 1e-9);
        assertEquals(1.0, WeatherSimulation.windshearSeverity(120), 1e-9);
        assertTrue(WeatherSimulation.windshearSeverity(35) > WeatherSimulation.windshearSeverity(0));
    }
}
