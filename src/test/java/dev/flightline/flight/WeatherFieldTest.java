package dev.flightline.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherFieldTest {
    @Test
    void fieldSamplingIsDeterministicAndNearbyBlocksBlendSmoothly() {
        WeatherField.Sample first = WeatherField.sample(0, 0, 96.0,
                false, false, 0.8f, 1200L);
        WeatherField.Sample repeat = WeatherField.sample(0, 0, 96.0,
                false, false, 0.8f, 1200L);
        WeatherField.Sample nearby = WeatherField.sample(16, 0, 96.0,
                false, false, 0.8f, 1200L);

        assertEquals(first.lowPressure(), repeat.lowPressure(), 1e-12);
        assertEquals(first.humidity(), repeat.humidity(), 1e-12);
        assertEquals(first.surfaceWindDirectionDegrees(), repeat.surfaceWindDirectionDegrees(), 1e-12);
        assertTrue(Math.abs(first.humidity() - nearby.humidity()) < 0.15);
        assertTrue(Math.abs(first.lowPressure() - nearby.lowPressure()) < 0.15);
    }

    @Test
    void windVectorUsesMeteorologicalFromConvention() {
        double[] east = WeatherField.windVector(270.0, 10.0);
        double[] south = WeatherField.windVector(0.0, 10.0);

        assertTrue(east[0] > 9.9);
        assertEquals(0.0, east[1], 1e-9);
        assertEquals(0.0, south[0], 1e-9);
        assertTrue(south[1] > 9.9);
    }

    @Test
    void stormCellsCarryStructureMetadata() {
        WeatherSimulation.WeatherCell storm = WeatherSimulation.sampleCell(0, 0, 1200L,
                WeatherField.WEATHER_SEED, 2, 80, 50);

        assertTrue(storm.intensity() > 0);
        assertTrue(storm.topFeet() > 0);
        assertTrue(storm.condition() >= 1);
        assertTrue(Math.hypot(storm.motionDx(), storm.motionDz()) > 0.9);
    }
}
