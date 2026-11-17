package dev.flightline.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyProceduresTest {

    @Test
    void extinguisherOnlyWorksBeforeFireBecomesStructural() {
        assertTrue(FlightTuning.canSuppressFire(0));
        assertTrue(FlightTuning.canSuppressFire(FlightTuning.ENGINE_FIRE_SUPPRESSION_WINDOW_TICKS));
        assertFalse(FlightTuning.canSuppressFire(FlightTuning.ENGINE_FIRE_SUPPRESSION_WINDOW_TICKS + 1));
        assertFalse(FlightTuning.canSuppressFire(-1));
    }

    @Test
    void relightNeedsAirspeedFuelCoolEngineAndAttemptsRemaining() {
        assertTrue(FlightTuning.canRestartEngine(true, true, 30.0, 0, 4.0, 2.0, 700.0));
        assertFalse(FlightTuning.canRestartEngine(false, true, 30.0, 0, 4.0, 2.0, 700.0),
                "a ground start is not an in-flight relight");
        assertFalse(FlightTuning.canRestartEngine(true, true, 4.0, 0, 4.0, 2.0, 700.0),
                "relight needs fuel above the minimum");
        assertFalse(FlightTuning.canRestartEngine(true, true, 30.0, 3, 4.0, 2.0, 700.0),
                "hot starts are limited");
        assertFalse(FlightTuning.canRestartEngine(true, true, 30.0, 0, 4.0, 2.0, 900.0),
                "a hot engine cannot be relit");
        assertFalse(FlightTuning.canRestartEngine(true, true, 30.0, 0, 2.1, 2.0, 700.0),
                "windmilling relight needs airspeed margin");
    }

    @Test
    void fuelJettisonProtectsLandingReserve() {
        assertEquals(85.0 - FlightTuning.FUEL_JETTISON_PER_TICK,
                FlightTuning.fuelAfterJettison(85.0), 1e-9);
        assertTrue(FlightTuning.fuelAfterJettison(20.1) >= FlightTuning.FUEL_JETTISON_RESERVE_PERCENT);
        assertTrue(FlightTuning.fuelAfterJettison(20.1) < 20.1);
        assertEquals(FlightTuning.FUEL_JETTISON_RESERVE_PERCENT,
                FlightTuning.fuelAfterJettison(FlightTuning.FUEL_JETTISON_RESERVE_PERCENT), 1e-9);
        assertEquals(FlightTuning.FUEL_JETTISON_RESERVE_PERCENT,
                FlightTuning.fuelAfterJettison(5.0), 1e-9);
    }

    @Test
    void recommendationPrefersFireSuppressionThenTimeCriticalProcedures() {
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.EXTINGUISH_ENGINE, 0),
                EmergencyStatus.recommend(EmergencyStatus.FIRE_1 | EmergencyStatus.PRESSURISATION,
                        EmergencyStatus.CAN_EXTINGUISH_1 | EmergencyStatus.CAN_EMERGENCY_DESCENT));
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.EMERGENCY_DESCENT, -1),
                EmergencyStatus.recommend(EmergencyStatus.PRESSURISATION,
                        EmergencyStatus.CAN_EMERGENCY_DESCENT | EmergencyStatus.CAN_MAYDAY));
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.ALTERNATE_GEAR, -1),
                EmergencyStatus.recommend(EmergencyStatus.GEAR_FAILED,
                        EmergencyStatus.CAN_ALTERNATE_GEAR | EmergencyStatus.CAN_MAYDAY));
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.RESET_HYDRAULICS, -1),
                EmergencyStatus.recommend(EmergencyStatus.HYDRAULIC,
                        EmergencyStatus.CAN_RESET_HYDRAULICS | EmergencyStatus.CAN_MAYDAY));
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.EMERGENCY_AUTOLAND, -1),
                EmergencyStatus.recommend(EmergencyStatus.FIRE_1 | EmergencyStatus.HEAVY_FUEL,
                        EmergencyStatus.CAN_AUTOLAND | EmergencyStatus.CAN_DUMP_FUEL
                                | EmergencyStatus.CAN_MAYDAY));
    }

    @Test
    void recommendationNeverJettisonsFuelJustBecauseFuelIsLow() {
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.DECLARE_MAYDAY, -1),
                EmergencyStatus.recommend(EmergencyStatus.FUEL_LOW,
                        EmergencyStatus.CAN_DUMP_FUEL | EmergencyStatus.CAN_MAYDAY));
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.TOGGLE_FUEL_DUMP, -1),
                EmergencyStatus.recommend(EmergencyStatus.FAILURE_1 | EmergencyStatus.HEAVY_FUEL
                                | EmergencyStatus.EMERGENCY_DECLARED,
                        EmergencyStatus.CAN_DUMP_FUEL));
    }

    @Test
    void checklistExplainsEveryConditionInPlainLanguage() {
        for (int condition : EmergencyStatus.CONDITIONS) {
            assertFalse(EmergencyStatus.conditionLabel(condition, false).isEmpty());
            assertTrue(EmergencyStatus.conditionExplanation(condition, false).length() > 20,
                    "condition " + condition + " needs a useful explanation");
        }
        assertTrue(EmergencyStatus.conditionExplanation(EmergencyStatus.FIRE_1, true)
                .contains("this engine"));
        assertTrue(EmergencyStatus.actionExplanation(EmergencyAction.EXTINGUISH_ENGINE)
                .contains("bottle"));
        assertEquals("ENGINE GROUP 1", EmergencyStatus.engineName(0, true));
    }

    @Test
    void debugScenariosResolveFromCommandIds() {
        assertEquals(EmergencyScenario.FIRE, EmergencyScenario.fromId("fire"));
        assertEquals(EmergencyScenario.FAILURE, EmergencyScenario.fromId("FAILURE"));
        assertEquals(EmergencyScenario.CLEAR, EmergencyScenario.fromId(" clear "));
        assertEquals(null, EmergencyScenario.fromId("not-a-fault"));
        assertTrue(EmergencyScenario.FIRE.targetsEngine());
        assertFalse(EmergencyScenario.GEAR.targetsEngine());
    }

    @Test
    void recommendationFallsBackToMaydayAndNoAction() {
        assertEquals(new EmergencyStatus.Recommendation(EmergencyAction.DECLARE_MAYDAY, -1),
                EmergencyStatus.recommend(EmergencyStatus.FAILURE_1, EmergencyStatus.CAN_MAYDAY));
        assertEquals(EmergencyStatus.Recommendation.NONE,
                EmergencyStatus.recommend(EmergencyStatus.FAILURE_1, 0));
        assertEquals(-1, EmergencyStatus.Recommendation.NONE.action());
        assertFalse(EmergencyStatus.Recommendation.NONE.present());
        assertEquals("Cancel MAYDAY", EmergencyStatus.actionLabel(EmergencyAction.CANCEL_MAYDAY));
    }
}
