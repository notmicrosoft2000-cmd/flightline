package dev.flightline.flight;

import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned player flight, ground roll, aircraft attitude and basic warnings. */
public final class FlightController {
    private static final int VALID_CONTROLS = FlightControls.FORWARD | FlightControls.REVERSE
            | FlightControls.LEFT | FlightControls.RIGHT | FlightControls.BRAKE;
    private static final int ENGINE_OFF = 0;
    private static final int ENGINE_STARTING = 1;
    private static final int ENGINE_RUNNING = 2;
    private static final int ENGINE_FAILED = 3;
    private static final int ENGINE_FIRE = 4;
    private static final int ENGINE_START_TICKS = 60;
    private static final double TRAFFIC_SCOPE_BLOCKS = 128.0;
    private static final ResourceKey<DamageType> AIRCRAFT_CRASH_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("flightline", "aircraft_crash"));
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> FLIGHT_DISABLED = new HashSet<>();

    private FlightController() {
    }

    public static void enable(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID()) || FLIGHT_DISABLED.contains(player.getUUID())) return;
        if (!player.isAlive() || player.isSpectator() || player.isPassenger()) {
            return;
        }

        Session session = new Session(player.isNoGravity());
        session.headingDegrees = player.getYRot();
        Vec3 velocity = player.getDeltaMovement();
        session.groundSpeed = velocity.horizontalDistance();
        session.airVelocity = velocity;
        session.airspeed = (float) session.groundSpeed;
        session.airborne = !player.onGround();
        session.phase = session.airborne ? FlightPhase.AIRBORNE : FlightPhase.TAXI;
        SESSIONS.put(player.getUUID(), session);
        player.setNoGravity(session.airborne || session.hadNoGravity);
    }

    public static void updateInput(ServerPlayer player, int controls) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.controls = controls & VALID_CONTROLS;
        }
    }

    public static boolean isFlightActive(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    public static boolean isAirborne(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.airborne && !session.crashed;
    }

    public static boolean setIlsReference(ServerPlayer player, float glideSlopeDegrees) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !Float.isFinite(glideSlopeDegrees)
                || glideSlopeDegrees < 1.0f || glideSlopeDegrees > 6.0f) return false;
        session.ilsDimension = player.level().dimension();
        session.ilsThreshold = player.position();
        session.ilsRunwayHeading = player.getYRot();
        session.ilsGlideSlopeDegrees = glideSlopeDegrees;
        sendState(player, session, true);
        return true;
    }

    public static boolean clearIlsReference(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return false;
        session.ilsDimension = null;
        session.ilsThreshold = Vec3.ZERO;
        session.ilsGlideSlopeDegrees = 3.0f;
        session.ilsGuidance = FlightInstrumentPayload.IlsGuidance.inactive();
        sendState(player, session, true);
        return true;
    }

    public static void disableFlight(ServerPlayer player) {
        FLIGHT_DISABLED.add(player.getUUID());
        remove(player.getUUID(), player);
        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(0.0, velocity.y, 0.0);
        player.resetFallDistance();
        sendState(player, null, false);
    }

    public static void enableFlight(ServerPlayer player) {
        FLIGHT_DISABLED.remove(player.getUUID());
        enable(player);
        sendState(player, SESSIONS.get(player.getUUID()), true);
    }

    /** Returns true when the normal Minecraft fall should be replaced by aircraft impact logic. */
    public static boolean handleFallImpact(ServerPlayer player, double fallDistance) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.airborne) return false;
        if (session.crashed || session.impactHandled) return true;
        session.impactHandled = true;

        double sinkSpeed = Math.max(0.0, -session.airVelocity.y);
        double horizontalSpeed = session.airVelocity.horizontalDistance();
        LandingAssessment landing = assessLanding(session.gearDown, sinkSpeed, horizontalSpeed);
        if (!landing.crashes()) {
            session.gearFailed = landing.gearFailed();
            float damage = Math.min(landing.damage(), Math.max(0.0f, player.getHealth() - 1.0f));
            if (damage > 0.0f) {
                player.hurtServer(player.level(), player.damageSources().generic(), damage);
            }
            return true;
        }
        session.gearFailed = true;
        crashAircraft(player, session, Math.max(sinkSpeed * 4.5,
                horizontalSpeed - FlightTuning.SAFE_TOUCHDOWN_SPEED));
        return true;
    }

    static LandingAssessment assessLanding(boolean gearDown, double sinkSpeed, double horizontalSpeed) {
        if (gearDown && sinkSpeed <= FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED
                && horizontalSpeed <= FlightTuning.SAFE_TOUCHDOWN_SPEED) {
            return new LandingAssessment(false, false, 0.0f);
        }
        if (gearDown && sinkSpeed <= FlightTuning.MAX_HARD_LANDING_SINK_SPEED
                && horizontalSpeed <= FlightTuning.MAX_HARD_LANDING_SPEED) {
            double sinkRatio = sinkSpeed / FlightTuning.MAX_HARD_LANDING_SINK_SPEED;
            double excessSpeedRatio = Math.max(0.0,
                    (horizontalSpeed - FlightTuning.SAFE_TOUCHDOWN_SPEED) / FlightTuning.MAX_HARD_LANDING_SPEED);
            float damage = (float) Math.min(6.0, 1.0 + sinkRatio * 3.0 + excessSpeedRatio * 2.0);
            boolean gearFailed = sinkSpeed > FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED
                    || horizontalSpeed > FlightTuning.SAFE_TOUCHDOWN_SPEED;
            return new LandingAssessment(false, gearFailed, damage);
        }
        return new LandingAssessment(true, true, 0.0f);
    }

    record LandingAssessment(boolean crashes, boolean gearFailed, float damage) {
    }

    private static void crashAircraft(ServerPlayer player, Session session, double impactSpeed) {
        if (session.crashed) return;
        Vec3 impactVelocity = session.airborne ? session.airVelocity
                : headingVector(session.headingDegrees).scale(session.groundSpeed);
        session.crashed = true;
        session.airborne = false;
        session.autopilot = false;
        session.landingRoll = false;
        session.engine1State = ENGINE_OFF;
        session.engine2State = ENGINE_OFF;
        session.throttle = 0.0;
        session.pushback = false;
        session.spoilers = true;
        session.crashTicksRemaining = 6;
        session.airVelocity = new Vec3(-impactVelocity.x * 0.18,
                Math.max(0.06, -impactVelocity.y * 0.12), -impactVelocity.z * 0.18);
        player.setNoGravity(false);
        player.resetFallDistance();
        setPhase(player, session, FlightPhase.CRASHED);

        float fatalCrashDamage = (float)Math.max(player.getHealth() + 1000.0, impactSpeed * 1000.0);
        DamageSource crashDamage = new DamageSource(player.level().registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(AIRCRAFT_CRASH_TYPE));
        player.hurtServer(player.level(), crashDamage, fatalCrashDamage);

        AircraftImpact.apply(player.level(), player, impactSpeed);
    }

    public static void toggleGear(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.gearFailed) {
            return;
        }
        if (session.airborne) {
            session.gearDown = !session.gearDown;
        } else {
            session.gearGroundAlarmTicks = 40;
        }
        sendState(player, session, true);
    }

    /** Starts both engines in sequence, with each starter running for three seconds. */
    public static void startEngines(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.crashed) {
            resetCrashedAircraft(player, session);
            return;
        }
        if (session == null || player.getFoodData().getFoodLevel() <= 0
                || session.engine1State != ENGINE_OFF || session.engine2State != ENGINE_OFF) {
            return;
        }
        session.throttle = 0.0;
        if (session.pushback) {
            session.groundSpeed = 0.0;
        }
        session.pushback = false;
        session.engine1State = ENGINE_STARTING;
        session.engineStartTicks = 0;
        sendState(player, session, true);
    }

    /** A respawn always receives a fresh, ground-ready aircraft session. */
    public static void resetAfterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        remove(oldPlayer.getUUID(), oldPlayer);
        newPlayer.setNoGravity(false);
        newPlayer.setDeltaMovement(Vec3.ZERO);
        newPlayer.resetFallDistance();
        if (FLIGHT_DISABLED.contains(oldPlayer.getUUID())) {
            FLIGHT_DISABLED.add(newPlayer.getUUID());
            sendState(newPlayer, null, false);
            return;
        }

        Session session = new Session(false);
        session.headingDegrees = newPlayer.getYRot();
        session.phase = FlightPhase.TAXI;
        SESSIONS.put(newPlayer.getUUID(), session);
        sendState(newPlayer, session, true);
    }

    private static void resetCrashedAircraft(ServerPlayer player, Session session) {
        session.controls = 0;
        session.takeoffGraceTicks = 0;
        session.takeoffWarningTicks = 0;
        session.engineStartTicks = 0;
        session.gearHeatTicks = 0;
        session.gearGroundAlarmTicks = 0;
        session.criticalBankTicks = 0;
        session.windShearTicks = 0;
        session.engineHotTicks = 0;
        session.engineFireTicks = 0;
        session.flapDetent = 0;
        session.flapPosition = 0.0;
        session.radioAltitudeFeet = 0;
        session.verticalSpeedFpm = 0;
        session.warningFlags = 0;
        session.groundSpeed = 0.0;
        session.throttle = 0.0;
        session.engineSpool = 0.0;
        session.bankDegrees = 0.0;
        session.pitchDegrees = 0.0;
        session.headingDegrees = player.getYRot();
        session.groundTurnRate = 0.0;
        session.airspeed = 0.0f;
        session.airVelocity = Vec3.ZERO;
        session.airborne = false;
        session.autopilot = false;
        session.gearDown = true;
        session.gearFailed = false;
        session.engine1State = ENGINE_OFF;
        session.engine2State = ENGINE_OFF;
        session.brakesActive = false;
        session.pushback = false;
        session.spoilers = false;
        session.landingRoll = false;
        session.crashed = false;
        session.crashTicksRemaining = 0;
        session.impactHandled = false;
        session.engineTemperature = 20.0;
        session.phase = FlightPhase.TAXI;
        player.setNoGravity(false);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        applyHeading(player, session);
        sendState(player, session, true);
    }

    public static void shutdownEngines(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return;
        session.engine1State = ENGINE_OFF;
        session.engine2State = ENGINE_OFF;
        session.engineStartTicks = 0;
        session.throttle = 0.0;
        sendState(player, session, true);
    }

    public static void adjustFlaps(ServerPlayer player, int delta) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.flapDetent = Math.max(0, Math.min(4, session.flapDetent + delta));
            sendState(player, session, true);
        }
    }

    public static void toggleSpoilers(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.spoilers = !session.spoilers;
            sendState(player, session, true);
        }
    }

    public static void setSpoilers(ServerPlayer player, boolean deployed) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            if (deployed && session.spoilers) {
                session.spoilers = false;
            } else {
                session.spoilers = deployed;
            }
            sendState(player, session, true);
        }
    }

    public static void togglePushback(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.airborne) {
            return;
        }
        if (session.pushback) {
            session.pushback = false;
            session.groundSpeed = 0.0;
            session.pushbackReversing = false;
        } else {
            session.throttle = 0.0;
            session.pushback = true;
            session.pushbackReversing = session.groundSpeed <= FlightTuning.PUSHBACK_STOPPING_DECELERATION;
            if (session.pushbackReversing) session.groundSpeed = 0.0;
        }
        sendState(player, session, true);
    }

    public static void tick(MinecraftServer server) {
        MobFlightController.tick(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Session session = SESSIONS.get(player.getUUID());
            if (session == null) {
                enable(player);
                session = SESSIONS.get(player.getUUID());
                if (session == null) continue;
            }
            if (!player.isAlive() || player.isSpectator() || player.isPassenger()) {
                remove(player.getUUID(), player);
                sendState(player, null, false);
                continue;
            }

            simulate(player, session, server);
            sendState(player, session, true);
        }
    }

    public static void handleMobCollision(ServerPlayer player, net.minecraft.world.entity.Mob mob,
                                          Vec3 mobVelocity) {
        Session playerSession = SESSIONS.get(player.getUUID());
        if (playerSession == null || playerSession.crashed) return;
        if (!playerSession.airborne && playerSession.groundSpeed < 0.70 * FlightTuning.WORLD_SPEED_SCALE) return;
        Vec3 playerVelocity = playerSession.airborne ? playerSession.airVelocity
                : headingVector(playerSession.headingDegrees).scale(playerSession.groundSpeed);
        double relativeSpeed = playerVelocity.subtract(mobVelocity).length();
        if (relativeSpeed > 0.35) crashAircraft(player, playerSession, relativeSpeed);
    }

    public static void remove(UUID playerId, ServerPlayer player) {
        Session session = SESSIONS.remove(playerId);
        if (session != null && player != null) {
            player.setNoGravity(session.hadNoGravity);
        }
    }

    private static void simulate(ServerPlayer player, Session session, MinecraftServer server) {
        if (session.crashed) {
            player.setNoGravity(false);
            if (session.crashTicksRemaining > 0) {
                session.crashTicksRemaining--;
                player.setDeltaMovement(session.airVelocity);
                session.airVelocity = session.airVelocity.scale(0.62).add(0.0, -0.025, 0.0);
            } else {
                player.setDeltaMovement(Vec3.ZERO);
            }
            updateInstruments(player, session, server);
            return;
        }
        updateEngineStart(session);
        updateThrottle(session);
        updateEngineSpool(session);
        updateWeather(player, session);
        updateFlaps(session);
        updateEngineTemperatures(session);
        updateFuel(player, session);
        session.brakesActive = !session.airborne && (session.controls & FlightControls.BRAKE) != 0;
        boolean onGround = player.onGround();

        if (session.airborne && !onGround
                && (player.horizontalCollision || player.verticalCollision)) {
            crashAircraft(player, session, session.airVelocity.length());
            updateInstruments(player, session, server);
            return;
        }
        if (!session.airborne && player.horizontalCollision
                && session.groundSpeed > 0.70 * FlightTuning.WORLD_SPEED_SCALE) {
            crashAircraft(player, session, session.groundSpeed);
            updateInstruments(player, session, server);
            return;
        }
        if (session.airborne) {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == player || other.level() != player.level()) continue;
                Session otherSession = SESSIONS.get(other.getUUID());
                if (otherSession == null || otherSession.crashed
                        || player.distanceToSqr(other) > 1.25 * 1.25) continue;
                Vec3 ownVelocity = session.airborne ? session.airVelocity
                        : headingVector(session.headingDegrees).scale(session.groundSpeed);
                Vec3 otherVelocity = otherSession.airborne ? otherSession.airVelocity
                        : headingVector(otherSession.headingDegrees).scale(otherSession.groundSpeed);
                double impactSpeed = ownVelocity.subtract(otherVelocity).length();
                if (impactSpeed < 0.35) continue;
                crashAircraft(player, session, impactSpeed);
                crashAircraft(other, otherSession, impactSpeed);
                updateInstruments(player, session, server);
                return;
            }
        }

        // Camera pitch is only an aircraft control in flight. On the runway it must not
        // pre-load the aircraft into a stall before it has even lifted off.
        if (session.airborne) {
            double commandedPitch;
            if (session.autopilot) {
                double currentAltFeet = player.getY() * 3.28084;
                double altErrorFeet = session.autopilotAltitudeFeet - currentAltFeet;
                double maxVs = Math.max(300.0, Math.min(3000.0, Math.abs(session.autopilotVerticalSpeedFpm)));
                double desiredVsFpm = Math.max(-maxVs, Math.min(maxVs, altErrorFeet * 8.0));
                double vsError = desiredVsFpm - session.verticalSpeedFpm;
                commandedPitch = Math.max(-12.0, Math.min(22.0, desiredVsFpm * 0.005 + vsError * 0.003));
            } else {
                commandedPitch = Math.max(-20.0, Math.min(45.0, -player.getXRot()));
            }
            double dynamicAuthority = Math.max(0.25, Math.min(1.2, session.airspeed / FlightTuning.ROTATION_SPEED));
            session.pitchDegrees = approach(session.pitchDegrees, commandedPitch, 0.45 * dynamicAuthority);
        } else {
            session.pitchDegrees = 0.0;
        }

        if (session.airborne && onGround && session.takeoffGraceTicks == 0
                && session.airVelocity.y <= 0.0) {
            handleFallImpact(player, player.fallDistance);
            if (session.crashed) {
                player.setDeltaMovement(Vec3.ZERO);
                updateInstruments(player, session, server);
                return;
            }
            session.airborne = false;
            session.groundSpeed = session.airVelocity.horizontalDistance();
            if (!session.gearDown || session.airVelocity.y < -FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED) {
                session.gearFailed = true;
            }
            session.airVelocity = Vec3.ZERO;
            session.landingRoll = true;
            player.setNoGravity(false);
            player.resetFallDistance();
            player.setDeltaMovement(headingVector(session.headingDegrees).scale(session.groundSpeed));
            setPhase(player, session, FlightPhase.LANDING_ROLL);
        }

        if (!session.airborne && !onGround && session.groundSpeed < FlightTuning.ROTATION_SPEED) {
            // A jump or walking off an edge is not a takeoff. Let normal gravity recover the player.
            player.setNoGravity(false);
            session.groundSpeed = player.getDeltaMovement().horizontalDistance();
        }

        if (session.airborne) {
            if (session.takeoffGraceTicks > 0) session.takeoffGraceTicks--;
            if (session.takeoffWarningTicks > 0) session.takeoffWarningTicks--;
            updateBankAndTurn(player, session);
            if (session.crashed) {
                player.setDeltaMovement(Vec3.ZERO);
                updateInstruments(player, session, server);
                return;
            }
            simulateAirborne(player, session);
        } else {
            session.bankDegrees = approach(session.bankDegrees, 0.0, 2.0);
            updateGroundHeading(player, session);
            simulateGroundRoll(player, session);
        }
        updateGearHeat(session);
        updateInstruments(player, session, server);
    }

    private static void updateThrottle(Session session) {
        if (session.pushback) {
            session.throttle = 0.0;
            return;
        }
        if (runningEngines(session) == 0) {
            session.throttle = 0.0;
            return;
        }
        if ((session.controls & FlightControls.FORWARD) != 0) {
            session.throttle = FlightTuning.adjustThrottle(session.throttle, true);
        } else if ((session.controls & FlightControls.REVERSE) != 0) {
            session.throttle = FlightTuning.adjustThrottle(session.throttle, false);
        }
    }

    private static void updateEngineSpool(Session session) {
        double targetSpool = runningEngines(session) == 0 ? 0.0 : 0.04 + session.throttle * 0.96;
        session.engineSpool = FlightTuning.updateEngineSpool(session.engineSpool, targetSpool);
    }

    private static void updateFuel(ServerPlayer player, Session session) {
        int food = player.getFoodData().getFoodLevel();
        if (food <= 0) {
            session.engine1State = ENGINE_OFF;
            session.engine2State = ENGINE_OFF;
            session.engineStartTicks = 0;
            session.throttle = 0.0;
            session.engineSpool = 0.0;
            return;
        }
        int engines = runningEngines(session);
        if (engines > 0) {
            // Vanilla exhaustion consumes one hunger point per 4 exhaustion.
            // Two engines at high thrust use more fuel; even idle operation burns some.
            float exhaustion = (float) ((0.00025 + session.throttle * 0.0020 * (engines / 2.0)));
            player.getFoodData().addExhaustion(exhaustion);
        }
    }

    private static void updateEngineStart(Session session) {
        if (session.engine1State == ENGINE_STARTING) {
            if (++session.engineStartTicks >= ENGINE_START_TICKS) {
                session.engine1State = ENGINE_RUNNING;
                session.engine2State = ENGINE_STARTING;
                session.engineStartTicks = 0;
            }
        } else if (session.engine2State == ENGINE_STARTING) {
            if (++session.engineStartTicks >= ENGINE_START_TICKS) {
                session.engine2State = ENGINE_RUNNING;
                session.engineStartTicks = 0;
            }
        }
    }

    private static int runningEngines(Session session) {
        return (session.engine1State == ENGINE_RUNNING ? 1 : 0)
                + (session.engine2State == ENGINE_RUNNING ? 1 : 0);
    }

    private static void updateGroundHeading(ServerPlayer player, Session session) {
        int controls = session.controls;
        double targetRate = 0.0;
        boolean left = (controls & FlightControls.LEFT) != 0;
        boolean right = (controls & FlightControls.RIGHT) != 0;
        if (left != right) {
            double authority = Math.max(0.12, 1.0 - session.groundSpeed / FlightTuning.MAX_AIRSPEED);
            targetRate = (left ? -1.0 : 1.0) * 0.45 * authority;
        }
        session.groundTurnRate = approach(session.groundTurnRate, targetRate, 0.035);
        session.headingDegrees = wrapDegrees(session.headingDegrees + session.groundTurnRate);
        applyHeading(player, session);
    }

    private static void updateBankAndTurn(ServerPlayer player, Session session) {
        int controls = session.controls;
        double targetBank = 0.0;
        if (session.autopilot) {
            double headingError = wrapDegrees(session.autopilotHeading - session.headingDegrees);
            targetBank = Math.max(-25.0, Math.min(25.0, headingError * 0.75));
        } else if ((controls & FlightControls.LEFT) != 0 && (controls & FlightControls.RIGHT) == 0) {
            targetBank = -FlightTuning.MAX_BANK_DEGREES;
        } else if ((controls & FlightControls.RIGHT) != 0 && (controls & FlightControls.LEFT) == 0) {
            targetBank = FlightTuning.MAX_BANK_DEGREES;
        }

        session.bankDegrees = approach(session.bankDegrees, targetBank,
                FlightTuning.BANK_RESPONSE_DEGREES_PER_TICK);
        if (Math.abs(session.bankDegrees) >= FlightTuning.CRITICAL_BANK_DEGREES) {
            session.criticalBankTicks++;
            if (shouldCrashForOverbank(session.bankDegrees, session.criticalBankTicks)) {
                crashAircraft(player, session, session.airspeed);
                return;
            }
        } else {
            session.criticalBankTicks = 0;
        }
        double speed = Math.max(0.75, session.airspeed);
        // Turn rate: omega = g * tan(phi) / v
        double turnRate = 0.95 * FlightTuning.WORLD_SPEED_SCALE
                * Math.tan(Math.toRadians(session.bankDegrees)) / speed;
        session.headingDegrees = wrapDegrees(session.headingDegrees + turnRate);

        // Aerodynamic overbanking / nose drop: in a bank, the aircraft naturally drops its nose unless pilot pulls back
        if (!session.autopilot && Math.abs(session.bankDegrees) > 2.0) {
            session.pitchDegrees -= Math.abs(session.bankDegrees) * 0.015;
        }

        // Rotate velocity vector horizontally to follow the coordinated turn
        if (Math.abs(turnRate) > 1.0e-4) {
            double radians = Math.toRadians(turnRate);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double vx = session.airVelocity.x;
            double vz = session.airVelocity.z;
            session.airVelocity = new Vec3(vx * cos - vz * sin, session.airVelocity.y, vx * sin + vz * cos);
        }

        applyHeading(player, session);
    }

    public static void configureAutopilot(ServerPlayer player, boolean engaged, int targetHeading,
                                          int targetAltitudeFeet, int targetVerticalSpeedFpm, int targetAirspeedKnots) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed || !session.airborne) return;
        session.autopilot = engaged;
        session.autopilotHeading = Math.floorMod(targetHeading, 360);
        session.autopilotAltitudeFeet = Math.max(100, targetAltitudeFeet);
        session.autopilotVerticalSpeedFpm = targetVerticalSpeedFpm;
        session.autopilotAirspeedKnots = targetAirspeedKnots;
        sendState(player, session, true);
    }

    public static void toggleAutopilot(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed || !session.airborne) return;
        session.autopilot = !session.autopilot;
        if (session.autopilot) {
            session.autopilotHeading = session.headingDegrees;
            session.autopilotAltitudeFeet = Math.max(100, (int) Math.round(player.getY() * 3.28084));
            session.autopilotVerticalSpeedFpm = 1000;
            session.autopilotAirspeedKnots = (int) Math.round(session.airspeed * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
        }
        sendState(player, session, true);
    }

    private static void simulateGroundRoll(ServerPlayer player, Session session) {
        player.setNoGravity(false);
        double speed = session.groundSpeed;
        if (session.brakesActive) {
            speed = Math.max(0.0, speed - FlightTuning.WHEEL_BRAKE_DECELERATION
                    - (session.spoilers ? 0.025 * FlightTuning.WORLD_SPEED_SCALE : 0.0));
        } else if (session.pushback) {
            FlightTuning.PushbackMotion motion = FlightTuning.advancePushback(speed,
                    session.pushbackReversing);
            speed = motion.speed();
            session.pushbackReversing = motion.reversing();
        } else {
            double engineFactor = runningEngines(session) / 2.0;
            double thrust = session.engineSpool * FlightTuning.GROUND_THRUST * engineFactor;
            // Low rolling resistance lets a fully powered aircraft reach rotation speed,
            // while the small per-tick thrust still takes time to build runway speed.
            double rollingResistance = 0.0008 * FlightTuning.WORLD_SPEED_SCALE + speed * 0.0017;
            double flapDrag = session.flapPosition * 0.0015;
            speed = Math.max(0.0, speed + thrust - rollingResistance - flapDrag);
        }
        session.groundSpeed = Math.min(FlightTuning.MAX_AIRSPEED, speed);
        session.airspeed = (float) session.groundSpeed;

        Vec3 heading = headingVector(session.headingDegrees);
        Vec3 previous = player.getDeltaMovement();
        double direction = session.pushback && session.pushbackReversing ? -1.0 : 1.0;
        player.setDeltaMovement(heading.scale(session.groundSpeed * direction).add(0.0, previous.y, 0.0));

        double pitchUp = -player.getXRot();
        if (!session.pushback && !session.brakesActive && runningEngines(session) > 0
                && session.groundSpeed >= FlightTuning.ROTATION_SPEED
                && pitchUp >= FlightTuning.ROTATION_PITCH_DEGREES) {
            session.airborne = true;
            session.takeoffGraceTicks = 10;
            session.takeoffWarningTicks = 600;
            session.impactHandled = false;
            player.setNoGravity(true);
            Vec3 initialVelocity = heading.scale(session.groundSpeed)
                    .add(0.0, 0.38 * FlightTuning.WORLD_SPEED_SCALE, 0.0);
            session.airVelocity = initialVelocity;
            player.setDeltaMovement(initialVelocity);
            session.pitchDegrees = 8.0;
            session.airspeed = (float) session.groundSpeed;
            setPhase(player, session, FlightPhase.AIRBORNE);
        } else if (session.landingRoll && session.groundSpeed > 0.25) {
            setPhase(player, session, FlightPhase.LANDING_ROLL);
        } else if (session.groundSpeed >= FlightTuning.ROTATION_SPEED * 0.90) {
            session.landingRoll = false;
            setPhase(player, session, FlightPhase.ROTATE);
        } else if (session.groundSpeed >= 0.25) {
            session.landingRoll = false;
            setPhase(player, session, FlightPhase.TAKEOFF_ROLL);
        } else {
            session.landingRoll = false;
            setPhase(player, session, FlightPhase.TAXI);
        }
    }

    private static void simulateAirborne(ServerPlayer player, Session session) {
        // Authoritative aircraft 3D attitude vector derived from aircraft pitch and heading
        double pitchRad = Math.toRadians(session.pitchDegrees);
        double yawRad = Math.toRadians(session.headingDegrees);
        double cosPitch = Math.cos(pitchRad);
        Vec3 aircraftAttitude = new Vec3(
                -Math.sin(yawRad) * cosPitch,
                Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        );

        Vec3 velocity = session.airVelocity;
        Vec3 wind = new Vec3(session.windX, session.windY, session.windZ);
        Vec3 relativeAir = velocity.subtract(wind);
        double airspeed = relativeAir.length();

        // Ground effect cushions aircraft flare within 12 blocks (~40 ft) of the surface
        double radioAltBlocks = session.radioAltitudeFeet / 3.28084;
        double groundEffect = radioAltBlocks < 12.0 ? Math.max(0.0, (12.0 - radioAltBlocks) / 12.0) : 0.0;
        double baseDrag = ((session.gearDown ? 0.0012 : 0.00045) + session.flapPosition * 0.00055
                + (session.spoilers ? 0.0045 : 0.0)) / FlightTuning.WORLD_SPEED_SCALE;

        // Turn induced drag from aerodynamic load factor (g-load = 1 / cos(bank)):
        // Steep turns bleed airspeed unless throttle is added!
        double cosBank = Math.max(0.15, Math.cos(Math.toRadians(session.bankDegrees)));
        double loadFactor = 1.0 / cosBank;
        double turnInducedDrag = (loadFactor * loadFactor - 1.0) * 0.0016 / FlightTuning.WORLD_SPEED_SCALE;
        double drag = (baseDrag * (1.0 - groundEffect * 0.30)) + turnInducedDrag;
        velocity = velocity.subtract(relativeAir.scale(drag * Math.max(0.15, airspeed)));

        // Thrust aligns strictly with the aircraft's physical attitude (decoupled from raw camera look)
        double engineFactor = runningEngines(session) / 2.0;
        velocity = velocity.add(aircraftAttitude.scale(session.engineSpool * FlightTuning.AIR_THRUST * engineFactor));

        // Autothrottle speed hold
        if (session.autopilot && session.autopilotAirspeedKnots > 0 && runningEngines(session) > 0) {
            double currentKnots = session.airspeed * FlightTuning.KNOTS_PER_BLOCK_PER_TICK;
            double speedError = session.autopilotAirspeedKnots - currentKnots;
            if (speedError > FlightTuning.AUTOTHROTTLE_DEADBAND_KNOTS) {
                session.throttle = Math.min(1.0, session.throttle + 0.006);
            } else if (speedError < -FlightTuning.AUTOTHROTTLE_DEADBAND_KNOTS) {
                session.throttle = Math.max(0.15, session.throttle - 0.006);
            }
        }

        double horizontalSpeed = velocity.subtract(wind).horizontalDistance();
        double flightPathAngle = Math.toDegrees(Math.atan2(velocity.y, Math.max(horizontalSpeed, 1.0e-4)));
        double pitchUp = session.pitchDegrees;
        double angleOfAttack = pitchUp - flightPathAngle;
        boolean stalled = horizontalSpeed < FlightTuning.STALL_SPEED
                || angleOfAttack > FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES || pitchUp >= 50.0;

        if (stalled) {
            // Aerodynamic stall recovery: nose naturally pitches down to regain airflow
            session.pitchDegrees = approach(session.pitchDegrees, -4.0, 0.25);
        }

        double effectiveAngle = Math.max(0.0, Math.min(20.0, angleOfAttack + 6.0)) + session.flapPosition * 2.4;
        double lift = stalled ? 0.03 * FlightTuning.WORLD_SPEED_SCALE
                : Math.min(0.24 * FlightTuning.WORLD_SPEED_SCALE,
                horizontalSpeed * horizontalSpeed * 0.0011 / FlightTuning.WORLD_SPEED_SCALE
                        * effectiveAngle);
        lift *= Math.cos(Math.toRadians(session.bankDegrees));
        lift *= (1.0 + groundEffect * 0.22); // Ground effect lift cushioning
        if (session.spoilers) lift *= 0.30;
        if (!stalled && session.takeoffGraceTicks > 0) {
            lift = Math.max(lift, 0.12);
        }
        velocity = velocity.add(0.0, lift - 0.08, 0.0);

        if (velocity.lengthSqr() > FlightTuning.MAX_AIRSPEED * FlightTuning.MAX_AIRSPEED) {
            velocity = velocity.normalize().scale(FlightTuning.MAX_AIRSPEED);
        }
        session.airVelocity = velocity;
        player.setDeltaMovement(velocity);
        session.airspeed = (float) horizontalSpeed;
        setPhase(player, session, stalled ? FlightPhase.STALL : FlightPhase.AIRBORNE);
    }

    private static void updateFlaps(Session session) {
        double target = session.flapDetent * 0.25;
        session.flapPosition = approach(session.flapPosition, target, 0.008);
    }

    private static void updateWeather(ServerPlayer player, Session session) {
        if (session.windShearTicks > 0) session.windShearTicks--;
        if (++session.windUpdateTicks < 40) return;
        session.windUpdateTicks = 0;
        long phase = player.level().getGameTime() / 40L + player.getUUID().getLeastSignificantBits();
        double angle = Math.floorMod(phase, 360L) * Math.PI / 180.0;
        double weather = player.level().isThundering() ? 1.0 : player.level().isRaining() ? 0.65 : 0.3;
        double gust = 0.55 + 0.45 * Math.sin(phase * 0.37);
        double strength = weather * gust * 0.22 * FlightTuning.WORLD_SPEED_SCALE;
        session.windX = Math.cos(angle) * strength;
        session.windZ = Math.sin(angle) * strength;
        session.windY = 0.0;
        session.windSpeedKnots = (int) Math.round(strength * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
        session.windDirectionSector = Math.floorMod((int) Math.round(Math.toDegrees(angle) / 5.625), 64);
        if (player.level().isThundering() && Math.floorMod(phase, 90L) == 0L) {
            session.windShearTicks = 60;
            session.windX *= 2.4;
            session.windZ *= 2.4;
            if (session.airborne) {
                session.airVelocity = session.airVelocity.add(session.windX * 0.05,
                        -0.08 * FlightTuning.WORLD_SPEED_SCALE, session.windZ * 0.05);
            }
        }
    }

    private static void updateEngineTemperatures(Session session) {
        boolean highPower = session.engineSpool > 0.88;
        session.engineTemperature = approach(session.engineTemperature,
                highPower ? 950.0 : 420.0, highPower ? 1.8 : 0.18);
        if (session.engineTemperature > 900.0) session.engineHotTicks++;
        else session.engineHotTicks = Math.max(0, session.engineHotTicks - 2);
        if (session.engineHotTicks > 1200) {
            if (session.engine1State == ENGINE_RUNNING) session.engine1State = ENGINE_FIRE;
            else if (session.engine2State == ENGINE_RUNNING) session.engine2State = ENGINE_FIRE;
            if (session.engine1State == ENGINE_FIRE || session.engine2State == ENGINE_FIRE) {
                session.engineFireTicks++;
                if (session.engineFireTicks > 400) {
                    if (session.engine1State == ENGINE_FIRE) session.engine1State = ENGINE_FAILED;
                    if (session.engine2State == ENGINE_FIRE) session.engine2State = ENGINE_FAILED;
                }
            }
        }
    }

    private static void updateGearHeat(Session session) {
        if (session.airborne && session.gearDown
                && session.airspeed > 2.4f * FlightTuning.WORLD_SPEED_SCALE && !session.gearFailed) {
            session.gearHeatTicks++;
            if (session.gearHeatTicks >= 400) {
                session.gearFailed = true;
            }
        } else if (!session.gearFailed) {
            session.gearHeatTicks = Math.max(0, session.gearHeatTicks - 2);
        }
    }

    private static void updateInstruments(ServerPlayer player, Session session, MinecraftServer server) {
        int radioAltitude = Math.max(0, (int) Math.round((player.getY()
                - player.level().getHeight(Heightmap.Types.MOTION_BLOCKING,
                player.blockPosition().getX(), player.blockPosition().getZ())) * 3.28084));
        double verticalVelocity = session.airborne ? session.airVelocity.y : player.getDeltaMovement().y;
        int verticalSpeed = (int) Math.round(verticalVelocity * 3937.0 / FlightTuning.WORLD_SPEED_SCALE);
        int warnings = 0;
        session.airborneContacts = 0;
        session.groundContacts = 0;
        if (session.phase == FlightPhase.STALL) warnings |= FlightWarning.STALL;
        if (session.phase == FlightPhase.AIRBORNE && radioAltitude < 1000 && verticalSpeed < -1000) warnings |= FlightWarning.SINK_RATE;
        if (session.airborne && session.takeoffWarningTicks > 0 && radioAltitude < 200
                && verticalSpeed < -300) warnings |= FlightWarning.DONT_SINK;
        boolean descendingTerrainAhead = false;
        if (session.windShearTicks > 0) warnings |= FlightWarning.WINDSHEAR;
        int food = player.getFoodData().getFoodLevel();
        if (food <= 0) warnings |= FlightWarning.FUEL_EMPTY;
        else if (food <= 6) warnings |= FlightWarning.FUEL_LOW;
        if (session.engine1State == ENGINE_FIRE || session.engine2State == ENGINE_FIRE) warnings |= FlightWarning.ENGINE_FIRE;
        else if (session.engine1State == ENGINE_FAILED || session.engine2State == ENGINE_FAILED) warnings |= FlightWarning.ENGINE_FAILURE;
        if (Math.abs(session.bankDegrees) > 45.0) warnings |= FlightWarning.BANK_ANGLE;
        if (session.gearGroundAlarmTicks > 0) {
            warnings |= FlightWarning.GEAR_SELECTED_ON_GROUND;
            session.gearGroundAlarmTicks--;
        }
        if (session.airborne && radioAltitude < 500 && verticalSpeed < -200 && !session.gearDown) {
            warnings |= FlightWarning.GEAR_UP;
        }
        if (session.airborne && radioAltitude < 300 && verticalSpeed < -200
                && session.gearDown && session.flapDetent == 0) {
            warnings |= FlightWarning.TOO_LOW_FLAPS;
        }
        if (session.airborne && radioAltitude < 250 && verticalSpeed < -200
                && !session.gearDown && session.throttle > 0.60) {
            warnings |= FlightWarning.TOO_LOW_TERRAIN;
        }

        // Mode 2 GPWS: Look-ahead terrain closure rate (rising mountains or hills ahead)
        if (session.airborne && session.airspeed > 0.8f) {
            Vec3 forward = session.airVelocity.horizontalDistanceSqr() > 1.0e-4
                    ? new Vec3(session.airVelocity.x, 0.0, session.airVelocity.z).normalize()
                    : headingVector(session.headingDegrees);
            double horizontalSpeed = Math.max(0.1, session.airVelocity.horizontalDistance());
            double lookDist = Math.max(16.0, Math.min(96.0, horizontalSpeed * 30.0));
            int aheadX = (int) Math.round(player.getX() + forward.x * lookDist);
            int aheadZ = (int) Math.round(player.getZ() + forward.z * lookDist);
            int currentGround = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING,
                    player.blockPosition().getX(), player.blockPosition().getZ());
            int groundAhead = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, aheadX, aheadZ);
            int risingTerrainFeet = (int) Math.round((groundAhead - currentGround) * 3.28084);
            double lookAheadTicks = lookDist / horizontalSpeed;
            double projectedClearanceFeet = (player.getY() + session.airVelocity.y * lookAheadTicks - groundAhead)
                    * 3.28084;
            descendingTerrainAhead = session.airVelocity.y < -0.02 && projectedClearanceFeet < 35.0;
            if (descendingTerrainAhead) {
                warnings |= FlightWarning.PULL_UP;
            } else if (risingTerrainFeet > 40 && radioAltitude < 600) {
                warnings |= FlightWarning.TERRAIN;
            }
        }
        if (shouldPullUpWarning(session.airborne, radioAltitude, verticalSpeed, descendingTerrainAhead)) {
            warnings |= FlightWarning.PULL_UP;
        }

        if (!session.airborne && session.throttle > 0.65
                && (session.flapDetent == 0 || session.spoilers || session.brakesActive)) {
            warnings |= FlightWarning.TAKEOFF_CONFIG;
        }
        if (session.airborne && session.airspeed > FlightTuning.MAX_AIRSPEED * 0.96) {
            warnings |= FlightWarning.OVERSPEED;
        }
        if (session.gearHeatTicks >= 100 && !session.gearFailed) warnings |= FlightWarning.GEAR_HOT;
        if (session.airborne && session.gearDown && radioAltitude < 1500 && session.airspeed > 2.5f) {
            double targetGlideFpm = -session.airspeed * FlightTuning.KNOTS_PER_BLOCK_PER_TICK * 5.3;
            double glideError = verticalSpeed - targetGlideFpm;
            if (glideError > 250) warnings |= FlightWarning.GLIDESLOPE_HIGH;
            else if (glideError < -250) warnings |= FlightWarning.GLIDESLOPE_LOW;
        }
        {
            double closestResolution = Double.MAX_VALUE;
            double closestScopeContact = Double.MAX_VALUE;
            int resolutionCommand = 0;
            session.trafficRangeBlocks = 0;
            session.trafficBearingDegrees = 0.0;
            session.trafficAltitudeFeet = 0;
            for (ServerPlayer traffic : server.getPlayerList().getPlayers()) {
                if (traffic == player || traffic.level() != player.level()) continue;
                Session intruder = SESSIONS.get(traffic.getUUID());
                if (intruder == null || intruder.crashed) continue;
                Vec3 separation = traffic.position().subtract(player.position());
                recordTrafficContact(session, separation, intruder.airborne);
                closestScopeContact = recordScopeContact(session, separation, closestScopeContact);
                Vec3 ownVelocity = session.airborne ? session.airVelocity : player.getDeltaMovement();
                Vec3 intruderVelocity = intruder.airborne ? intruder.airVelocity : traffic.getDeltaMovement();
                Vec3 relativeVelocity = intruderVelocity.subtract(ownVelocity);
                double relativeSpeedSquared = relativeVelocity.lengthSqr();
                double timeToClosest = relativeSpeedSquared < 1.0e-6 ? 0.0
                        : -separation.dot(relativeVelocity) / relativeSpeedSquared;
                if (timeToClosest < 0.0 || timeToClosest > 400.0) continue;
                Vec3 closestSeparation = separation.add(relativeVelocity.scale(timeToClosest));
                double horizontal = closestSeparation.horizontalDistance();
                double vertical = Math.abs(closestSeparation.y);
                if (shouldWarnTcas(session.airborne, intruder.airborne)
                        && horizontal < 64.0 * FlightTuning.WORLD_SPEED_SCALE
                        && vertical < 16.0 * FlightTuning.WORLD_SPEED_SCALE) {
                    warnings |= FlightWarning.TCAS_TRAFFIC;
                }
                double combinedDistance = horizontal + vertical * 2.0;
                if (session.airborne && intruder.airborne && timeToClosest < 300.0
                        && horizontal < 28.0 * FlightTuning.WORLD_SPEED_SCALE
                        && vertical < 12.0 * FlightTuning.WORLD_SPEED_SCALE
                        && combinedDistance < closestResolution) {
                    closestResolution = combinedDistance;
                    resolutionCommand = separation.y > 0.5 ? FlightWarning.TCAS_DESCEND : FlightWarning.TCAS_CLIMB;
                    if (separation.y * relativeVelocity.y < 0.0) resolutionCommand |= FlightWarning.TCAS_CROSSING;
                }
            }
            for (MobFlightController.Traffic traffic : MobFlightController.aircraftIn(player.level())) {
                Vec3 separation = traffic.position().subtract(player.position());
                recordTrafficContact(session, separation, traffic.airborne());
                closestScopeContact = recordScopeContact(session, separation, closestScopeContact);
                Vec3 relativeVelocity = traffic.velocity().subtract(session.airVelocity);
                double relativeSpeedSquared = relativeVelocity.lengthSqr();
                double timeToClosest = relativeSpeedSquared < 1.0e-6 ? 0.0
                        : -separation.dot(relativeVelocity) / relativeSpeedSquared;
                if (timeToClosest < 0.0 || timeToClosest > 400.0) continue;
                Vec3 closestSeparation = separation.add(relativeVelocity.scale(timeToClosest));
                double horizontal = closestSeparation.horizontalDistance();
                double vertical = Math.abs(closestSeparation.y);
                double combinedDistance = horizontal + vertical * 2.0;
                if (shouldWarnTcas(session.airborne, traffic.airborne())
                        && horizontal < 64.0 * FlightTuning.WORLD_SPEED_SCALE
                        && vertical < 16.0 * FlightTuning.WORLD_SPEED_SCALE) {
                    warnings |= FlightWarning.TCAS_TRAFFIC;
                }
                if (session.airborne && traffic.airborne() && timeToClosest < 300.0
                        && horizontal < 28.0 * FlightTuning.WORLD_SPEED_SCALE
                        && vertical < 12.0 * FlightTuning.WORLD_SPEED_SCALE
                        && combinedDistance < closestResolution) {
                    closestResolution = combinedDistance;
                    resolutionCommand = separation.y > 0.5 ? FlightWarning.TCAS_DESCEND : FlightWarning.TCAS_CLIMB;
                    if (separation.y * relativeVelocity.y < 0.0) resolutionCommand |= FlightWarning.TCAS_CROSSING;
                }
            }
            if (resolutionCommand != 0) {
                warnings |= FlightWarning.TCAS_RESOLUTION | resolutionCommand;
                session.tcasClearTicks = 100;
            } else if (session.tcasClearTicks > 0) {
                session.tcasClearTicks--;
                warnings |= FlightWarning.TCAS_CLEAR;
            }
        }
        session.ilsGuidance = calculateIlsGuidance(player, session);
        session.warningFlags = warnings;
        session.radioAltitudeFeet = radioAltitude;
        session.verticalSpeedFpm = verticalSpeed;
    }

    private static void recordTrafficContact(Session session, Vec3 separation, boolean airborne) {
        if (separation.horizontalDistance() > TRAFFIC_SCOPE_BLOCKS
                || Math.abs(separation.y) > TRAFFIC_SCOPE_BLOCKS) return;
        if (airborne) {
            session.airborneContacts = Math.min(255, session.airborneContacts + 1);
        } else {
            session.groundContacts = Math.min(255, session.groundContacts + 1);
        }
    }

    static boolean shouldWarnTcas(boolean ownAircraftAirborne, boolean trafficAirborne) {
        return ownAircraftAirborne && trafficAirborne;
    }

    static boolean shouldPullUpWarning(boolean airborne, int radioAltitudeFeet, int verticalSpeedFpm,
                                       boolean descendingTerrainAhead) {
        return airborne && (descendingTerrainAhead
                || (radioAltitudeFeet < 180 && verticalSpeedFpm < -700));
    }

    static boolean shouldCrashForOverbank(double bankDegrees, int durationTicks) {
        return Math.abs(bankDegrees) >= FlightTuning.CRITICAL_BANK_DEGREES && durationTicks >= 20;
    }

    private static FlightInstrumentPayload.IlsGuidance calculateIlsGuidance(ServerPlayer player, Session session) {
        if (session.ilsDimension == null || !session.ilsDimension.equals(player.level().dimension())) {
            return FlightInstrumentPayload.IlsGuidance.inactive();
        }
        return calculateIlsGuidance(player.position(), session.ilsThreshold, session.ilsRunwayHeading,
                session.ilsGlideSlopeDegrees);
    }

    static FlightInstrumentPayload.IlsGuidance calculateIlsGuidance(Vec3 aircraftPosition, Vec3 threshold,
                                                                    float runwayHeading, float glideSlopeDegrees) {
        Vec3 fromThreshold = aircraftPosition.subtract(threshold);
        double runwayYaw = Math.toRadians(runwayHeading);
        Vec3 lateralDirection = new Vec3(Math.cos(runwayYaw), 0.0, Math.sin(runwayYaw));
        double alongTrack = fromThreshold.dot(headingVector(runwayHeading + 180.0));
        double lateralOffset = fromThreshold.dot(lateralDirection);
        boolean inCoverage = alongTrack >= 10.0 && alongTrack <= 5000.0 && Math.abs(lateralOffset) <= 500.0;
        double localizer = Math.toDegrees(Math.atan2(lateralOffset, Math.max(1.0, alongTrack)));
        double targetHeight = alongTrack * Math.tan(Math.toRadians(glideSlopeDegrees));
        double glideslope = Math.toDegrees(Math.atan2(fromThreshold.y - targetHeight,
                Math.max(1.0, alongTrack)));
        return new FlightInstrumentPayload.IlsGuidance(inCoverage, (float) localizer, (float) glideslope,
                glideSlopeDegrees);
    }

    private static double recordScopeContact(Session session, Vec3 separation, double closestContact) {
        double horizontal = separation.horizontalDistance();
        double vertical = Math.abs(separation.y);
        if (horizontal > TRAFFIC_SCOPE_BLOCKS || vertical > TRAFFIC_SCOPE_BLOCKS) return closestContact;
        double distance = horizontal + vertical * 0.25;
        if (distance >= closestContact) return closestContact;
        session.trafficRangeBlocks = Math.max(1, (int)Math.round(horizontal));
        session.trafficBearingDegrees = (float)wrapDegrees(
                Math.toDegrees(Math.atan2(-separation.x, separation.z)) - session.headingDegrees);
        session.trafficAltitudeFeet = (int)Math.round(separation.y * 3.28084);
        return distance;
    }

    private static Vec3 headingVector(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    private static void applyHeading(ServerPlayer player, Session session) {
        player.setYRot((float) session.headingDegrees);
        player.setYHeadRot((float) session.headingDegrees);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static double approach(double current, double target, double step) {
        if (current < target) return Math.min(target, current + step);
        return Math.max(target, current - step);
    }

    private static void setPhase(ServerPlayer player, Session session, FlightPhase phase) {
        if (session.phase != phase) {
            session.phase = phase;
            sendState(player, session, true);
        }
    }

    private static void sendState(ServerPlayer player, @Nullable Session session, boolean enabled) {
        FlightStatePayload state = session == null
                ? new FlightStatePayload(false, FlightPhase.OFF.ordinal(), 0.0f, 0.0f,
                (float) FlightTuning.ROTATION_SPEED, 0.0f, 0.0f, 0.0f)
                : new FlightStatePayload(enabled, session.phase.ordinal(), session.airspeed,
                (float) session.throttle, (float) FlightTuning.ROTATION_SPEED,
                (float) (session.airborne ? session.airVelocity.x : player.getDeltaMovement().x),
                (float) (session.airborne ? session.airVelocity.y : player.getDeltaMovement().y),
                (float) (session.airborne ? session.airVelocity.z : player.getDeltaMovement().z));
        ServerPlayNetworking.send(player, state);

        FlightInstrumentPayload instruments = session == null
                ? new FlightInstrumentPayload(0, 0, 0.0f, 0.0f, 0.0f, 0, 0, false, 0,
                0.0f, 0, 0, 0, new FlightInstrumentPayload.CockpitGuidance(
                FlightInstrumentPayload.IlsGuidance.inactive(),
                FlightInstrumentPayload.AutopilotGuidance.defaults()))
                : new FlightInstrumentPayload(session.radioAltitudeFeet, session.verticalSpeedFpm,
                (float) session.bankDegrees, (float) session.pitchDegrees, (float) session.headingDegrees,
                session.gearFailed ? 2 : (session.gearDown ? 0 : 1),
                (session.engine1State & 7) | ((session.engine2State & 7) << 3)
                        | (session.brakesActive ? 1 << 6 : 0) | (session.pushback ? 1 << 7 : 0)
                        | (session.spoilers ? 1 << 8 : 0) | ((session.flapDetent & 7) << 9)
                        | (Math.round((float) session.engineSpool * 100.0f) << 12)
                        | ((Math.min(127, session.windSpeedKnots) & 0x7F) << 19)
                        | ((session.windDirectionSector & 0x3F) << 26),
                session.autopilot, session.warningFlags | (session.gearFailed ? FlightWarning.GEAR_FAILED : 0),
                (float) session.trafficBearingDegrees, session.trafficRangeBlocks, session.trafficAltitudeFeet,
                (Math.min(255, session.airborneContacts) << 8) | Math.min(255, session.groundContacts),
                new FlightInstrumentPayload.CockpitGuidance(session.ilsGuidance,
                        new FlightInstrumentPayload.AutopilotGuidance((float) session.autopilotHeading,
                        session.autopilotAltitudeFeet, session.autopilotVerticalSpeedFpm,
                        session.autopilotAirspeedKnots, (float) FlightTuning.AUTOTHROTTLE_DEADBAND_KNOTS)));
        ServerPlayNetworking.send(player, instruments);
    }

    private static final class Session {
        private final boolean hadNoGravity;
        private int controls;
        private int takeoffGraceTicks;
        private int takeoffWarningTicks;
        private int engineStartTicks;
        private int gearHeatTicks;
        private int gearGroundAlarmTicks;
        private int criticalBankTicks;
        private int windUpdateTicks;
        private int windShearTicks;
        private int engineHotTicks;
        private int engineFireTicks;
        private int flapDetent;
        private int windSpeedKnots;
        private int windDirectionSector;
        private double flapPosition;
        private double windX, windY, windZ;
        private int radioAltitudeFeet;
        private int verticalSpeedFpm;
        private int warningFlags;
        private int trafficRangeBlocks;
        private int trafficAltitudeFeet;
        private int airborneContacts;
        private int groundContacts;
        private double groundSpeed;
        private double throttle;
        private double engineSpool;
        private double bankDegrees;
        private double pitchDegrees;
        private double headingDegrees;
        private double groundTurnRate;
        private double trafficBearingDegrees;
        private float airspeed;
        private Vec3 airVelocity = Vec3.ZERO;
        private boolean airborne;
        private boolean gearDown = true;
        private boolean gearFailed;
        private int engine1State = ENGINE_OFF;
        private int engine2State = ENGINE_OFF;
        private boolean brakesActive;
        private boolean pushback;
        private boolean pushbackReversing;
        private boolean spoilers;
        private boolean autopilot;
        private double autopilotHeading;
        private int autopilotAltitudeFeet = 2000;
        private int autopilotVerticalSpeedFpm = 1000;
        private int autopilotAirspeedKnots = 160;
        private int tcasClearTicks;
        private boolean landingRoll;
        private boolean crashed;
        private int crashTicksRemaining;
        private boolean impactHandled;
        private @Nullable ResourceKey<Level> ilsDimension;
        private Vec3 ilsThreshold = Vec3.ZERO;
        private float ilsRunwayHeading;
        private float ilsGlideSlopeDegrees = 3.0f;
        private FlightInstrumentPayload.IlsGuidance ilsGuidance = FlightInstrumentPayload.IlsGuidance.inactive();
        private double engineTemperature = 20.0;
        private FlightPhase phase = FlightPhase.TAXI;

        private Session(boolean hadNoGravity) {
            this.hadNoGravity = hadNoGravity;
        }
    }
}
