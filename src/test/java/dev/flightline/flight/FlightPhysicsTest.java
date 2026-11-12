package dev.flightline.flight;

import dev.flightline.network.FlightInstrumentPayload;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightPhysicsTest {

    @Test
    void testAircraftAttitudeVector() {
        // Test pitch = 0, yaw = 0 (facing south / +Z)
        double pitchRad = Math.toRadians(0.0);
        double yawRad = Math.toRadians(0.0);
        double cosPitch = Math.cos(pitchRad);
        Vec3 attitude = new Vec3(
                -Math.sin(yawRad) * cosPitch,
                Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        );
        assertEquals(0.0, attitude.x, 1e-4);
        assertEquals(0.0, attitude.y, 1e-4);
        assertEquals(1.0, attitude.z, 1e-4);

        // Test pitch = 30 deg up, yaw = 90 deg (facing west / -X)
        pitchRad = Math.toRadians(30.0);
        yawRad = Math.toRadians(90.0);
        cosPitch = Math.cos(pitchRad);
        attitude = new Vec3(
                -Math.sin(yawRad) * cosPitch,
                Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        );
        assertEquals(-Math.cos(Math.toRadians(30.0)), attitude.x, 1e-4);
        assertEquals(Math.sin(Math.toRadians(30.0)), attitude.y, 1e-4);
        assertEquals(0.0, attitude.z, 1e-4);
        assertEquals(1.0, attitude.length(), 1e-4);
    }

    @Test
    void testCoordinatedTurnRate() {
        // Coordinated turn rate: omega = g * tan(bank) / v
        double bank = 20.0;
        double speed = 2.0;
        double turnRate = 0.95 * FlightTuning.WORLD_SPEED_SCALE * Math.tan(Math.toRadians(bank)) / speed;

        assertTrue(turnRate > 0.0);
        // Bank left should give negative turn rate
        double bankLeft = -20.0;
        double turnRateLeft = 0.95 * FlightTuning.WORLD_SPEED_SCALE * Math.tan(Math.toRadians(bankLeft)) / speed;
        assertEquals(-turnRate, turnRateLeft, 1e-4);
    }

    @Test
    void testGroundEffectCushioning() {
        // At 2 blocks above ground (flare height):
        double radioAltBlocks = 2.0;
        double groundEffect = radioAltBlocks < 12.0 ? Math.max(0.0, (12.0 - radioAltBlocks) / 12.0) : 0.0;
        assertTrue(groundEffect > 0.8);

        double baseLift = 0.10;
        double flaredLift = baseLift * (1.0 + groundEffect * 0.22);
        assertTrue(flaredLift > baseLift);

        double baseDrag = 0.005;
        double flaredDrag = baseDrag * (1.0 - groundEffect * 0.30);
        assertTrue(flaredDrag < baseDrag);

        // At 50 blocks above ground: ground effect must be 0
        radioAltBlocks = 50.0;
        groundEffect = radioAltBlocks < 12.0 ? Math.max(0.0, (12.0 - radioAltBlocks) / 12.0) : 0.0;
        assertEquals(0.0, groundEffect, 1e-4);
    }

    @Test
    void testCrashKnockbackScaling() {
        double lowSpeedImpact = 0.4;
        assertEquals(1.0, AircraftImpact.knockbackStrength(lowSpeedImpact), 1e-4);
        assertEquals(4.4, AircraftImpact.impactRadius(lowSpeedImpact), 1e-4);

        double highSpeedImpact = 3.0;
        assertEquals(3.2, AircraftImpact.knockbackStrength(highSpeedImpact), 1e-4);
        assertEquals(7.0, AircraftImpact.impactRadius(highSpeedImpact), 1e-4);
    }

    @Test
    void testSafeTouchdownDoesNotDamageOrFailGear() {
        FlightController.LandingAssessment assessment = FlightController.assessLanding(
                true, FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED, FlightTuning.SAFE_TOUCHDOWN_SPEED);

        assertFalse(assessment.crashes());
        assertFalse(assessment.gearFailed());
        assertEquals(0.0f, assessment.damage());
    }

    @Test
    void testHardLandingIsSurvivableButDamagesLandingGear() {
        FlightController.LandingAssessment assessment = FlightController.assessLanding(
                true, FlightTuning.MAX_HARD_LANDING_SINK_SPEED * 0.6,
                FlightTuning.MAX_HARD_LANDING_SPEED * 0.95);

        assertFalse(assessment.crashes());
        assertTrue(assessment.gearFailed());
        assertTrue(assessment.damage() > 0.0f);
        assertTrue(assessment.damage() <= 6.0f);
    }

    @Test
    void testGearUpOrExcessiveSinkRateCrashesOnTouchdown() {
        assertTrue(FlightController.assessLanding(false, 0.05, 0.5).crashes());
        assertTrue(FlightController.assessLanding(true,
                FlightTuning.MAX_HARD_LANDING_SINK_SPEED * 1.01, 0.5).crashes());
    }

    @Test
    void testAutothrottleDeadbandIsTheDisplayedTwoKnots() {
        assertEquals(2.0, FlightTuning.AUTOTHROTTLE_DEADBAND_KNOTS);
    }

    @Test
    void testThrottleLeverRespondsInAboutTwoAndAHalfSeconds() {
        double throttle = 0.0;
        int ticks = 0;
        while (throttle < 1.0 && ticks < 100) {
            throttle = FlightTuning.adjustThrottle(throttle, true);
            ticks++;
        }

        assertEquals(50, ticks);
        assertEquals(1.0, throttle, 1.0e-9);
        assertEquals(0.0, FlightTuning.adjustThrottle(0.0, false), 1.0e-9);
        assertEquals(1.0, FlightTuning.adjustThrottle(1.0, true), 1.0e-9);
    }

    @Test
    void testEngineSpoolRespondsFasterInBothDirections() {
        double spool = 0.0;
        int ticks = 0;
        while (spool < 1.0 && ticks < 200) {
            spool = FlightTuning.updateEngineSpool(spool, 1.0);
            ticks++;
        }
        assertTrue(ticks <= 84);
        assertEquals(1.0, spool, 1.0e-9);

        spool = FlightTuning.updateEngineSpool(1.0, 0.0);
        assertEquals(1.0 - FlightTuning.ENGINE_SPOOL_DOWN_RATE, spool, 1.0e-9);
        assertEquals(0.0, FlightTuning.updateEngineSpool(0.01, 0.0), 1.0e-9);
    }

    @Test
    void testPushbackStopsBeforeReversingAndReachesCappedSpeed() {
        FlightTuning.PushbackMotion motion = new FlightTuning.PushbackMotion(0.5, false);
        int stopTicks = 0;
        while (!motion.reversing() && stopTicks < 100) {
            motion = FlightTuning.advancePushback(motion.speed(), motion.reversing());
            stopTicks++;
        }
        assertTrue(motion.reversing());
        assertEquals(0.0, motion.speed(), 1.0e-9);
        assertTrue(stopTicks < 20);

        int ticks = 0;
        while (motion.speed() < FlightTuning.PUSHBACK_TARGET_SPEED && ticks < 100) {
            motion = FlightTuning.advancePushback(motion.speed(), motion.reversing());
            ticks++;
        }
        assertTrue(ticks <= 11);
        assertEquals(FlightTuning.PUSHBACK_TARGET_SPEED, motion.speed(), 1.0e-9);
    }

    @Test
    void testPushbackDoesNotOscillateAtDirectionChangeThreshold() {
        FlightTuning.PushbackMotion motion = FlightTuning.advancePushback(0.01, true);
        assertTrue(motion.reversing());
        assertTrue(motion.speed() > 0.01);
        assertTrue(motion.speed() <= FlightTuning.PUSHBACK_TARGET_SPEED);
    }

    @Test
    void testIlsGuidanceOnRunwayCenterline() {
        double targetHeight = 100.0 * Math.tan(Math.toRadians(3.0));
        FlightInstrumentPayload.IlsGuidance guidance = FlightController.calculateIlsGuidance(
                new Vec3(0.0, targetHeight, -100.0), Vec3.ZERO, 0.0f, 3.0f);

        assertTrue(guidance.active());
        assertEquals(0.0f, guidance.localizerDegrees(), 1e-4f);
        assertEquals(0.0f, guidance.glideslopeDegrees(), 1e-4f);
    }

    @Test
    void testIlsGuidanceReportsLateralAndVerticalDeviation() {
        FlightInstrumentPayload.IlsGuidance guidance = FlightController.calculateIlsGuidance(
                new Vec3(10.0, 0.0, -100.0), Vec3.ZERO, 0.0f, 3.0f);

        assertTrue(guidance.active());
        assertEquals(Math.toDegrees(Math.atan2(10.0, 100.0)), guidance.localizerDegrees(), 1e-4f);
        assertTrue(guidance.glideslopeDegrees() < -2.0f);
    }

    @Test
    void testIlsGuidanceUsesConfiguredRunwayHeading() {
        double targetHeight = 100.0 * Math.tan(Math.toRadians(3.0));
        FlightInstrumentPayload.IlsGuidance guidance = FlightController.calculateIlsGuidance(
                new Vec3(100.0, targetHeight, 0.0), Vec3.ZERO, 90.0f, 3.0f);

        assertTrue(guidance.active());
        assertEquals(0.0f, guidance.localizerDegrees(), 1e-4f);
        assertEquals(0.0f, guidance.glideslopeDegrees(), 1e-4f);
    }

    @Test
    void testIlsGuidanceCarriesConfiguredGlideSlope() {
        FlightInstrumentPayload.IlsGuidance guidance = FlightController.calculateIlsGuidance(
                new Vec3(0.0, 0.0, -100.0), Vec3.ZERO, 0.0f, 4.5f);

        assertEquals(4.5f, guidance.referenceGlideSlopeDegrees(), 1e-4f);
    }

    @Test
    void testIlsGuidanceIsInactiveBehindRunwayThreshold() {
        FlightInstrumentPayload.IlsGuidance guidance = FlightController.calculateIlsGuidance(
                new Vec3(0.0, 10.0, 100.0), Vec3.ZERO, 0.0f, 3.0f);

        assertFalse(guidance.active());
    }

    @Test
    void testPullUpWarningOnlyForAirborneLowDescendingOrTerrainThreat() {
        assertFalse(FlightController.shouldPullUpWarning(false, 40, -1800, false));
        assertFalse(FlightController.shouldPullUpWarning(true, 400, -1800, false));
        assertFalse(FlightController.shouldPullUpWarning(true, 100, -500, false));
        assertTrue(FlightController.shouldPullUpWarning(true, 100, -800, false));
        assertTrue(FlightController.shouldPullUpWarning(true, 800, 0, true));
    }

    @Test
    void testTcasThreatsRequireBothAircraftToBeAirborne() {
        assertFalse(FlightController.shouldWarnTcas(true, false));
        assertFalse(FlightController.shouldWarnTcas(false, true));
        assertFalse(FlightController.shouldWarnTcas(false, false));
        assertTrue(FlightController.shouldWarnTcas(true, true));
    }

    @Test
    void testOverbankAllowsSteepTurnsButCrashesIfSustained() {
        assertTrue(FlightTuning.MAX_BANK_DEGREES > FlightTuning.CRITICAL_BANK_DEGREES);
        assertFalse(FlightController.shouldCrashForOverbank(60.0, 40));
        assertFalse(FlightController.shouldCrashForOverbank(70.0, 19));
        assertTrue(FlightController.shouldCrashForOverbank(70.0, 20));
    }

    @Test
    void testAngleOfAttackCalculation() {
        double pitch = 12.0; // nose pitched up 12 deg
        double flightPathAngle = 4.0; // climbing at 4 deg
        double aoa = pitch - flightPathAngle;
        assertEquals(8.0, aoa, 1e-4);

        // Critical AoA stall check
        assertTrue(aoa < FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES);

        double stallPitch = 25.0;
        double levelFlightPath = 0.0;
        double stallAoa = stallPitch - levelFlightPath;
        assertTrue(stallAoa > FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES);
    }
}
