package dev.flightline.flight;

import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
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
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private FlightController() {
    }

    public static void enable(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) return;
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

    /** Returns true when the normal Minecraft fall should be replaced by aircraft impact logic. */
    public static boolean handleFallImpact(ServerPlayer player, double fallDistance) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.airborne) return false;
        if (session.crashed || session.impactHandled) return true;
        session.impactHandled = true;

        double sinkSpeed = Math.max(0.0, -session.airVelocity.y);
        double horizontalSpeed = session.airVelocity.horizontalDistance();
        double safeSink = 0.10 * FlightTuning.WORLD_SPEED_SCALE;
        double safeLandingSpeed = FlightTuning.MAX_AIRSPEED * 0.82;
        if (session.gearDown && sinkSpeed <= safeSink && horizontalSpeed <= safeLandingSpeed) return true;

        session.gearFailed = true;
        if (session.gearDown && sinkSpeed <= 0.18 * FlightTuning.WORLD_SPEED_SCALE
                && horizontalSpeed <= FlightTuning.MAX_AIRSPEED * 0.92) {
            player.hurt(player.damageSources().generic(), 2.0f);
            return true;
        }
        crashAircraft(player, session, Math.max(sinkSpeed * 4.5, horizontalSpeed - safeLandingSpeed));
        return true;
    }

    private static void crashAircraft(ServerPlayer player, Session session, double impactSpeed) {
        if (session.crashed) return;
        Vec3 impactVelocity = session.airborne ? session.airVelocity
                : headingVector(session.headingDegrees).scale(session.groundSpeed);
        session.crashed = true;
        session.airborne = false;
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

        double impactRatio = impactSpeed / Math.max(0.45 * FlightTuning.WORLD_SPEED_SCALE, 1.0e-4);
        float impactDamage = (float) Math.max(2.0, Math.min(14.0, 2.0 + impactRatio * impactRatio));
        if (!session.gearDown) impactDamage += 2.0f;
        player.hurt(player.damageSources().generic(), impactDamage);
    }

    public static void toggleGear(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.gearFailed) {
            return;
        }
        if (session.airborne) {
            session.gearDown = !session.gearDown;
        } else if (!session.gearDown) {
            session.gearDown = true;
        }
        sendState(player, session, true);
    }

    /** Starts both engines in sequence, with each starter running for three seconds. */
    public static void startEngines(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
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
        if (session != null && Math.abs(delta) == 1) {
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

    public static void togglePushback(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.airborne) {
            return;
        }
        if (session.pushback) {
            session.pushback = false;
            session.groundSpeed = 0.0;
        } else {
            // Disengage thrust and start pushback; the tug first slows the aircraft
            // to a stop, then begins rolling it backward at walking speed.
            session.throttle = 0.0;
            session.pushback = true;
        }
        sendState(player, session, true);
    }

    public static void tick(MinecraftServer server) {
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

        // Camera pitch is only an aircraft control in flight. On the runway it must not
        // pre-load the aircraft into a stall before it has even lifted off.
        if (session.airborne) {
            double commandedPitch = Math.max(-15.0, Math.min(50.0, -player.getXRot()));
            session.pitchDegrees = approach(session.pitchDegrees, commandedPitch, 0.35);
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
            if (!session.gearDown || session.airVelocity.y < -0.10 * FlightTuning.WORLD_SPEED_SCALE) {
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
            session.throttle = Math.min(1.0, session.throttle + FlightTuning.THROTTLE_LEVER_RATE);
        } else if ((session.controls & FlightControls.REVERSE) != 0) {
            session.throttle = Math.max(0.0, session.throttle - FlightTuning.THROTTLE_LEVER_RATE);
        }
    }

    private static void updateEngineSpool(Session session) {
        double targetSpool = runningEngines(session) == 0 ? 0.0 : 0.04 + session.throttle * 0.96;
        double responseRate = targetSpool > session.engineSpool
                ? FlightTuning.ENGINE_SPOOL_UP_RATE : FlightTuning.ENGINE_SPOOL_DOWN_RATE;
        session.engineSpool = approach(session.engineSpool, targetSpool, responseRate);
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
            targetRate = (left ? -1.0 : 1.0) * 0.30 * authority;
        }
        session.groundTurnRate = approach(session.groundTurnRate, targetRate, 0.02);
        session.headingDegrees = wrapDegrees(session.headingDegrees + session.groundTurnRate);
        applyHeading(player, session);
    }

    private static void updateBankAndTurn(ServerPlayer player, Session session) {
        int controls = session.controls;
        double targetBank = 0.0;
        if ((controls & FlightControls.LEFT) != 0 && (controls & FlightControls.RIGHT) == 0) {
            targetBank = -FlightTuning.MAX_BANK_DEGREES;
        } else if ((controls & FlightControls.RIGHT) != 0 && (controls & FlightControls.LEFT) == 0) {
            targetBank = FlightTuning.MAX_BANK_DEGREES;
        }

        session.bankDegrees = approach(session.bankDegrees, targetBank,
                FlightTuning.BANK_RESPONSE_DEGREES_PER_TICK);
        double speed = Math.max(0.75, session.airspeed);
        double turnRate = 0.70 * FlightTuning.WORLD_SPEED_SCALE
                * Math.tan(Math.toRadians(session.bankDegrees)) / speed;
        session.headingDegrees = wrapDegrees(session.headingDegrees + turnRate);
        applyHeading(player, session);
    }

    private static void simulateGroundRoll(ServerPlayer player, Session session) {
        player.setNoGravity(false);
        double speed = session.groundSpeed;
        if (session.brakesActive) {
            speed = Math.max(0.0, speed - FlightTuning.WHEEL_BRAKE_DECELERATION
                    - (session.spoilers ? 0.025 * FlightTuning.WORLD_SPEED_SCALE : 0.0));
        } else if (session.pushback) {
            if (speed > 0.02) {
                speed = Math.max(0.0, speed - 0.08 * FlightTuning.WORLD_SPEED_SCALE);
            } else {
                speed = Math.min(0.12 * FlightTuning.WORLD_SPEED_SCALE,
                        speed + 0.006 * FlightTuning.WORLD_SPEED_SCALE);
            }
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
        double direction = session.pushback ? -1.0 : 1.0;
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
        // Client movement packets update the player position and can overwrite the
        // entity's delta movement. Keep aircraft velocity in the server-owned session
        // so stall and lift use the simulated airspeed, not packet movement speed.
        Vec3 velocity = session.airVelocity;
        // Ambient wind advects the aircraft while aerodynamic forces respond to airspeed.
        Vec3 wind = new Vec3(session.windX, session.windY, session.windZ);
        Vec3 relativeAir = velocity.subtract(wind);
        double airspeed = relativeAir.length();
        double drag = ((session.gearDown ? 0.0012 : 0.00045) + session.flapPosition * 0.00055
                + (session.spoilers ? 0.0045 : 0.0)) / FlightTuning.WORLD_SPEED_SCALE;
        velocity = velocity.subtract(relativeAir.scale(drag * Math.max(0.15, airspeed)));
        Vec3 nose = player.getLookAngle();
        double engineFactor = runningEngines(session) / 2.0;
        velocity = velocity.add(nose.scale(session.engineSpool * FlightTuning.AIR_THRUST * engineFactor));

        double horizontalSpeed = velocity.subtract(wind).horizontalDistance();
        double flightPathAngle = Math.toDegrees(Math.atan2(velocity.y, Math.max(horizontalSpeed, 1.0e-4)));
        double pitchUp = session.pitchDegrees;
        double angleOfAttack = pitchUp - flightPathAngle;
        boolean stalled = horizontalSpeed < FlightTuning.STALL_SPEED
                || angleOfAttack > FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES || pitchUp >= 50.0;

        double effectiveAngle = Math.max(0.0, Math.min(20.0, angleOfAttack + 6.0)) + session.flapPosition * 2.4;
        double lift = stalled ? 0.0
                : Math.min(0.22 * FlightTuning.WORLD_SPEED_SCALE,
                horizontalSpeed * horizontalSpeed * 0.0011 / FlightTuning.WORLD_SPEED_SCALE
                        * effectiveAngle);
        lift *= Math.cos(Math.toRadians(session.bankDegrees));
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
        if (session.phase == FlightPhase.STALL) warnings |= FlightWarning.STALL;
        if (session.phase == FlightPhase.AIRBORNE && radioAltitude < 1000 && verticalSpeed < -1000) warnings |= FlightWarning.SINK_RATE;
        if (session.airborne && session.takeoffWarningTicks > 0 && radioAltitude < 200
                && verticalSpeed < -300) warnings |= FlightWarning.DONT_SINK;
        if (radioAltitude < 300 && verticalSpeed < -700) warnings |= FlightWarning.PULL_UP;
        if (session.windShearTicks > 0) warnings |= FlightWarning.WINDSHEAR;
        int food = player.getFoodData().getFoodLevel();
        if (food <= 0) warnings |= FlightWarning.FUEL_EMPTY;
        else if (food <= 6) warnings |= FlightWarning.FUEL_LOW;
        if (session.engine1State == ENGINE_FIRE || session.engine2State == ENGINE_FIRE) warnings |= FlightWarning.ENGINE_FIRE;
        else if (session.engine1State == ENGINE_FAILED || session.engine2State == ENGINE_FAILED) warnings |= FlightWarning.ENGINE_FAILURE;
        if (Math.abs(session.bankDegrees) > 35.0) warnings |= FlightWarning.BANK_ANGLE;
        if (session.airborne && radioAltitude < 500 && !session.gearDown) warnings |= FlightWarning.GEAR_UP;
        if (session.airborne && radioAltitude < 300 && session.gearDown && session.flapDetent == 0) {
            warnings |= FlightWarning.TOO_LOW_FLAPS;
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
        if (session.airborne) {
            double closestResolution = Double.MAX_VALUE;
            int resolutionCommand = 0;
            for (ServerPlayer traffic : server.getPlayerList().getPlayers()) {
                if (traffic == player || traffic.level() != player.level()) continue;
                Session intruder = SESSIONS.get(traffic.getUUID());
                if (intruder == null || !intruder.airborne) continue;
                Vec3 separation = traffic.position().subtract(player.position());
                Vec3 relativeVelocity = intruder.airVelocity.subtract(session.airVelocity);
                double relativeSpeedSquared = relativeVelocity.lengthSqr();
                double timeToClosest = relativeSpeedSquared < 1.0e-6 ? 0.0
                        : -separation.dot(relativeVelocity) / relativeSpeedSquared;
                if (timeToClosest < 0.0 || timeToClosest > 400.0) continue;
                Vec3 closestSeparation = separation.add(relativeVelocity.scale(timeToClosest));
                double horizontal = closestSeparation.horizontalDistance();
                double vertical = Math.abs(closestSeparation.y);
                if (horizontal < 48.0 * FlightTuning.WORLD_SPEED_SCALE
                        && vertical < 16.0 * FlightTuning.WORLD_SPEED_SCALE) warnings |= FlightWarning.TCAS_TRAFFIC;
                double combinedDistance = horizontal + vertical * 2.0;
                if (timeToClosest < 300.0 && horizontal < 16.0 * FlightTuning.WORLD_SPEED_SCALE
                        && vertical < 8.0 * FlightTuning.WORLD_SPEED_SCALE
                        && combinedDistance < closestResolution) {
                    closestResolution = combinedDistance;
                    resolutionCommand = separation.y > 0.5 ? FlightWarning.TCAS_DESCEND : FlightWarning.TCAS_CLIMB;
                }
            }
            if (resolutionCommand != 0) warnings |= FlightWarning.TCAS_RESOLUTION | resolutionCommand;
        }
        session.warningFlags = warnings;
        session.radioAltitudeFeet = radioAltitude;
        session.verticalSpeedFpm = verticalSpeed;
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

    private static void sendState(ServerPlayer player, Session session, boolean enabled) {
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
                ? new FlightInstrumentPayload(0, 0, 0.0f, 0.0f, 0.0f, 0, 0, 0)
                : new FlightInstrumentPayload(session.radioAltitudeFeet, session.verticalSpeedFpm,
                (float) session.bankDegrees, (float) session.pitchDegrees, (float) session.headingDegrees,
                session.gearFailed ? 2 : (session.gearDown ? 0 : 1),
                (session.engine1State & 7) | ((session.engine2State & 7) << 3)
                        | (session.brakesActive ? 1 << 6 : 0) | (session.pushback ? 1 << 7 : 0)
                        | (session.spoilers ? 1 << 8 : 0) | ((session.flapDetent & 7) << 9)
                        | (Math.round((float) session.engineSpool * 100.0f) << 12)
                        | ((Math.min(127, session.windSpeedKnots) & 0x7F) << 19)
                        | ((session.windDirectionSector & 0x3F) << 26),
                session.warningFlags | (session.gearFailed ? FlightWarning.GEAR_FAILED : 0));
        ServerPlayNetworking.send(player, instruments);
    }

    private static final class Session {
        private final boolean hadNoGravity;
        private int controls;
        private int takeoffGraceTicks;
        private int takeoffWarningTicks;
        private int engineStartTicks;
        private int gearHeatTicks;
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
        private double groundSpeed;
        private double throttle;
        private double engineSpool;
        private double bankDegrees;
        private double pitchDegrees;
        private double headingDegrees;
        private double groundTurnRate;
        private float airspeed;
        private Vec3 airVelocity = Vec3.ZERO;
        private boolean airborne;
        private boolean gearDown = true;
        private boolean gearFailed;
        private int engine1State = ENGINE_OFF;
        private int engine2State = ENGINE_OFF;
        private boolean brakesActive;
        private boolean pushback;
        private boolean spoilers;
        private boolean landingRoll;
        private boolean crashed;
        private int crashTicksRemaining;
        private boolean impactHandled;
        private double engineTemperature = 20.0;
        private FlightPhase phase = FlightPhase.TAXI;

        private Session(boolean hadNoGravity) {
            this.hadNoGravity = hadNoGravity;
        }
    }
}
