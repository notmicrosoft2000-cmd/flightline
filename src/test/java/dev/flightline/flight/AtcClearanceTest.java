package dev.flightline.flight;

import dev.flightline.network.AtcOrderPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtcClearanceTest {

    @Test
    void validatesOrderValues() {
        assertTrue(AtcClearance.isValidOrder(AtcOrderPayload.HOLD));
        assertTrue(AtcClearance.isValidOrder(AtcOrderPayload.FOLLOW));
        assertFalse(AtcClearance.isValidOrder(AtcOrderPayload.RELEASE));
        assertTrue(AtcClearance.isValidValue(AtcOrderPayload.TURN, 0));
        assertTrue(AtcClearance.isValidValue(AtcOrderPayload.TURN, 359));
        assertFalse(AtcClearance.isValidValue(AtcOrderPayload.TURN, 360));
        assertTrue(AtcClearance.isValidValue(AtcOrderPayload.CLIMB, 12_000));
        assertFalse(AtcClearance.isValidValue(AtcOrderPayload.CLIMB, 40_001));
        assertTrue(AtcClearance.isValidValue(AtcOrderPayload.SPEED, 300));
        assertFalse(AtcClearance.isValidValue(AtcOrderPayload.SPEED, 39));
    }

    @Test
    void holdPreservesCurrentFlightDeckTargets() {
        AtcClearance clearance = AtcClearance.create(AtcOrderPayload.HOLD, 0, -1, 275.4, 8_420, 212);

        assertEquals(275.0, clearance.targetHeadingDegrees(), 1e-6);
        assertEquals(8_420, clearance.targetAltitudeFeet());
        assertEquals(212, clearance.targetAirspeedKnots());
        assertEquals(0, clearance.targetVerticalSpeedFpm());
        assertFalse(clearance.dynamicTarget());
    }

    @Test
    void altitudeOrdersSetClimbOrDescentRate() {
        AtcClearance climb = AtcClearance.create(AtcOrderPayload.CLIMB, 24_000, -1, 90, 12_000, 260);
        AtcClearance descend = AtcClearance.create(AtcOrderPayload.DESCEND, 3_000, -1, 90, 12_000, 260);

        assertEquals(24_000, climb.targetAltitudeFeet());
        assertTrue(climb.targetVerticalSpeedFpm() > 0);
        assertEquals(3_000, descend.targetAltitudeFeet());
        assertTrue(descend.targetVerticalSpeedFpm() < 0);
        assertEquals(90.0, climb.targetHeadingDegrees(), 1e-6);
    }

    @Test
    void positionOrdersAreMarkedDynamic() {
        assertTrue(AtcClearance.create(AtcOrderPayload.ORBIT, 0, -1, 10, 5_000, 180).dynamicTarget());
        assertTrue(AtcClearance.create(AtcOrderPayload.RETURN, 0, -1, 10, 5_000, 180).dynamicTarget());
        assertTrue(AtcClearance.create(AtcOrderPayload.FOLLOW, 0, 42, 10, 5_000, 180).dynamicTarget());
        assertEquals(42, AtcClearance.create(AtcOrderPayload.FOLLOW, 0, 42, 10, 5_000, 180)
                .referenceEntityId());
    }

    @Test
    void radioTextMatchesPilotDisplay() {
        assertEquals("turn heading 270", AtcClearance.create(
                AtcOrderPayload.TURN, 270, -1, 0, 5_000, 180).radioText());
        assertEquals("maintain 220 knots", AtcClearance.create(
                AtcOrderPayload.SPEED, 220, -1, 0, 5_000, 180).radioText());
    }
}
