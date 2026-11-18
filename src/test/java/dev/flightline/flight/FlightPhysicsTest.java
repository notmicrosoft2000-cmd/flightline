package dev.flightline.flight;

import dev.flightline.network.FlightInstrumentPayload;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightPhysicsTest {

    @Test
    void testAircraftAttitudeVector() {
        // Level, facing +Z.
        Vec3 attitude = FlightMath.aircraftAttitudeVector(0.0, 0.0);
        assertEquals(0.0, attitude.x, 1e-4);
        assertEquals(0.0, attitude.y, 1e-4);
        assertEquals(1.0, attitude.z, 1e-4);

        // 30 degrees nose up, facing -X.
        attitude = FlightMath.aircraftAttitudeVector(30.0, 90.0);
        assertEquals(-0.8660254, attitude.x, 1e-4);
        assertEquals(0.5, attitude.y, 1e-4);
        assertEquals(0.0, attitude.z, 1e-4);
        assertEquals(1.0, attitude.length(), 1e-4);
    }

    @Test
    void testCoordinatedTurnRate() {
        double turnRate = FlightMath.coordinatedTurnRateRadiansPerTick(20.0, 2.0);
        assertTrue(turnRate > 0.0);
        // Bank left should give the opposite turn rate, and a faster aircraft turns more slowly.
        assertEquals(-turnRate, FlightMath.coordinatedTurnRateRadiansPerTick(-20.0, 2.0), 1e-4);
        assertTrue(FlightMath.coordinatedTurnRateRadiansPerTick(20.0, 4.0) < turnRate);
    }

    @Test
    void testPilotTurnInputRespondsQuicklyAndReleasesGradually() {
        assertTrue(FlightTuning.BANK_RESPONSE_DEGREES_PER_TICK
                > FlightTuning.BANK_RELEASE_RESPONSE_DEGREES_PER_TICK);
        assertTrue(FlightTuning.TURN_RATE_FACTOR > 1.0);
    }

    @Test
    void testBankKeysHaveExplicitLeftAndRightAircraftDirections() {
        assertEquals(-FlightTuning.MAX_BANK_DEGREES,
                FlightController.bankTargetFromControls(FlightControls.LEFT, false));
        assertEquals(FlightTuning.MAX_BANK_DEGREES,
                FlightController.bankTargetFromControls(FlightControls.RIGHT, false));
        assertEquals(0.0, FlightController.bankTargetFromControls(
                FlightControls.LEFT | FlightControls.RIGHT, false));
    }

    @Test
    void testGroundEffectCushioning() {
        // At flare height the cushion is strong but bounded, with the production lift and drag gains.
        double flareFactor = FlightMath.groundEffectFactor(2.0);
        assertTrue(flareFactor > 0.8);
        assertTrue(flareFactor <= 1.0);
        assertEquals(1.32, FlightMath.groundEffectLiftMultiplier(1.0), 1e-4);
        assertEquals(0.70, FlightMath.groundEffectDragMultiplier(1.0), 1e-4);

        double baseLift = 0.10;
        assertTrue(baseLift * FlightMath.groundEffectLiftMultiplier(flareFactor) > baseLift);
        double baseDrag = 0.005;
        assertTrue(baseDrag * FlightMath.groundEffectDragMultiplier(flareFactor) < baseDrag);

        // No effect at or above the height limit, and no multiplier change at zero factor.
        assertEquals(0.0, FlightMath.groundEffectFactor(FlightMath.GROUND_EFFECT_HEIGHT_BLOCKS), 1e-4);
        assertEquals(0.0, FlightMath.groundEffectFactor(50.0), 1e-4);
        assertEquals(1.0, FlightMath.groundEffectLiftMultiplier(0.0), 1e-4);
        assertEquals(1.0, FlightMath.groundEffectDragMultiplier(0.0), 1e-4);

        // Cushioning grows monotonically as the aircraft descends.
        assertTrue(FlightMath.groundEffectFactor(3.0) > FlightMath.groundEffectFactor(9.0));
        assertTrue(FlightMath.groundEffectFactor(9.0) > 0.0);
    }

    @Test
    void testCrashKnockbackScaling() {
        // Low-speed impact: knockback is floored at 1.5; radius grows from base 6 blocks.
        double lowSpeedImpact = 0.4;
        assertEquals(1.5, AircraftImpact.knockbackStrength(lowSpeedImpact), 1e-4);
        assertEquals(9.2, AircraftImpact.impactRadius(lowSpeedImpact), 1e-4);

        // High-speed impact: knockback caps at 6.5; radius caps at 24 blocks.
        double highSpeedImpact = 3.0;
        assertEquals(6.5, AircraftImpact.knockbackStrength(highSpeedImpact), 1e-4);
        assertEquals(24.0, AircraftImpact.impactRadius(highSpeedImpact), 1e-4);

        // Scaling: moderate impact stays within bounds.
        double midSpeedImpact = 1.0;
        assertEquals(Math.max(1.5, Math.min(6.5, 1.0 * 3.6)), AircraftImpact.knockbackStrength(midSpeedImpact), 1e-4);
        assertEquals(Math.min(24.0, 6.0 + 1.0 * 8.0), AircraftImpact.impactRadius(midSpeedImpact), 1e-4);
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
    void autolandFlareLimitsDescentRateNearTouchdown() {
        assertEquals(-0.035, FlightController.autolandFlareVerticalSpeed(-0.2, 60));
        assertEquals(-0.02, FlightController.autolandFlareVerticalSpeed(-0.1, 20));
        assertEquals(0.03, FlightController.autolandFlareVerticalSpeed(0.03, 10));
        assertEquals(-0.2, FlightController.autolandFlareVerticalSpeed(-0.2, 61));
    }

    @Test
    void testAutothrottleDeadbandIsTheDisplayedTwoKnots() {
        assertEquals(2.0, FlightTuning.AUTOTHROTTLE_DEADBAND_KNOTS);
    }

    @Test
    void overspeedWarningAllowsNormalAutopilotCruiseButFlagsNearLimit() {
        double autopilotCruise = 160.0 / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
        assertFalse(FlightTuning.isOverspeed(autopilotCruise));
        assertTrue(FlightTuning.isOverspeed(FlightTuning.MAX_AIRSPEED));
    }

    @Test
    void sustainedOverspeedBuildsAirframeStressAndCausesDamage() {
        int stressTicks = 0;
        for (int tick = 0; tick < FlightTuning.OVERSPEED_DAMAGE_START_TICKS; tick++) {
            stressTicks = FlightTuning.advanceOverspeedStressTicks(stressTicks, true);
        }
        assertTrue(stressTicks >= FlightTuning.OVERSPEED_STRESS_START_TICKS);
        assertTrue(FlightTuning.overspeedLiftFactor(stressTicks) < 1.0);
        assertTrue(FlightTuning.shouldApplyOverspeedDamage(stressTicks, 0));
        assertFalse(FlightTuning.shouldApplyOverspeedDamage(stressTicks,
                FlightTuning.OVERSPEED_DAMAGE_INTERVAL_TICKS));
        assertEquals(stressTicks - 2,
                FlightTuning.advanceOverspeedStressTicks(stressTicks, false));
    }

    @Test
    void generatedRunwaysMeetMinimumSurvivalLandingDimensions() {
        assertEquals(400, RunwayGenerator.RUNWAY_LENGTH);
        assertTrue(RunwayGenerator.RUNWAY_WIDTH >= 20);
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
    void icingBuildsInColdPrecipitationAndCanBeRemovedByAntiIce() {
        WeatherSimulation.Conditions coldRain = WeatherSimulation.sample(true, false, 0.0f, 1000L, 42L);
        WeatherSimulation.Conditions warmRain = WeatherSimulation.sample(true, false, 0.8f, 1000L, 42L);

        assertTrue(coldRain.icingConditions());
        assertFalse(warmRain.icingConditions());
        double icing = 50.0;
        assertTrue(WeatherSimulation.advanceIcing(icing, true, false) > icing);
        assertTrue(WeatherSimulation.advanceIcing(icing, false, true) < icing);
    }

    @Test
    void flapsReduceStallSpeedWhileIcingRaisesItAndSpoilersDumpLift() {
        double cleanStallSpeed = FlightTuning.icedStallSpeed(0.0, 0.0);
        assertTrue(FlightTuning.icedStallSpeed(0.0, 1.0) < cleanStallSpeed);
        assertTrue(FlightTuning.icedStallSpeed(100.0, 0.0) > cleanStallSpeed);
        assertTrue(FlightTuning.icingLiftFactor(100.0) < FlightTuning.icingLiftFactor(0.0));
        assertTrue(FlightTuning.spoilerLiftFactor(true) < FlightTuning.spoilerLiftFactor(false));
    }

    @Test
    void engineOutGlideLowersTheStallMarginInsteadOfStallingImmediately() {
        double cleanStall = FlightTuning.icedStallSpeed(0.0, 0.0);
        assertEquals(cleanStall, FlightTuning.engineOutStallSpeed(cleanStall, true), 1e-9);
        double glideStall = FlightTuning.engineOutStallSpeed(cleanStall, false);
        assertTrue(glideStall < cleanStall);

        // An engine failure on approach must not instantly stall at a speed the wing can still fly.
        double approachSpeed = glideStall * 1.02;
        assertTrue(approachSpeed > glideStall);
        double glideSpeed = FlightTuning.engineOutGlideSpeed(glideStall);
        assertTrue(approachSpeed < glideSpeed);
        assertTrue(FlightTuning.engineOutGlideAcceleration(approachSpeed, glideSpeed, false) > 0.0);
        assertEquals(0.0,
                FlightTuning.engineOutGlideAcceleration(glideSpeed * 1.05, glideSpeed, false), 1e-9);
        assertEquals(0.0,
                FlightTuning.engineOutGlideAcceleration(approachSpeed, glideSpeed, true), 1e-9);
    }

    @Test
    void engineOutAircraftRetainsUsefulGlideTimeBeforeReachingGlideStallSpeed() {
        assertTrue(FlightTuning.engineOutDragFactor(false)
                < FlightTuning.engineOutDragFactor(true));
        double stallSpeed = FlightTuning.engineOutStallSpeed(
                FlightTuning.icedStallSpeed(0.0, 0.0), false);
        double drag = 0.00045 / FlightTuning.WORLD_SPEED_SCALE
                * FlightTuning.engineOutDragFactor(false);
        double speed = FlightTuning.ROTATION_SPEED;
        int ticks = 0;
        while (speed > stallSpeed && ticks < 2000) {
            speed -= speed * speed * drag;
            ticks++;
        }
        assertTrue(ticks > 120,
                "engine-out glide should give the pilot time to land, reached stall in " + ticks);
    }

    @Test
    void testWeatherSimulationIncreasesTurbulenceAndReducesVisibility() {
        WeatherSimulation.Conditions clear = WeatherSimulation.sample(false, false, 1000L, 42L);
        WeatherSimulation.Conditions rain = WeatherSimulation.sample(true, false, 1000L, 42L);
        WeatherSimulation.Conditions storm = WeatherSimulation.sample(true, true, 1000L, 42L);

        assertTrue(clear.visibilityBlocks() > rain.visibilityBlocks());
        assertTrue(rain.visibilityBlocks() > storm.visibilityBlocks());
        assertTrue(clear.turbulencePercent() < rain.turbulencePercent());
        assertTrue(rain.turbulencePercent() < storm.turbulencePercent());
        assertTrue(storm.windSpeedKnots() > clear.windSpeedKnots());
        assertTrue(storm.windSpeedKnots() <= 90);
    }

    @Test
    void testWindshearOnlyOccursDuringThunderstormAtScheduledIntervals() {
        assertFalse(WeatherSimulation.isWindshearEvent(false, 600L, 0L));
        assertTrue(WeatherSimulation.isWindshearEvent(true, 600L, 0L));
        assertFalse(WeatherSimulation.isWindshearEvent(true, 601L, 0L));
    }

    @Test
    void testIlsGuidanceOnRunwayCenterline() {
        double targetHeight = 100.0 * Math.tan(Math.toRadians(3.0));
        FlightInstrumentPayload.IlsGuidance guidance = FlightController.calculateIlsGuidance(
                new Vec3(0.0, targetHeight, -100.0), Vec3.ZERO, 0.0f, 3.0f);

        assertTrue(guidance.active());
        assertEquals(0.0f, guidance.localizerDegrees(), 1e-4f);
        assertEquals(0.0f, guidance.glideslopeDegrees(), 1e-4f);
        assertEquals(0.0f, guidance.runwayHeadingDegrees(), 1e-4f);
        assertEquals(100.0f, guidance.thresholdDistanceBlocks(), 1e-4f);
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
    void testAutolandTracksRunwayCenterlineAndGlidePath() {
        double targetHeight = 100.0 * Math.tan(Math.toRadians(3.0));
        FlightController.AutolandTargets targets = FlightController.calculateAutolandTargets(
                new Vec3(0.0, targetHeight, -100.0), Vec3.ZERO, 0.0f, 3.0f);

        assertNotNull(targets);
        assertEquals(0.0, targets.targetHeadingDegrees(), 1.0e-4);
        assertEquals(Math.round(targetHeight * 3.28084), targets.targetAltitudeFeet());
        assertEquals(130, targets.targetAirspeedKnots());
        assertTrue(targets.targetVerticalSpeedFpm() < 0);
    }

    @Test
    void testAutolandLocalizerCorrectionTurnsTowardRunwayCenterline() {
        FlightController.AutolandTargets targets = FlightController.calculateAutolandTargets(
                new Vec3(10.0, 0.0, -100.0), Vec3.ZERO, 0.0f, 3.0f);

        assertNotNull(targets);
        assertTrue(targets.targetHeadingDegrees() > 0.0);
    }

    @Test
    void testAutolandRequiresApproachCaptureVolume() {
        assertNull(FlightController.calculateAutolandTargets(
                new Vec3(501.0, 0.0, -100.0), Vec3.ZERO, 0.0f, 3.0f));
        assertNull(FlightController.calculateAutolandTargets(
                new Vec3(0.0, 0.0, -5001.0), Vec3.ZERO, 0.0f, 3.0f));
    }

    @Test
    void testPullUpWarningOnlyForAirborneLowDescendingOrTerrainThreat() {
        assertFalse(FlightController.shouldPullUpWarning(false, 40, -1800, false, false));
        assertFalse(FlightController.shouldPullUpWarning(true, 400, -1800, false, false));
        assertFalse(FlightController.shouldPullUpWarning(true, 100, -500, false, false));
        assertTrue(FlightController.shouldPullUpWarning(true, 100, -1500, false, false));
        assertTrue(FlightController.shouldPullUpWarning(true, 800, 0, true, true));
    }

    @Test
    void normalLandingDoesNotTriggerGpwsSinkRateOrPullUp() {
        for (int radioAltitude = 1000; radioAltitude >= 30; radioAltitude -= 10) {
            assertFalse(FlightController.shouldSinkRateWarning(true, radioAltitude, -700, true),
                    "stable approach sink rate must be silent at " + radioAltitude + " ft");
            assertFalse(FlightController.shouldPullUpWarning(true, radioAltitude, -700, false, true),
                    "stable approach must not command pull-up at " + radioAltitude + " ft");
        }
        assertFalse(FlightController.shouldSinkRateWarning(true, 0, -900, true));
    }

    @Test
    void excessiveSinkRateStillTriggersGpws() {
        assertTrue(FlightController.shouldSinkRateWarning(true, 500, -3000, false));
        assertTrue(FlightController.shouldSinkRateWarning(true, 100, -2000, true));
        assertFalse(FlightController.shouldSinkRateWarning(false, 100, -3000, false));
        assertFalse(FlightController.shouldSinkRateWarning(true, 3000, -5000, false));
    }

    @Test
    void flatRunwayAheadIsNotMistakenForRisingTerrain() {
        assertFalse(FlightController.isDescendingTerrainThreat(true, -0.2, 10.0, 0, 100));
        assertFalse(FlightController.isDescendingTerrainThreat(true, -0.2, 10.0, 20, 100));
        assertFalse(FlightController.isDescendingTerrainThreat(true, -0.2, 10.0, 50, 100));
        assertTrue(FlightController.isDescendingTerrainThreat(true, -0.2, 10.0, 80, 300));
        assertFalse(FlightController.isDescendingTerrainThreat(false, -0.2, 10.0, 80, 300));
    }

    @Test
    void takeoffAcceleratesToRotationInAGameplayFriendlyTime() {
        double speed = 0.0;
        double throttle = 0.0;
        double spool = 0.0;
        int ticks = 0;
        while (speed < FlightTuning.ROTATION_SPEED && ticks < 200) {
            throttle = FlightTuning.adjustThrottle(throttle, true);
            spool = FlightTuning.updateEngineSpool(spool, 0.04 + throttle * 0.96);
            speed = FlightTuning.advanceGroundSpeed(speed, spool, 2, 0.0);
            ticks++;
        }
        assertTrue(speed >= FlightTuning.ROTATION_SPEED);
        assertTrue(ticks <= 105, "takeoff roll should reach rotation in about five seconds, was " + ticks + " ticks");
    }

    @Test
    void testTcasThreatsRequireBothAircraftToBeAirborne() {
        assertFalse(FlightController.shouldWarnTcas(true, false));
        assertFalse(FlightController.shouldWarnTcas(false, true));
        assertFalse(FlightController.shouldWarnTcas(false, false));
        assertTrue(FlightController.shouldWarnTcas(true, true));
    }

    @Test
    void overbankIsAerodynamicPenaltyNotAnInstantCrash() {
        assertTrue(FlightTuning.MAX_BANK_DEGREES > FlightTuning.CRITICAL_BANK_DEGREES);
        assertFalse(FlightController.isOverbank(60.0));
        assertTrue(FlightController.isOverbank(65.0));
        assertTrue(FlightController.isOverbank(70.0));
    }

    @Test
    void testAngleOfAttackCalculation() {
        // 12 degrees nose up while climbing along a 4 degree flight path.
        double horizontalSpeed = 100.0;
        double verticalSpeed = horizontalSpeed * Math.tan(Math.toRadians(4.0));
        double aoa = FlightMath.angleOfAttackDegrees(12.0, verticalSpeed, horizontalSpeed);
        assertEquals(8.0, aoa, 1e-4);
        assertTrue(aoa < FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES);

        // Level flight at 25 degrees nose up exceeds the critical angle.
        double stallAoa = FlightMath.angleOfAttackDegrees(25.0, 0.0, horizontalSpeed);
        assertTrue(stallAoa > FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES);

        // Tiny horizontal speeds are clamped before the flight-path calculation.
        assertEquals(10.0, FlightMath.angleOfAttackDegrees(10.0, 0.0, 0.0), 1e-4);
    }

    @Test
    void testAircraftTypeProfiles() {
        AircraftType kasa = AircraftType.KASA_A380;
        assertEquals(2, kasa.engineCount());
        assertEquals(172, kasa.maxAirspeedKnots());
        assertEquals(140, kasa.cruiseAirspeedKnots());
        assertEquals(1.0f, kasa.turbulenceSensitivity(), 1e-4);
        assertEquals(100.0, kasa.fuelCapacityPercent(), 1e-4);

        AircraftType gina = AircraftType.GINA_787;
        assertEquals(6, gina.engineCount());
        assertEquals(280, gina.maxAirspeedKnots());
        assertEquals(245, gina.cruiseAirspeedKnots());
        assertTrue(gina.maxAirspeedKnots() > 250, "Gina must be able to operate above 250 kt");
        assertTrue(gina.cruiseAirspeedKnots() > 240);
        assertEquals(0.55f, gina.turbulenceSensitivity(), 1e-4);
        assertTrue(gina.turbulenceSensitivity() < kasa.turbulenceSensitivity(), "Gina 787 should have damped turbulence");
        assertTrue(gina.weight() > kasa.weight(), "Gina is the heavier airframe");
        assertTrue(gina.fuelConsumptionRate() > kasa.fuelConsumptionRate(), "Gina burns more fuel");
        assertTrue(gina.maxAirspeedBlocksPerTick() > kasa.maxAirspeedBlocksPerTick());
    }

    @Test
    void overspeedUsesEachAircraftsOwnLimit() {
        double ginaCruise = AircraftType.GINA_787.cruiseAirspeedKnots()
                / FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
        assertFalse(FlightTuning.isOverspeed(ginaCruise, AircraftType.GINA_787));
        assertTrue(FlightTuning.isOverspeed(AircraftType.GINA_787.maxAirspeedBlocksPerTick() * 1.01,
                AircraftType.GINA_787));
        // 245 kt is far above the Kasa limit, so the same speed must trip the smaller airframe.
        assertTrue(FlightTuning.isOverspeed(ginaCruise, AircraftType.KASA_A380));
    }

    @Test
    void airbrakesAddDragButPreserveMostLift() {
        assertTrue(FlightTuning.AIRBRAKE_DRAG > 0.0);
        assertTrue(FlightTuning.AIRBRAKE_LIFT_FACTOR > FlightTuning.spoilerLiftFactor(true),
                "Airbrakes should keep far more lift than spoilers");
        assertTrue(FlightTuning.AIRBRAKE_LIFT_FACTOR < 1.0);
    }

    @Test
    void fuelCapacityAndBurnScaleWithAirframe() {
        assertTrue(FlightTuning.FUEL_DRAIN_PER_ENGINE_PER_TICK > 0.0);
        assertTrue(FlightTuning.FUEL_LOW_PERCENT > FlightTuning.FUEL_EMPTY_PERCENT);
        assertEquals(85.0, AircraftType.GINA_787.fuelCapacityPercent(), 1e-4);
    }

    @Test
    void thunderstormGustsExceedCalmConditions() {
        WeatherSimulation.Conditions calm = WeatherSimulation.sample(false, false, 0.8f, 1000L, 7L);
        WeatherSimulation.Conditions storm = WeatherSimulation.sample(false, true, 0.8f, 1000L, 7L);
        assertTrue(storm.gustKnots() > calm.gustKnots());
        assertTrue(storm.windSpeedKnots() >= calm.windSpeedKnots());
        assertTrue(storm.gustKnots() >= storm.windSpeedKnots());
        assertTrue(storm.turbulencePercent() > calm.turbulencePercent());
    }

    @Test
    void testAircraftTypeSelectionFromId() {
        assertSame(AircraftType.KASA_A380, AircraftType.fromId("kasa"));
        assertSame(AircraftType.KASA_A380, AircraftType.fromId("a380"));
        assertSame(AircraftType.KASA_A380, AircraftType.fromId("Kasa A380"));
        assertSame(AircraftType.GINA_787, AircraftType.fromId("gina"));
        assertSame(AircraftType.GINA_787, AircraftType.fromId("787"));
        assertSame(AircraftType.GINA_787, AircraftType.fromId("The Gina 787"));
    }

    @Test
    void testGina787SixEngineHighSpeedPerformance() {
        // Test ground acceleration with 6 engines for Gina vs 2 engines for Kasa
        double kasaAcc = FlightTuning.groundAcceleration(1.0, 1.0, 2, 0.0, AircraftType.KASA_A380);
        double ginaAcc = FlightTuning.groundAcceleration(1.0, 1.0, 6, 0.0, AircraftType.GINA_787);

        assertTrue(ginaAcc > kasaAcc, "Gina 787 with 6 giant engines should produce more acceleration than Kasa");
        assertTrue(AircraftType.GINA_787.maxAirspeedBlocksPerTick() > AircraftType.KASA_A380.maxAirspeedBlocksPerTick());
    }
}
