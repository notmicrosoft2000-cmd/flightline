package dev.flightline.flight;

import dev.flightline.network.AtcOrderPayload;
import dev.flightline.network.AtcRadioPayload;
import dev.flightline.network.EmergencyStatusPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.NavDataPayload;
import dev.flightline.network.FlightNoticePayload;
import dev.flightline.network.FlightRadarPayload;
import dev.flightline.network.FlightStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
    private static final int FIRE_PARTICLE_INTERVAL_TICKS = 2;
    private static final int FIRE_BOTTLES = 2;
    private static final double RESTART_TEMPERATURE_AFTER_SUPPRESSION = 820.0;
    private static final double EMERGENCY_AUTOLAND_MIN_SPEED_FACTOR = 1.05;
    private static final double TRAFFIC_SCOPE_BLOCKS = 128.0;
    private static final double RADAR_SCOPE_BLOCKS = 4096.0;
    private static final int MAX_RADAR_CONTACTS = FlightRadarPayload.MAX_CONTACTS;
    private static final int STATUS_SEND_INTERVAL_TICKS = 2;
    private static final int EMERGENCY_STATUS_SEND_INTERVAL_TICKS = 5;
    private static final int STATUS_RESYNC_TICKS = 100;
    private static final int ATC_CONTROL_RANGE_BLOCKS = 2048;
    private static final ResourceKey<DamageType> AIRCRAFT_CRASH_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("flightline", "aircraft_crash"));
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> FLIGHT_DISABLED = new HashSet<>();
    private static final Map<UUID, AircraftType> PLAYER_AIRCRAFT = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, RadarSnapshot> RADAR_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ForcedWindshear> FORCED_WINDSHEARS = new ConcurrentHashMap<>();

    private FlightController() {
    }

    public static AircraftType getAircraftType(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        if (s != null) return s.aircraftType;
        return PLAYER_AIRCRAFT.getOrDefault(player.getUUID(), AircraftType.KASA_A380);
    }

    public static @Nullable FlightPlan getFlightPlan(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null ? null : session.flightPlan;
    }

    public static NavMode getNavMode(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null ? NavMode.HDG : session.navMode;
    }

    public static void setFlightPlan(ServerPlayer player, @Nullable FlightPlan plan) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) session.flightPlan = plan;
    }

    public static void setNavMode(ServerPlayer player, NavMode mode) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return;
        session.navMode = mode;
        if (mode != NavMode.HDG) session.autopilot = true;
        if (mode != NavMode.ILS) clearAutoland(player, session, false);
        sendState(player, session, true);
    }

    /** Tunes a database runway and arms the existing ILS/autoland path when conditions allow. */
    public static boolean tuneApproach(ServerPlayer player, NavDataPayload.Runway runway) {
        if (!setIlsReference(player, runway.thresholdX(), runway.thresholdZ(),
                runway.headingDegrees(), runway.glideSlopeDegrees())) return false;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return false;
        session.navMode = NavMode.ILS;
        session.autopilot = true;
        if (calculateIlsGuidance(player, session).active()) toggleAutoland(player);
        return true;
    }

    /** Loads a filed plan's cruise altitude into the MCP without changing the active nav mode. */
    public static boolean syncCruiseAltitude(ServerPlayer player, int altitudeFeet) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed || altitudeFeet < 100 || altitudeFeet > 40_000) return false;
        if (session.atcClearance != null) releaseAtcControl(player, session, "pilot altitude input");
        session.autopilotAltitudeFeet = altitudeFeet;
        sendState(player, session, true);
        return true;
    }

    public static boolean setAircraftType(ServerPlayer player, AircraftType type) {
        PLAYER_AIRCRAFT.put(player.getUUID(), type);
        Session s = SESSIONS.get(player.getUUID());
        if (s != null) {
            double previousCapacity = Math.max(1.0, s.aircraftType.fuelCapacityPercent());
            double fuelFraction = Math.max(0.0, Math.min(1.0, s.fuelPercent / previousCapacity));
            s.aircraftType = type;
            s.fuelPercent = type.fuelCapacityPercent() * fuelFraction;
            if (type == AircraftType.GINA_787 && s.autopilotAirspeedKnots == 160) {
                s.autopilotAirspeedKnots = 245;
            } else if (type == AircraftType.KASA_A380 && s.autopilotAirspeedKnots == 245) {
                s.autopilotAirspeedKnots = 160;
            }
        }
        ServerPlayNetworking.send(player, new dev.flightline.network.SelectPlanePayload(type.id()));
        return true;
    }

    public static void enable(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID()) || FLIGHT_DISABLED.contains(player.getUUID())) return;
        if (!player.isAlive() || player.isSpectator() || player.isPassenger()) {
            return;
        }

        Session session = new Session(player.isNoGravity());
        session.aircraftType = PLAYER_AIRCRAFT.getOrDefault(player.getUUID(), AircraftType.KASA_A380);
        session.fuelPercent = session.aircraftType.fuelCapacityPercent();
        if (session.aircraftType == AircraftType.GINA_787) session.autopilotAirspeedKnots = 245;
        session.headingDegrees = player.getYRot();
        Vec3 velocity = player.getDeltaMovement();
        session.groundSpeed = velocity.horizontalDistance();
        session.airVelocity = velocity;
        session.airspeed = (float) session.groundSpeed;
        session.airborne = !player.onGround();
        session.phase = session.airborne ? FlightPhase.AIRBORNE : FlightPhase.TAXI;
        SESSIONS.put(player.getUUID(), session);
        restoreNavigationState(player, session, false);
        player.setNoGravity(session.airborne || session.hadNoGravity);
    }

    /** Restores persisted plan and ILS state into a newly created session. */
    private static void restoreNavigationState(ServerPlayer player, Session session, boolean freshAircraft) {
        session.flightPlan = FlightPlanService.loadPlan(player);
        FlightPlanStorage.IlsState savedIls = FlightPlanService.loadIls(player);
        var level = player.level();
        String currentDimension = level.dimension().identifier().toString();
        boolean matchingDimension = !savedIls.dimension().isEmpty()
                && savedIls.dimension().equals(currentDimension);
        if (savedIls.active() && matchingDimension) {
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    savedIls.thresholdX(), savedIls.thresholdZ());
            if (y >= level.getMinY() && y < level.getMaxY()) {
                session.ilsDimension = level.dimension();
                session.ilsThreshold = new Vec3(savedIls.thresholdX() + 0.5, y,
                        savedIls.thresholdZ() + 0.5);
                session.ilsRunwayHeading = savedIls.runwayHeadingDegrees();
                session.ilsGlideSlopeDegrees = savedIls.glideSlopeDegrees();
            }
        }
        boolean armAutoland = savedIls.autolandArmed() && !freshAircraft && session.airborne
                && matchingDimension && session.ilsDimension != null;
        session.autoland = armAutoland;
        if (armAutoland) {
            session.navMode = NavMode.ILS;
            session.autopilot = true;
        } else if (savedIls.autolandArmed()) {
            FlightPlanService.clearAutolandArming(player);
        }
    }

    public static void updateInput(ServerPlayer player, int controls) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return;
        int requested = controls & VALID_CONTROLS;
        if (session.atcClearance != null
                && (requested & (FlightControls.FORWARD | FlightControls.REVERSE
                | FlightControls.LEFT | FlightControls.RIGHT)) != 0) {
            releaseAtcControl(player, session, "manual pilot input");
        }
        session.controls = requested;
    }

    public static boolean isFlightActive(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    public static boolean isAirborne(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.airborne && !session.crashed;
    }

    public static boolean isAutolandArmed(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.autoland;
    }

    public static boolean isAtcControlled(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.atcClearance != null;
    }

    public static int atcControllerEntityId(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.atcClearance != null ? session.atcControllerEntityId : -1;
    }

    public static boolean releaseAtcControl(ServerPlayer pilot) {
        return releaseAtcControl(pilot, "ATC release");
    }

    public static boolean releaseAtcControl(ServerPlayer pilot, String reason) {
        Session session = SESSIONS.get(pilot.getUUID());
        return session != null && releaseAtcControl(pilot, session, reason) != null;
    }

    /** Applies an operator clearance to a player's authoritative flight deck. */
    public static boolean issueAtcOrder(ServerPlayer controller, ServerPlayer pilot, int order,
                                        int value, int referenceEntityId) {
        Session session = SESSIONS.get(pilot.getUUID());
        if (session == null || session.crashed || !session.airborne || pilot.isSpectator()
                || !AtcClearance.isValidOrder(order) || !AtcClearance.isValidValue(order, value)) {
            return false;
        }
        if (order == AtcOrderPayload.FOLLOW
                && !isValidFollowTarget(pilot, controller, referenceEntityId)) {
            return false;
        }

        int currentAltitudeFeet = (int) Math.round(pilot.getY() * 3.28084);
        int currentSpeedKnots = (int) Math.round(session.airspeed * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
        AtcClearance clearance = AtcClearance.create(order, value, referenceEntityId,
                session.headingDegrees, currentAltitudeFeet, currentSpeedKnots);

        if (session.atcClearance != null) {
            releaseAtcControl(pilot, session, "control reassigned");
        }
        session.atcControllerId = controller.getUUID();
        session.atcControllerEntityId = controller.getId();
        session.atcClearance = clearance;
        session.atcControlTicks = 0;
        session.autopilot = true;
        session.navMode = NavMode.HDG;
        clearAutoland(pilot, session, false);
        session.emergencyDescent = false;
        applyAtcTargets(session, clearance);
        sendState(pilot, session, true);
        notify(pilot, "ATC " + controller.getGameProfile().name() + " assumed control: "
                + clearance.radioText() + ". F7 returns control.");
        return true;
    }

    private static boolean isValidFollowTarget(ServerPlayer pilot, ServerPlayer controller, int entityId) {
        net.minecraft.world.entity.Entity lead = pilot.level().getEntity(entityId);
        if (lead == null || !lead.isAlive() || lead == pilot || lead == controller
                || lead.level() != pilot.level()
                || lead.position().distanceToSqr(pilot.position()) > ATC_CONTROL_RANGE_BLOCKS
                * (double) ATC_CONTROL_RANGE_BLOCKS) {
            return false;
        }
        if (lead instanceof ServerPlayer leadPlayer) {
            Session leadSession = SESSIONS.get(leadPlayer.getUUID());
            return leadSession != null && !leadSession.crashed;
        }
        return lead instanceof net.minecraft.world.entity.Mob leadMob
                && MobFlightController.suppressVanillaFallDamage(leadMob);
    }

    private static void applyAtcTargets(Session session, AtcClearance clearance) {
        session.autopilotHeading = clearance.targetHeadingDegrees();
        session.autopilotAltitudeFeet = clearance.targetAltitudeFeet();
        session.autopilotVerticalSpeedFpm = clearance.targetVerticalSpeedFpm();
        session.autopilotAirspeedKnots = clearance.targetAirspeedKnots();
    }

    private static void updateAtcControl(ServerPlayer pilot, Session session, MinecraftServer server) {
        AtcClearance clearance = session.atcClearance;
        if (clearance == null) return;
        ServerPlayer controller = findPlayer(server, session.atcControllerId);
        if (controller == null || !controller.isAlive() || controller.isSpectator()
                || controller.level() != pilot.level()
                || controller.position().distanceToSqr(pilot.position()) > ATC_CONTROL_RANGE_BLOCKS
                * (double) ATC_CONTROL_RANGE_BLOCKS
                || !session.airborne || session.crashed) {
            releaseAtcControl(pilot, session, "controller unavailable");
            return;
        }

        session.atcControlTicks++;
        session.autopilot = true;
        if (!clearance.dynamicTarget()) return;

        Vec3 target;
        int altitudeFeet;
        int speedKnots;
        switch (clearance.order()) {
            case AtcOrderPayload.ORBIT -> {
                double angle = pilot.level().getGameTime() * 0.015
                        + pilot.getUUID().getLeastSignificantBits() * 0.000001;
                target = controller.position().add(Math.cos(angle) * 48.0, 0.0, Math.sin(angle) * 48.0);
                altitudeFeet = session.autopilotAltitudeFeet;
                speedKnots = 160;
            }
            case AtcOrderPayload.RETURN -> {
                target = controller.position();
                altitudeFeet = Math.max(500,
                        (int) Math.round((controller.getY() + 80.0) * 3.28084));
                speedKnots = 130;
                if (target.subtract(pilot.position()).horizontalDistance() < 24.0) {
                    releaseAtcControl(pilot, session, "arrival at controller");
                    return;
                }
            }
            case AtcOrderPayload.FOLLOW -> {
                FollowTarget follow = findFollowTarget(pilot, server, clearance.referenceEntityId());
                if (follow == null) {
                    releaseAtcControl(pilot, session, "follow aircraft lost");
                    return;
                }
                Vec3 behind = headingVector(follow.headingDegrees()).scale(24.0);
                target = follow.position().subtract(behind).add(0.0, 6.0, 0.0);
                altitudeFeet = (int) Math.round(follow.position().y * 3.28084) + 300;
                speedKnots = Math.max(AtcClearance.MIN_SPEED_KNOTS,
                        Math.min(AtcClearance.MAX_SPEED_KNOTS, follow.speedKnots() + 10));
            }
            default -> {
                return;
            }
        }

        Vec3 delta = target.subtract(pilot.position());
        session.autopilotHeading = Math.floorMod((int) Math.round(
                Math.toDegrees(Math.atan2(-delta.x, delta.z))), 360);
        session.autopilotAltitudeFeet = Math.max(AtcClearance.MIN_ALTITUDE_FEET, altitudeFeet);
        session.autopilotVerticalSpeedFpm = 1200;
        session.autopilotAirspeedKnots = speedKnots;
    }

    private record FollowTarget(Vec3 position, double headingDegrees, int speedKnots) {
    }

    private static @Nullable FollowTarget findFollowTarget(ServerPlayer pilot, MinecraftServer server,
                                                          int entityId) {
        net.minecraft.world.entity.Entity lead = pilot.level().getEntity(entityId);
        if (lead == null || !lead.isAlive() || lead.level() != pilot.level()) return null;
        if (lead instanceof ServerPlayer leadPlayer) {
            Session leadSession = SESSIONS.get(leadPlayer.getUUID());
            if (leadSession == null || leadSession.crashed) return null;
            int knots = (int) Math.round(leadSession.airspeed * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
            return new FollowTarget(leadPlayer.position(), leadSession.headingDegrees, knots);
        }
        if (lead instanceof net.minecraft.world.entity.Mob leadMob
                && MobFlightController.suppressVanillaFallDamage(leadMob)
                && pilot.level() instanceof ServerLevel level) {
            for (MobFlightController.Traffic traffic : MobFlightController.aircraftIn(level)) {
                if (traffic.entityId() == entityId) {
                    int knots = (int) Math.round(traffic.velocity().horizontalDistance()
                            * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
                    return new FollowTarget(traffic.position(), traffic.headingDegrees(), knots);
                }
            }
        }
        return null;
    }

    private static @Nullable ServerPlayer findPlayer(MinecraftServer server, @Nullable UUID playerId) {
        if (playerId == null) return null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(playerId)) return player;
        }
        return null;
    }

    /** Returns control to the pilot and tells the controller why the clearance ended. */
    public static @Nullable ServerPlayer releaseAtcControl(ServerPlayer pilot, Session session, String reason) {
        if (session.atcClearance == null) return null;
        ServerPlayer controller = findPlayer(pilot.level().getServer(), session.atcControllerId);
        session.atcControllerId = null;
        session.atcControllerEntityId = -1;
        session.atcClearance = null;
        session.atcControlTicks = 0;
        session.autopilot = false;
        clearAutoland(pilot, session, true);
        if (controller != null) {
            String message = "RELEASE FROM " + pilot.getGameProfile().name()
                    + ": control returned to pilot (" + reason + ").";
            ServerPlayNetworking.send(controller, new AtcRadioPayload(message));
            controller.sendSystemMessage(Component.literal("[ATC RADIO] " + message));
        }
        notify(pilot, "ATC control released: " + reason + ". Pilot authority restored.");
        sendState(pilot, session, true);
        return controller;
    }

    public static boolean setIlsReference(ServerPlayer player, float glideSlopeDegrees) {
        return setIlsReference(player, player.blockPosition().getX(), player.blockPosition().getZ(),
                player.getYRot(), glideSlopeDegrees);
    }

    public static boolean setIlsReference(ServerPlayer player, int thresholdX, int thresholdZ,
                                          float runwayHeadingDegrees, float glideSlopeDegrees) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !Float.isFinite(glideSlopeDegrees)
                || glideSlopeDegrees < 1.0f || glideSlopeDegrees > 6.0f
                || !Float.isFinite(runwayHeadingDegrees) || Math.abs(thresholdX) > 29_999_984
                || Math.abs(thresholdZ) > 29_999_984
                || !player.level().getWorldBorder().isWithinBounds(thresholdX, thresholdZ)) return false;
        int thresholdY = player.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, thresholdX, thresholdZ);
        if (thresholdY < player.level().getMinY() || thresholdY >= player.level().getMaxY()) return false;
        session.ilsDimension = player.level().dimension();
        session.ilsThreshold = new Vec3(thresholdX + 0.5, thresholdY, thresholdZ + 0.5);
        session.ilsRunwayHeading = (float) wrapDegrees(runwayHeadingDegrees);
        session.ilsGlideSlopeDegrees = glideSlopeDegrees;
        persistIls(player, session);
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
        session.navMode = NavMode.HDG;
        disengageAutoland(player, session);
        persistIls(player, session);
        sendState(player, session, true);
        return true;
    }

    private static void persistIls(ServerPlayer player, Session session) {
        FlightPlanService.saveIls(player, new FlightPlanStorage.IlsState(
                session.ilsDimension != null,
                session.ilsGlideSlopeDegrees,
                (int) Math.round(session.ilsThreshold.x - 0.5),
                (int) Math.round(session.ilsThreshold.z - 0.5),
                session.ilsRunwayHeading,
                session.autoland,
                session.ilsDimension == null ? "" : session.ilsDimension.identifier().toString()));
    }

    public static void disableFlight(ServerPlayer player) {
        FLIGHT_DISABLED.add(player.getUUID());
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.atcClearance != null) {
            releaseAtcControl(player, session, "flight mode disabled");
        }
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
            if (session.autoland) {
                clearAutoland(player, session, true);
                session.throttle = 0.0;
                session.spoilers = true;
                session.autolandRolloutTicks = 100;
            }
            session.airborne = false;
            session.groundSpeed = horizontalSpeed;
            session.airVelocity = Vec3.ZERO;
            session.landingRoll = true;
            completeEmergencyLanding(player, session);
            player.setNoGravity(false);
            player.resetFallDistance();
            player.setDeltaMovement(headingVector(session.headingDegrees).scale(session.groundSpeed));
            setPhase(player, session, FlightPhase.LANDING_ROLL);
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

    /**
     * Ground crews can secure a fire only after the aircraft is safely on the surface. This gives
     * players a real emergency-landing outcome even when the extinguisher window has passed.
     */
    private static void completeEmergencyLanding(ServerPlayer player, Session session) {
        boolean fire = false;
        for (int i = 0; i < 2; i++) {
            if (engineState(session, i) != ENGINE_FIRE) continue;
            setEngineState(session, i, ENGINE_FAILED);
            session.engineFireTicks[i] = 0;
            session.engineHotTicks[i] = 0;
            session.engineFireDamageCooldown[i] = 0;
            session.engineTemperatures[i] = 20.0;
            fire = true;
        }
        if (fire) {
            notify(player, "Emergency landing complete. Ground crew secured the engine fire.");
        }
        if (session.emergencyDeclared) {
            session.emergencyDeclared = false;
            notify(player, "MAYDAY closed. Emergency landing complete.");
        }
        session.emergencyDescent = false;
        session.fuelDumping = false;
        session.fuelDumpTicks = 0;
    }

    static LandingAssessment assessLanding(boolean gearDown, double sinkSpeed, double horizontalSpeed) {
        if (!gearDown) {
            return new LandingAssessment(true, true, 0.0f);
        }
        if (sinkSpeed <= FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED
                && horizontalSpeed <= FlightTuning.SAFE_TOUCHDOWN_SPEED) {
            return new LandingAssessment(false, false, 0.0f);
        }
        if (sinkSpeed <= FlightTuning.MAX_HARD_LANDING_SINK_SPEED
                && horizontalSpeed <= FlightTuning.MAX_HARD_LANDING_SPEED) {
            double sinkRatio = sinkSpeed / FlightTuning.MAX_HARD_LANDING_SINK_SPEED;
            double excessSpeedRatio = Math.max(0.0,
                    (horizontalSpeed - FlightTuning.SAFE_TOUCHDOWN_SPEED) / FlightTuning.MAX_HARD_LANDING_SPEED);
            float damage = (float) Math.min(8.0, 1.0 + sinkRatio * 4.0 + excessSpeedRatio * 3.0);
            boolean gearFailed = sinkSpeed > FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED
                    || horizontalSpeed > FlightTuning.SAFE_TOUCHDOWN_SPEED;
            return new LandingAssessment(false, gearFailed, damage);
        }
        return new LandingAssessment(true, true, 0.0f);
    }

    static double autolandFlareVerticalSpeed(double verticalSpeed, int radioAltitudeFeet) {
        if (radioAltitudeFeet <= 20) return Math.max(verticalSpeed, -0.02);
        if (radioAltitudeFeet <= 60) return Math.max(verticalSpeed, -0.035);
        return verticalSpeed;
    }

    record LandingAssessment(boolean crashes, boolean gearFailed, float damage) {
    }

    private static void crashAircraft(ServerPlayer player, Session session, double impactSpeed) {
        if (session.crashed) return;
        Vec3 impactVelocity = session.airborne ? session.airVelocity
                : headingVector(session.headingDegrees).scale(session.groundSpeed);
        if (session.atcClearance != null) releaseAtcControl(player, session, "aircraft crash");
        session.crashed = true;
        session.airborne = false;
        session.autopilot = false;
        clearAutoland(player, session, true);
        session.landingRoll = false;
        session.engine1State = ENGINE_OFF;
        session.engine2State = ENGINE_OFF;
        session.throttle = 0.0;
        session.pushback = false;
        session.spoilers = true;
        session.antiIce = false;
        session.antiIceEffective = false;
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
        if (session == null || session.gearFailed || session.alternateGearExtended) {
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
        if (session == null || session.fuelPercent <= 0.0
                || session.engine1State != ENGINE_OFF || session.engine2State != ENGINE_OFF) {
            return;
        }
        session.throttle = 0.0;
        if (session.pushback) {
            session.groundSpeed = 0.0;
        }
        session.pushback = false;
        session.engine1State = ENGINE_STARTING;
        session.engine2State = ENGINE_STARTING;
        Arrays.fill(session.engineStartTicks, 0);
        Arrays.fill(session.engineRestartAttempts, 0);
        sendState(player, session, true);
    }

    /** A respawn always receives a fresh, ground-ready aircraft session. */
    public static void resetAfterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        Session oldSession = SESSIONS.get(oldPlayer.getUUID());
        if (oldSession != null && oldSession.atcClearance != null) {
            releaseAtcControl(oldPlayer, oldSession, "pilot respawn");
        }
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
        session.aircraftType = PLAYER_AIRCRAFT.getOrDefault(newPlayer.getUUID(),
                PLAYER_AIRCRAFT.getOrDefault(oldPlayer.getUUID(), AircraftType.KASA_A380));
        session.fuelPercent = session.aircraftType.fuelCapacityPercent();
        if (session.aircraftType == AircraftType.GINA_787) session.autopilotAirspeedKnots = 245;
        session.headingDegrees = newPlayer.getYRot();
        session.phase = FlightPhase.TAXI;
        SESSIONS.put(newPlayer.getUUID(), session);
        restoreNavigationState(newPlayer, session, true);
        sendState(newPlayer, session, true);
    }

    private static void resetCrashedAircraft(ServerPlayer player, Session session) {
        session.controls = 0;
        session.takeoffGraceTicks = 0;
        session.takeoffWarningTicks = 0;
        Arrays.fill(session.engineStartTicks, 0);
        Arrays.fill(session.engineRestartAttempts, 0);
        Arrays.fill(session.engineHotTicks, 0);
        Arrays.fill(session.engineFireTicks, 0);
        Arrays.fill(session.engineFireDamageCooldown, 0);
        Arrays.fill(session.engineTemperatures, 20.0);
        session.extinguisherBottles = FIRE_BOTTLES;
        session.emergencyDeclared = false;
        session.alternateGearExtended = false;
        session.hydraulicBackupUsed = false;
        session.emergencyDescent = false;
        session.fuelDumping = false;
        session.fuelDumpTicks = 0;
        session.gearHeatTicks = 0;
        session.gearGroundAlarmTicks = 0;
        session.windShearTicks = 0;
        session.flapDetent = 0;
        session.flapPosition = 0.0;
        session.radioAltitudeFeet = 0;
        session.verticalSpeedFpm = 0;
        session.warningFlags = 0;
        session.overspeedTicks = 0;
        session.overspeedDamageCooldown = 0;
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
        clearAutoland(player, session, true);
        session.autolandRolloutTicks = 0;
        session.gearDown = true;
        session.gearFailed = false;
        session.engine1State = ENGINE_OFF;
        session.engine2State = ENGINE_OFF;
        session.brakesActive = false;
        session.pushback = false;
        session.spoilers = false;
        session.airbrakes = false;
        session.fuelPercent = session.aircraftType.fuelCapacityPercent();
        session.landingRoll = false;
        session.crashed = false;
        session.crashTicksRemaining = 0;
        session.impactHandled = false;
        session.hydraulicFailTicks = 0;
        session.hydraulicFailed = false;
        session.cabinAltitudeFeet = 0;
        session.pressLost = false;
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
        // A burning engine cannot be extinguished by simply moving the fuel lever. Fire needs an
        // extinguisher bottle or an emergency landing.
        if (session.engine1State != ENGINE_FIRE) session.engine1State = ENGINE_OFF;
        if (session.engine2State != ENGINE_FIRE) session.engine2State = ENGINE_OFF;
        Arrays.fill(session.engineStartTicks, 0);
        if (engineState(session, 0) != ENGINE_FIRE && engineState(session, 1) != ENGINE_FIRE) {
            session.throttle = 0.0;
        }
        sendState(player, session, true);
    }

    public static void adjustFlaps(ServerPlayer player, int delta) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.flapDetent = Math.max(0, Math.min(4, session.flapDetent + delta));
            sendState(player, session, true);
        }
    }

    public static void setSpoilers(ServerPlayer player, boolean deployed) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.spoilers = deployed;
            sendState(player, session, true);
        }
    }

    public static void setAirbrake(ServerPlayer player, boolean deployed) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.airbrakes = deployed;
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

    public static boolean toggleAutoland(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed || !session.airborne) return false;
        if (session.atcClearance != null) releaseAtcControl(player, session, "pilot autoland input");
        if (session.autoland) {
            disengageAutoland(player, session);
            sendState(player, session, true);
            return true;
        }
        if (session.gearFailed || runningEngines(session) == 0
                || session.airspeed < FlightTuning.STALL_SPEED) return false;
        if (session.ilsDimension == null
                || !session.ilsDimension.equals(player.level().dimension())
                || !calculateIlsGuidance(player, session).active()) {
            return false;
        }

        session.autoland = true;
        session.autopilot = true;
        session.gearDown = true;
        session.flapDetent = Math.max(session.flapDetent, 3);
        session.spoilers = false;
        session.airbrakes = false;
        session.autopilotAirspeedKnots = 130;
        updateAutolandTargets(player, session);
        persistIls(player, session);
        sendState(player, session, true);
        return true;
    }

    public static void setAntiIce(ServerPlayer player, boolean enabled) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed) return;
        session.antiIce = enabled;
        sendState(player, session, true);
    }

    /** Executes a checklist procedure. The server revalidates every action before applying it. */
    public static void handleEmergencyAction(ServerPlayer player, int action, int target) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed) return;
        int engine = target == 1 ? 1 : 0;

        switch (action) {
            case EmergencyAction.EXTINGUISH_ENGINE -> {
                if (engineState(session, engine) != ENGINE_FIRE) {
                    notify(player, engineLabel(session, engine) + ": no fire detected.");
                    break;
                }
                if (session.extinguisherBottles <= 0) {
                    notify(player, "No extinguisher bottles remaining. Land immediately.");
                    break;
                }
                if (!FlightTuning.canSuppressFire(session.engineFireTicks[engine])) {
                    notify(player, engineLabel(session, engine) + " fire is beyond extinguisher range. Land now.");
                    break;
                }
                setEngineState(session, engine, ENGINE_FAILED);
                session.engineFireTicks[engine] = 0;
                session.engineHotTicks[engine] = 0;
                session.engineFireDamageCooldown[engine] = 0;
                session.engineTemperatures[engine] = RESTART_TEMPERATURE_AFTER_SUPPRESSION;
                session.extinguisherBottles--;
                notify(player, engineLabel(session, engine) + " fire extinguished. Engine failed; relight when cool.");
            }
            case EmergencyAction.RESTART_ENGINE -> {
                int state = engineState(session, engine);
                boolean failedState = state == ENGINE_FAILED || state == ENGINE_OFF;
                if (!FlightTuning.canRestartEngine(session.airborne, failedState, session.fuelPercent,
                        session.engineRestartAttempts[engine], session.airspeed, stallSpeed(session),
                        session.engineTemperatures[engine])) {
                    notify(player, engineLabel(session, engine) + " relight unavailable: need airspeed, fuel, cool engine and attempts left.");
                    break;
                }
                setEngineState(session, engine, ENGINE_STARTING);
                session.engineStartTicks[engine] = 0;
                session.engineRestartAttempts[engine]++;
                session.throttle = 0.0;
                notify(player, engineLabel(session, engine) + " windmilling start initiated.");
            }
            case EmergencyAction.ALTERNATE_GEAR -> {
                if (!session.gearFailed || session.alternateGearExtended) {
                    notify(player, "Alternate gear extension is not available.");
                    break;
                }
                session.alternateGearExtended = true;
                session.hydraulicBackupUsed = true;
                session.gearDown = true;
                session.gearFailed = false;
                notify(player, "Alternate gear extended. Gear is down and locked; avoid cycling it.");
            }
            case EmergencyAction.RESET_HYDRAULICS -> {
                if (!session.hydraulicFailed || session.hydraulicBackupUsed) {
                    notify(player, "Hydraulic backup is not needed.");
                    break;
                }
                session.hydraulicBackupUsed = true;
                session.hydraulicFailed = false;
                session.hydraulicFailTicks = 0;
                session.flapDetent = Math.max(session.flapDetent, 2);
                notify(player, "Hydraulic backup online. Flap and gear authority restored.");
            }
            case EmergencyAction.EMERGENCY_DESCENT -> {
                if (!session.airborne || !session.pressLost) {
                    notify(player, "Emergency descent is not required.");
                    break;
                }
                session.emergencyDescent = true;
                session.autopilot = true;
                clearAutoland(player, session, false);
                session.autopilotHeading = session.headingDegrees;
                session.autopilotAltitudeFeet = 10000;
                session.autopilotVerticalSpeedFpm = -2000;
                session.autopilotAirspeedKnots = Math.max(140, Math.min(250,
                        (int) Math.round(session.airspeed * FlightTuning.KNOTS_PER_BLOCK_PER_TICK)));
                notify(player, "Emergency descent set: 10,000 ft, -2000 fpm. Oxygen mask on.");
            }
            case EmergencyAction.TOGGLE_FUEL_DUMP -> {
                if (session.fuelDumping) {
                    session.fuelDumping = false;
                    session.fuelDumpTicks = 0;
                    notify(player, "Fuel jettison stopped.");
                } else if (!session.airborne) {
                    notify(player, "Fuel jettison is only available in flight.");
                } else if (session.fuelPercent <= FlightTuning.FUEL_JETTISON_RESERVE_PERCENT + 1.0) {
                    notify(player, "Fuel jettison locked: landing reserve reached.");
                } else {
                    session.fuelDumping = true;
                    session.fuelDumpTicks = 0;
                    notify(player, "Fuel jettison on. Stopping at 20% landing reserve.");
                }
            }
            case EmergencyAction.DECLARE_MAYDAY -> {
                if (session.emergencyDeclared) {
                    notify(player, "MAYDAY is already active.");
                    break;
                }
                session.emergencyDeclared = true;
                broadcastMayday(player, session);
                notify(player, "MAYDAY broadcast to all aircraft: " + emergencySummary(session));
            }
            case EmergencyAction.CANCEL_MAYDAY -> {
                if (!session.emergencyDeclared) {
                    notify(player, "No MAYDAY is active.");
                    break;
                }
                session.emergencyDeclared = false;
                notify(player, "MAYDAY cancelled. Resume normal radio calls.");
            }
            case EmergencyAction.EMERGENCY_AUTOLAND -> {
                if (session.atcClearance != null) releaseAtcControl(player, session, "emergency autoland");
                if (!canEmergencyAutoland(player, session)) {
                    notify(player, "Emergency autoland needs an active ILS, airspeed margin and a running engine.");
                    break;
                }
                session.autoland = true;
                session.autopilot = true;
                session.gearDown = true;
                session.flapDetent = Math.max(session.flapDetent, session.hydraulicBackupUsed ? 2 : 3);
                session.spoilers = false;
                session.airbrakes = false;
                session.autopilotAirspeedKnots = 130;
                session.autopilotVerticalSpeedFpm = -1200;
                updateAutolandTargets(player, session);
                persistIls(player, session);
                notify(player, "Emergency autoland armed. Monitor the glide path and configure for landing.");
            }
            default -> {
                return;
            }
        }
        sendState(player, session, true);
    }

    /**
     * Operator-only debug override. The override is scoped to the executing player's dimension and
     * only arms during storm weather; sessions still receive the event through updateWeather.
     */
    public static boolean forceWindshear(ServerPlayer player, int durationTicks) {
        if (!(player.level() instanceof ServerLevel level) || durationTicks <= 0 || !level.isThundering()) {
            return false;
        }
        FORCED_WINDSHEARS.put(level.dimension(), new ForcedWindshear(durationTicks, null));
        return true;
    }

    public static void clearForcedWindshear(ServerPlayer player) {
        FORCED_WINDSHEARS.remove(player.level().dimension());
    }

    /**
     * Operator-only debug injection. Everything is applied here on the server so the client can
     * never write emergency state directly, and each engine scenario targets exactly one slot.
     */
    public static boolean debugCauseEmergency(ServerPlayer player, EmergencyScenario scenario, int target) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed) {
            notify(player, "Debug emergency unavailable: activate flight first.");
            return false;
        }
        if (scenario == null) {
            notify(player, "Unknown debug emergency.");
            return false;
        }
        int engine = target == 1 ? 1 : 0;
        String name = engineLabel(session, engine);

        switch (scenario) {
            case FIRE -> {
                setEngineState(session, engine, ENGINE_FIRE);
                session.engineFireTicks[engine] = 0;
                session.engineFireDamageCooldown[engine] = 0;
                session.engineHotTicks[engine] = 0;
                session.engineTemperatures[engine] = 1150.0;
                playFireBurst(player);
                notify(player, "DEBUG: " + name + " fire injected. Only this engine is burning.");
            }
            case FAILURE -> {
                setEngineState(session, engine, ENGINE_FAILED);
                session.engineFireTicks[engine] = 0;
                session.engineFireDamageCooldown[engine] = 0;
                session.engineHotTicks[engine] = 0;
                session.engineTemperatures[engine] = 20.0;
                notify(player, "DEBUG: " + name + " failure injected. Other engines remain available.");
            }
            case GEAR -> {
                session.gearFailed = true;
                session.gearDown = false;
                session.alternateGearExtended = false;
                notify(player, "DEBUG: gear failure injected. Use alternate gear extension.");
            }
            case HYDRAULIC -> {
                session.hydraulicFailed = true;
                session.hydraulicBackupUsed = false;
                session.hydraulicFailTicks = 600;
                session.flapDetent = Math.min(session.flapDetent, 2);
                notify(player, "DEBUG: hydraulic failure injected. Use hydraulic backup.");
            }
            case CABIN -> {
                session.pressLost = true;
                session.cabinAltitudeFeet = Math.max(session.cabinAltitudeFeet, 12000);
                notify(player, "DEBUG: pressurisation failure injected. Use emergency descent.");
            }
            case FUEL -> {
                session.fuelPercent = 0.0;
                session.fuelDumping = false;
                session.fuelDumpTicks = 0;
                notify(player, "DEBUG: fuel exhaustion injected. Glide and land.");
            }
            case ICE -> {
                session.icingPercent = 65.0;
                notify(player, "DEBUG: severe airframe ice injected. Use anti-ice and keep speed margin.");
            }
            case OVERSPEED -> {
                if (!session.airborne) {
                    notify(player, "DEBUG: overspeed needs an airborne aircraft.");
                    return false;
                }
                session.airspeed = (float) (session.aircraftType.maxAirspeedBlocksPerTick() * 1.03);
                session.overspeedTicks = FlightTuning.OVERSPEED_STRESS_START_TICKS + 1;
                notify(player, "DEBUG: overspeed injected. Reduce throttle and level the nose.");
            }
            case CLEAR -> {
                clearDebugEmergencies(player, session);
                notify(player, "DEBUG: emergency conditions cleared.");
            }
        }
        sendState(player, session, true);
        return true;
    }

    private static void clearDebugEmergencies(ServerPlayer player, Session session) {
        for (int i = 0; i < 2; i++) {
            setEngineState(session, i, ENGINE_RUNNING);
            session.engineFireTicks[i] = 0;
            session.engineFireDamageCooldown[i] = 0;
            session.engineHotTicks[i] = 0;
            session.engineStartTicks[i] = 0;
            session.engineRestartAttempts[i] = 0;
            session.engineTemperatures[i] = 420.0;
        }
        session.extinguisherBottles = FIRE_BOTTLES;
        session.gearFailed = false;
        session.gearDown = true;
        session.alternateGearExtended = false;
        session.gearHeatTicks = 0;
        session.hydraulicFailed = false;
        session.hydraulicBackupUsed = false;
        session.hydraulicFailTicks = 0;
        session.pressLost = false;
        session.cabinAltitudeFeet = 0;
        session.icingPercent = 0.0;
        session.overspeedTicks = 0;
        session.overspeedDamageCooldown = 0;
        session.windShearTicks = 0;
        session.windShearSeverity = 0.0;
        session.fuelDumping = false;
        session.fuelDumpTicks = 0;
        if (session.emergencyDescent) {
            session.autopilot = false;
            clearAutoland(player, session, true);
        }
        session.emergencyDescent = false;
        session.emergencyDeclared = false;
        session.fuelPercent = session.aircraftType.fuelCapacityPercent();
        session.throttle = 0.0;
        session.engineSpool = 0.04;
        ServerPlayNetworking.send(player, new FlightNoticePayload("DEBUG: aircraft systems restored."));
    }

    private static double stallSpeed(Session session) {
        return FlightTuning.engineOutStallSpeed(
                FlightTuning.icedStallSpeed(session.icingPercent, session.flapPosition),
                runningEngines(session) > 0);
    }

    private static boolean canEmergencyAutoland(ServerPlayer player, Session session) {
        if (session.crashed || !session.airborne || session.autoland || runningEngines(session) == 0) {
            return false;
        }
        if (session.airspeed <= stallSpeed(session) * EMERGENCY_AUTOLAND_MIN_SPEED_FACTOR) {
            return false;
        }
        return session.ilsDimension != null
                && session.ilsDimension.equals(player.level().dimension())
                && calculateIlsGuidance(player, session).active();
    }

    private static void broadcastMayday(ServerPlayer player, Session session) {
        String message = "MAYDAY MAYDAY MAYDAY " + radarName(player.getGameProfile().name())
                + " " + emergencySummary(session) + " POS "
                + player.blockPosition().getX() + "," + player.blockPosition().getZ()
                + " ALT " + Math.round(player.getY() * 3.28084) + "FT";
        for (ServerPlayer listener : player.level().getServer().getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(listener, new AtcRadioPayload(message));
            listener.sendSystemMessage(Component.literal("[MAYDAY] " + message));
        }
    }

    private static String emergencySummary(Session session) {
        int conditions = emergencyConditions(session);
        List<String> parts = new ArrayList<>();
        if ((conditions & EmergencyStatus.FIRE_1) != 0) parts.add("ENG1 FIRE");
        if ((conditions & EmergencyStatus.FIRE_2) != 0) parts.add("ENG2 FIRE");
        if ((conditions & EmergencyStatus.FAILURE_1) != 0) parts.add("ENG1 FAIL");
        if ((conditions & EmergencyStatus.FAILURE_2) != 0) parts.add("ENG2 FAIL");
        if ((conditions & EmergencyStatus.FUEL_EMPTY) != 0) parts.add("FUEL EMPTY");
        else if ((conditions & EmergencyStatus.FUEL_LOW) != 0) parts.add("LOW FUEL");
        if ((conditions & EmergencyStatus.GEAR_FAILED) != 0) parts.add("GEAR FAIL");
        if ((conditions & EmergencyStatus.HYDRAULIC) != 0) parts.add("HYD FAIL");
        if ((conditions & EmergencyStatus.PRESSURISATION) != 0) parts.add("CABIN ALT");
        if (parts.isEmpty()) parts.add("EMERGENCY");
        return String.join("+", parts);
    }

    private static int emergencyConditions(Session session) {
        int conditions = 0;
        if (engineState(session, 0) == ENGINE_FIRE) conditions |= EmergencyStatus.FIRE_1;
        if (engineState(session, 1) == ENGINE_FIRE) conditions |= EmergencyStatus.FIRE_2;
        if (engineState(session, 0) == ENGINE_FAILED) conditions |= EmergencyStatus.FAILURE_1;
        if (engineState(session, 1) == ENGINE_FAILED) conditions |= EmergencyStatus.FAILURE_2;
        if (session.fuelPercent <= FlightTuning.FUEL_EMPTY_PERCENT) conditions |= EmergencyStatus.FUEL_EMPTY;
        else if (session.fuelPercent <= FlightTuning.FUEL_LOW_PERCENT) conditions |= EmergencyStatus.FUEL_LOW;
        if (session.gearFailed) conditions |= EmergencyStatus.GEAR_FAILED;
        if (session.hydraulicFailed) conditions |= EmergencyStatus.HYDRAULIC;
        if (session.pressLost) conditions |= EmergencyStatus.PRESSURISATION;
        if (session.icingPercent >= 20.0) conditions |= EmergencyStatus.ICING;
        if (FlightTuning.isOverspeed(session.airspeed, session.aircraftType)) conditions |= EmergencyStatus.OVERSPEED;
        if (session.windShearTicks > 0) conditions |= EmergencyStatus.WINDSHEAR;
        boolean landingEmergency = (conditions & (EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2
                | EmergencyStatus.FAILURE_1 | EmergencyStatus.FAILURE_2 | EmergencyStatus.FUEL_EMPTY
                | EmergencyStatus.GEAR_FAILED | EmergencyStatus.HYDRAULIC
                | EmergencyStatus.PRESSURISATION)) != 0;
        if (landingEmergency && session.fuelPercent >= 60.0) conditions |= EmergencyStatus.HEAVY_FUEL;
        if (session.emergencyDeclared) conditions |= EmergencyStatus.EMERGENCY_DECLARED;
        if (session.fuelDumping) conditions |= EmergencyStatus.FUEL_DUMPING;
        if (session.emergencyDescent) conditions |= EmergencyStatus.EMERGENCY_DESCENT;
        return conditions;
    }

    private static int emergencyAvailability(ServerPlayer player, Session session, int conditions) {
        int available = 0;
        for (int i = 0; i < 2; i++) {
            if (engineState(session, i) == ENGINE_FIRE && session.extinguisherBottles > 0
                    && FlightTuning.canSuppressFire(session.engineFireTicks[i])) {
                available |= i == 0 ? EmergencyStatus.CAN_EXTINGUISH_1 : EmergencyStatus.CAN_EXTINGUISH_2;
            }
            int state = engineState(session, i);
            boolean failedState = state == ENGINE_FAILED || state == ENGINE_OFF;
            if (FlightTuning.canRestartEngine(session.airborne, failedState, session.fuelPercent,
                    session.engineRestartAttempts[i], session.airspeed, stallSpeed(session),
                    session.engineTemperatures[i])) {
                available |= i == 0 ? EmergencyStatus.CAN_RESTART_1 : EmergencyStatus.CAN_RESTART_2;
            }
        }
        if (session.gearFailed && !session.alternateGearExtended) {
            available |= EmergencyStatus.CAN_ALTERNATE_GEAR;
        }
        if (session.hydraulicFailed && !session.hydraulicBackupUsed) {
            available |= EmergencyStatus.CAN_RESET_HYDRAULICS;
        }
        if (session.pressLost && session.airborne && !session.emergencyDescent) {
            available |= EmergencyStatus.CAN_EMERGENCY_DESCENT;
        }
        if (session.fuelDumping) {
            available |= EmergencyStatus.CAN_STOP_DUMP;
        } else if (session.airborne
                && session.fuelPercent > FlightTuning.FUEL_JETTISON_RESERVE_PERCENT + 1.0) {
            available |= EmergencyStatus.CAN_DUMP_FUEL;
        }
        int distress = conditions & (EmergencyStatus.FIRE_1 | EmergencyStatus.FIRE_2
                | EmergencyStatus.FAILURE_1 | EmergencyStatus.FAILURE_2 | EmergencyStatus.FUEL_LOW
                | EmergencyStatus.FUEL_EMPTY | EmergencyStatus.GEAR_FAILED | EmergencyStatus.HYDRAULIC
                | EmergencyStatus.PRESSURISATION | EmergencyStatus.ICING | EmergencyStatus.OVERSPEED
                | EmergencyStatus.WINDSHEAR);
        if (distress != 0) {
            if (session.emergencyDeclared) {
                available |= EmergencyStatus.CAN_CANCEL_MAYDAY;
            } else {
                available |= EmergencyStatus.CAN_MAYDAY;
            }
        }
        if (canEmergencyAutoland(player, session)) {
            available |= EmergencyStatus.CAN_AUTOLAND;
        }
        return available;
    }

    private static EmergencyStatusPayload buildEmergencyStatus(ServerPlayer player, @Nullable Session session) {
        if (session == null) return EmergencyStatusPayload.inactive();
        int conditions = emergencyConditions(session);
        int available = emergencyAvailability(player, session, conditions);
        EmergencyStatus.Recommendation recommendation = EmergencyStatus.recommend(conditions, available);
        return new EmergencyStatusPayload(conditions, available, recommendation.action(),
                recommendation.target(), Math.max(0, session.engineFireTicks[0]),
                Math.max(0, session.engineFireTicks[1]), Math.max(0, session.engineHotTicks[0]),
                Math.max(0, session.engineHotTicks[1]), session.extinguisherBottles,
                session.engineRestartAttempts[0], session.engineRestartAttempts[1], session.fuelDumpTicks);
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
                if (session.atcClearance != null) releaseAtcControl(player, session, "pilot unavailable");
                remove(player.getUUID(), player);
                sendState(player, null, false);
                continue;
            }

            updateAtcControl(player, session, server);
            simulate(player, session, server);
            if (session.flightPlan != null
                    && player.level().getGameTime() - session.lastNavSyncTick >= STATUS_RESYNC_TICKS) {
                FlightPlanService.refresh(player, session.flightPlan);
                session.lastNavSyncTick = player.level().getGameTime();
            }
            sendState(player, session, true);
        }
        FORCED_WINDSHEARS.forEach((dimension, forced) -> {
            int remaining = forced.remainingTicks() - 1;
            if (remaining > 0) {
                FORCED_WINDSHEARS.put(dimension, new ForcedWindshear(remaining, forced.severity()));
            } else {
                FORCED_WINDSHEARS.remove(dimension, forced);
            }
        });
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
            persistIls(player, session);
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
        updateEngineTemperatures(player, session);
        updateEngineFires(player, session);
        updateFuel(player, session);
        session.brakesActive = !session.airborne && ((session.controls & FlightControls.BRAKE) != 0
                || session.autolandRolloutTicks > 0);
        if (!session.airborne && session.autolandRolloutTicks > 0) session.autolandRolloutTicks--;
        boolean onGround = player.onGround();

        // A runway contact can be reported as a vertical collision or low-altitude horizontal collision
        // before or together with vanilla onGround. Treat surface contact as a touchdown to be assessed.
        // Horizontal collisions only count as runway contact below 20 ft RA to avoid triggering
        // a crash when the player clips sloped terrain during a normal approach.
        boolean runwayContact = session.airborne && session.takeoffGraceTicks == 0
                && (onGround || player.verticalCollision
                || (player.horizontalCollision && session.radioAltitudeFeet <= 20));
        if (runwayContact) {
            handleFallImpact(player, player.fallDistance);
            if (session.crashed) {
                player.setDeltaMovement(Vec3.ZERO);
                updateInstruments(player, session, server);
                return;
            }
        } else if (session.airborne && !onGround
                && (player.horizontalCollision || player.verticalCollision)) {
            crashAircraft(player, session, session.airVelocity.length());
            updateInstruments(player, session, server);
            return;
        }
        if (!session.airborne && player.horizontalCollision
                && session.groundSpeed > 0.90 * session.aircraftType.maxAirspeedBlocksPerTick()) {
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
                commandedPitch = Math.max(FlightTuning.MAX_NOSE_DOWN_PITCH_DEGREES,
                        Math.min(FlightTuning.MAX_NOSE_UP_PITCH_DEGREES, -player.getXRot()));
            }
            double dynamicAuthority = Math.max(0.25, Math.min(1.2, session.airspeed / FlightTuning.ROTATION_SPEED));
            session.pitchDegrees = approach(session.pitchDegrees, commandedPitch, 0.45 * dynamicAuthority);
        } else {
            session.pitchDegrees = 0.0;
        }

        if (!session.airborne && !onGround && session.groundSpeed < FlightTuning.ROTATION_SPEED) {
            // A jump or walking off an edge is not a takeoff. Let normal gravity recover the player.
            player.setNoGravity(false);
            session.groundSpeed = player.getDeltaMovement().horizontalDistance();
        }

        if (session.airborne) {
            if (session.takeoffGraceTicks > 0) session.takeoffGraceTicks--;
            if (session.takeoffWarningTicks > 0) session.takeoffWarningTicks--;
            if (session.autopilot && !session.autoland && session.atcClearance == null) {
                updateNavigation(player, session);
            }
            if (session.autoland) updateAutolandTargets(player, session);
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
        updateOverspeedStress(player, session);
        updateGearHeat(session);
        updateHydraulicsAndPressurisation(player, session);
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
        if (session.fuelDumping && session.fuelPercent <= FlightTuning.FUEL_JETTISON_RESERVE_PERCENT) {
            session.fuelDumping = false;
            session.fuelDumpTicks = 0;
            notify(player, "Fuel jettison complete. Landing reserve protected.");
        }
        if (session.fuelPercent <= 0.0) {
            session.fuelPercent = 0.0;
            boolean flameout = false;
            for (int i = 0; i < 2; i++) {
                int state = engineState(session, i);
                if (state == ENGINE_RUNNING || state == ENGINE_STARTING) {
                    setEngineState(session, i, ENGINE_OFF);
                    session.engineStartTicks[i] = 0;
                    flameout = true;
                }
            }
            if (flameout) {
                session.throttle = 0.0;
                session.engineSpool = 0.0;
                notify(player, "FUEL EXHAUSTED - engines flamed out. Glide and land.");
            }
            return;
        }
        int engines = runningEngines(session);
        if (engines > 0) {
            double burn = FlightTuning.FUEL_DRAIN_PER_ENGINE_PER_TICK * engines
                    * (0.18 + session.throttle * 0.82)
                    * session.aircraftType.fuelConsumptionRate();
            session.fuelPercent = Math.max(0.0, session.fuelPercent - burn);
        }
        if (session.fuelDumping) {
            session.fuelPercent = FlightTuning.fuelAfterJettison(session.fuelPercent);
            session.fuelDumpTicks++;
        }
    }

    private static void updateEngineStart(Session session) {
        for (int i = 0; i < 2; i++) {
            if (engineState(session, i) != ENGINE_STARTING) continue;
            if (++session.engineStartTicks[i] >= ENGINE_START_TICKS) {
                setEngineState(session, i, ENGINE_RUNNING);
                session.engineStartTicks[i] = 0;
                session.engineTemperatures[i] = Math.max(session.engineTemperatures[i], 420.0);
            }
        }
    }

    private static int runningEngines(Session session) {
        if (session.aircraftType == AircraftType.GINA_787) {
            boolean running = session.engine1State == ENGINE_RUNNING && session.engine2State == ENGINE_RUNNING;
            if (running) return 6;
            if (session.engine1State == ENGINE_RUNNING || session.engine2State == ENGINE_RUNNING) return 3;
            return 0;
        }
        return (session.engine1State == ENGINE_RUNNING ? 1 : 0)
                + (session.engine2State == ENGINE_RUNNING ? 1 : 0);
    }

    private static int engineState(Session session, int index) {
        return index == 0 ? session.engine1State : session.engine2State;
    }

    private static void setEngineState(Session session, int index, int state) {
        if (index == 0) {
            session.engine1State = state;
        } else {
            session.engine2State = state;
        }
    }

    private static String engineLabel(int index) {
        return index == 0 ? "ENGINE 1" : "ENGINE 2";
    }

    private static String engineLabel(Session session, int index) {
        return session.aircraftType == AircraftType.GINA_787
                ? "ENGINE GROUP " + (index + 1)
                : engineLabel(index);
    }

    /** Short checklist feedback shown as a cockpit toast without filling chat. */
    private static void notify(ServerPlayer player, String message) {
        String safe = message.length() > 200 ? message.substring(0, 200) : message;
        ServerPlayNetworking.send(player, new FlightNoticePayload(safe));
    }

    private static void updateGroundHeading(ServerPlayer player, Session session) {
        int controls = session.controls;
        double targetRate = 0.0;
        boolean left = (controls & FlightControls.LEFT) != 0;
        boolean right = (controls & FlightControls.RIGHT) != 0;
        if (left != right) {
            double authority = Math.max(0.12,
                    1.0 - session.groundSpeed / session.aircraftType.maxAirspeedBlocksPerTick());
            targetRate = (left ? -1.0 : 1.0) * 0.45 * authority;
        }
        session.groundTurnRate = approach(session.groundTurnRate, targetRate, 0.035);
        session.headingDegrees = wrapDegrees(session.headingDegrees + session.groundTurnRate);
        applyHeading(player, session);
    }

    private static void updateBankAndTurn(ServerPlayer player, Session session) {
        int controls = session.controls;
        double targetBank = bankTargetFromControls(controls, session.autopilot);
        if (session.autopilot) {
            double headingError = wrapDegrees(session.autopilotHeading - session.headingDegrees);
            targetBank = Math.max(-25.0, Math.min(25.0, headingError * 0.75));
        }
        long turbulenceTick = player.level().getGameTime() + player.getUUID().getLeastSignificantBits();
        if (session.turbulencePercent > 0 || session.gustKnots > 0) {
            double gustFraction = Math.min(1.0, session.gustKnots / 60.0);
            targetBank += Math.sin(turbulenceTick * 0.19)
                    * (session.turbulencePercent * 0.030 + gustFraction * 5.0)
                    * session.aircraftType.turbulenceSensitivity();
        }
        if (session.windShearTicks > 0) {
            // A microburst rocks the wings, but it must never roll the aircraft onto its side.
            targetBank += Math.sin(turbulenceTick * 0.23) * 9.0 * session.windShearSeverity;
        }
        targetBank = Math.max(-FlightTuning.MAX_BANK_DEGREES,
                Math.min(FlightTuning.MAX_BANK_DEGREES, targetBank));
        if (session.engine1State >= ENGINE_FAILED && session.engine2State == ENGINE_RUNNING) {
            targetBank += 4.5 * session.throttle;
        } else if (session.engine2State >= ENGINE_FAILED && session.engine1State == ENGINE_RUNNING) {
            targetBank -= 4.5 * session.throttle;
        }

        boolean pilotTurning = !session.autopilot
                && (controls & (FlightControls.LEFT | FlightControls.RIGHT)) != 0;
        double bankResponse = pilotTurning || session.autopilot
                ? FlightTuning.BANK_RESPONSE_DEGREES_PER_TICK
                : FlightTuning.BANK_RELEASE_RESPONSE_DEGREES_PER_TICK;
        session.bankDegrees = approach(session.bankDegrees, targetBank, bankResponse);
        // Exceeding the bank limit is an aerodynamic penalty, not an instant death sentence.
        // The aircraft only crashes when it actually strikes terrain, traffic or an obstacle.
        double speed = Math.max(0.75, session.airspeed);
        // Turn rate: omega = g * tan(phi) / v
        double turnRate = FlightMath.coordinatedTurnRateRadiansPerTick(session.bankDegrees, speed);
        if (session.engine1State >= ENGINE_FAILED && session.engine2State == ENGINE_RUNNING) {
            turnRate += 0.28 * session.throttle;
        } else if (session.engine2State >= ENGINE_FAILED && session.engine1State == ENGINE_RUNNING) {
            turnRate -= 0.28 * session.throttle;
        }
        session.headingDegrees = wrapDegrees(session.headingDegrees + turnRate);

        // Aerodynamic overbanking / nose drop: in a bank, the aircraft naturally drops its nose unless pilot pulls back.
        // Beyond the critical bank angle the wing loses more vertical lift, which is dramatic but survivable.
        if (!session.autopilot && Math.abs(session.bankDegrees) > 2.0) {
            session.pitchDegrees -= Math.abs(session.bankDegrees) * 0.015;
            if (isOverbank(session.bankDegrees)) {
                session.pitchDegrees -= 0.10;
            }
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

    static double bankTargetFromControls(int controls, boolean autopilot) {
        if (autopilot) return 0.0;
        boolean left = (controls & FlightControls.LEFT) != 0;
        boolean right = (controls & FlightControls.RIGHT) != 0;
        if (left == right) return 0.0;
        return left ? -FlightTuning.MAX_BANK_DEGREES : FlightTuning.MAX_BANK_DEGREES;
    }

    private static boolean handoffToApproach(ServerPlayer player, Session session, FlightPlan plan,
                                             NavDataPayload.Runway runway) {
        if (!tuneApproach(player, runway)) return false;
        plan.setStatus(FlightPlan.Status.ARRIVAL);
        FlightPlanService.onPlanChanged(player, plan);
        return true;
    }

    private static void updateNavigation(ServerPlayer player, Session session) {
        FlightPlan plan = session.flightPlan;
        if (plan == null || session.navMode == NavMode.HDG) return;
        MinecraftServer server = player.level().getServer();
        NavDatabase database = NavDatabase.get(server);

        if (session.navMode == NavMode.ILS) {
            NavDataPayload.Runway runway = NavGuidance.approachRunway(database, plan);
            if (runway == null) {
                session.navMode = NavMode.HDG;
                return;
            }
            AutolandTargets targets = calculateAutolandTargets(player.position(),
                    new Vec3(runway.thresholdX() + 0.5, session.ilsThreshold.y,
                            runway.thresholdZ() + 0.5),
                    runway.headingDegrees(), runway.glideSlopeDegrees());
            double bearing = targets != null ? targets.targetHeadingDegrees()
                    : NavGuidance.bearingDegrees(player.getX(), player.getZ(),
                            runway.thresholdX(), runway.thresholdZ());
            session.autopilotHeading = NavGuidance.wrapDegrees(bearing);
            return;
        }

        var leg = plan.activeLeg(database);
        if (leg == null) {
            NavDataPayload.Runway runway = NavGuidance.approachRunway(database, plan);
            if (runway != null && handoffToApproach(player, session, plan, runway)) {
                notify(player, "Route complete. ILS runway " + runway.name() + " acquired.");
            } else {
                session.navMode = NavMode.HDG;
                notify(player, "Route complete. Resuming heading control.");
            }
            return;
        }
        if (NavGuidance.shouldSequence(player.getX(), player.getZ(), leg.x(), leg.z(),
                leg.id() == plan.destinationId())) {
            plan.advanceLeg();
            FlightPlanService.onPlanChanged(player, plan);
            var next = plan.activeLeg(database);
            if (next != null) {
                notify(player, "LNAV: next waypoint " + next.name() + ".");
            } else {
                NavDataPayload.Runway runway = NavGuidance.approachRunway(database, plan);
                if (runway != null && handoffToApproach(player, session, plan, runway)) {
                    notify(player, "Route complete. ILS runway " + runway.name() + " acquired.");
                } else {
                    session.navMode = NavMode.HDG;
                    notify(player, "Route complete. Resuming heading control.");
                }
            }
            return;
        }
        session.autopilotHeading = NavGuidance.bearingDegrees(player.getX(), player.getZ(),
                leg.x(), leg.z());
    }

    private static void updateAutolandTargets(ServerPlayer player, Session session) {
        if (session.ilsDimension == null || !session.ilsDimension.equals(player.level().dimension())) {
            disengageAutoland(player, session);
            return;
        }
        AutolandTargets targets = calculateAutolandTargets(player.position(), session.ilsThreshold,
                session.ilsRunwayHeading, session.ilsGlideSlopeDegrees);
        if (targets == null) {
            disengageAutoland(player, session);
            return;
        }
        session.autopilotAltitudeFeet = targets.targetAltitudeFeet();
        session.autopilotHeading = targets.targetHeadingDegrees();
        session.autopilotAirspeedKnots = targets.targetAirspeedKnots();
        session.autopilotVerticalSpeedFpm = targets.targetVerticalSpeedFpm();
    }

    private static void clearAutoland(ServerPlayer player, Session session, boolean stopAutopilot) {
        if (!session.autoland) return;
        session.autoland = false;
        if (stopAutopilot) session.autopilot = false;
        persistIls(player, session);
    }

    private static void disengageAutoland(ServerPlayer player, Session session) {
        clearAutoland(player, session, true);
    }

    static @Nullable AutolandTargets calculateAutolandTargets(Vec3 position, Vec3 threshold,
                                                               float runwayHeading, float glideSlopeDegrees) {
        Vec3 fromThreshold = position.subtract(threshold);
        double alongTrack = fromThreshold.dot(headingVector(runwayHeading + 180.0));
        Vec3 lateralDirection = new Vec3(Math.cos(Math.toRadians(runwayHeading)), 0.0,
                Math.sin(Math.toRadians(runwayHeading)));
        double lateralOffset = fromThreshold.dot(lateralDirection);
        if (alongTrack < -20.0 || alongTrack > 5000.0 || Math.abs(lateralOffset) > 500.0) return null;

        double glideSlopeRadians = Math.toRadians(glideSlopeDegrees);
        double targetAltitudeFeet = (threshold.y + alongTrack * Math.tan(glideSlopeRadians)) * 3.28084;
        double localizerDegrees = Math.toDegrees(Math.atan2(lateralOffset, Math.max(1.0, alongTrack)));
        double localizerCorrection = Math.max(-35.0, Math.min(35.0, localizerDegrees * 2.0));
        int targetAirspeedKnots = 130;
        int targetVerticalSpeedFpm = -(int) Math.max(500.0,
                targetAirspeedKnots * Math.tan(glideSlopeRadians) * 5.3);
        return new AutolandTargets(wrapDegrees(runwayHeading + localizerCorrection),
                Math.max(0, (int) Math.round(targetAltitudeFeet)),
                targetVerticalSpeedFpm, targetAirspeedKnots);
    }

    record AutolandTargets(double targetHeadingDegrees, int targetAltitudeFeet,
                           int targetVerticalSpeedFpm, int targetAirspeedKnots) {
    }

    public static void configureAutopilot(ServerPlayer player, boolean engaged, int targetHeading,
                                          int targetAltitudeFeet, int targetVerticalSpeedFpm, int targetAirspeedKnots) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed || !session.airborne) return;
        if (session.atcClearance != null) releaseAtcControl(player, session, "pilot autopilot input");
        clearAutoland(player, session, !engaged);
        session.autopilot = engaged;
        session.navMode = NavMode.HDG;
        session.autopilotHeading = Math.floorMod(targetHeading, 360);
        session.autopilotAltitudeFeet = Math.max(100, targetAltitudeFeet);
        session.autopilotVerticalSpeedFpm = targetVerticalSpeedFpm;
        session.autopilotAirspeedKnots = targetAirspeedKnots;
        sendState(player, session, true);
    }

    public static void toggleAutopilot(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.crashed || !session.airborne) return;
        if (session.atcClearance != null) releaseAtcControl(player, session, "pilot autopilot input");
        boolean engage = !session.autopilot;
        clearAutoland(player, session, !engage);
        session.autopilot = engage;
        session.navMode = NavMode.HDG;
        if (engage) {
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
            speed = FlightTuning.advanceGroundSpeed(speed, session.engineSpool,
                    runningEngines(session), session.flapPosition, session.aircraftType);
        }
        session.groundSpeed = speed;
        session.airspeed = (float) session.groundSpeed;

        Vec3 heading = headingVector(session.headingDegrees);
        Vec3 previous = player.getDeltaMovement();
        double direction = session.pushback && session.pushbackReversing ? -1.0 : 1.0;
        player.setDeltaMovement(heading.scale(session.groundSpeed * direction).add(0.0, previous.y, 0.0));

        double pitchUp = -player.getXRot();
        if (!session.pushback && !session.brakesActive && runningEngines(session) > 0
                && session.groundSpeed >= session.aircraftType.rotationSpeedBlocksPerTick()
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
        boolean powered = runningEngines(session) > 0;
        // Authoritative aircraft 3D attitude vector derived from aircraft pitch and heading
        Vec3 aircraftAttitude = FlightMath.aircraftAttitudeVector(session.pitchDegrees, session.headingDegrees);

        Vec3 velocity = session.airVelocity;
        Vec3 wind = new Vec3(session.windX, session.windY, session.windZ);
        double turbSens = session.aircraftType.turbulenceSensitivity();
        double turbulence = (session.turbulencePercent / 100.0) * turbSens;
        double gust = Math.min(1.0, session.gustKnots / 60.0);
        double atmospheric = turbulence + gust * 0.45;
        long turbulenceTick = player.level().getGameTime() + player.getUUID().getLeastSignificantBits();
        velocity = velocity.add(
                Math.sin(turbulenceTick * 0.31) * atmospheric * 0.006,
                Math.sin(turbulenceTick * 0.47 + 1.2) * atmospheric * 0.010,
                Math.cos(turbulenceTick * 0.29 + 0.6) * atmospheric * 0.006);
        if (session.windShearTicks > 0) {
            // Microburst: a short, sharp downdraft plus a lateral shear and a performance loss.
            // The shear is deliberately bounded so it cannot roll the aircraft onto its side.
            double shear = session.windShearSeverity;
            velocity = velocity.add(session.windX * 0.020 * shear,
                    -0.008 * FlightTuning.WORLD_SPEED_SCALE * shear,
                    session.windZ * 0.020 * shear);
            velocity = velocity.subtract(headingVector(session.headingDegrees)
                    .scale(0.004 * shear));
        }
        Vec3 relativeAir = velocity.subtract(wind);
        double airspeed = relativeAir.length();

        // Ground effect cushions aircraft flare within 12 blocks (~40 ft) of the surface
        double radioAltBlocks = session.radioAltitudeFeet / 3.28084;
        double groundEffect = FlightMath.groundEffectFactor(radioAltBlocks);
        double baseDrag = ((session.gearDown ? 0.0012 : 0.00045) + session.flapPosition * 0.00055
                + (session.spoilers ? 0.0045 : 0.0)
                + (session.airbrakes ? FlightTuning.AIRBRAKE_DRAG : 0.0))
                / FlightTuning.WORLD_SPEED_SCALE;

        // Turn induced drag from aerodynamic load factor (g-load = 1 / cos(bank)):
        // Steep turns bleed airspeed unless throttle is added!
        double cosBank = Math.max(0.15, Math.cos(Math.toRadians(session.bankDegrees)));
        double loadFactor = 1.0 / cosBank;
        double turnInducedDrag = (loadFactor * loadFactor - 1.0) * 0.0016 / FlightTuning.WORLD_SPEED_SCALE;
        double drag = (baseDrag * FlightMath.groundEffectDragMultiplier(groundEffect)) + turnInducedDrag;
        drag *= FlightTuning.engineOutDragFactor(powered);
        velocity = velocity.subtract(relativeAir.scale(drag * Math.max(0.15, airspeed)));

        // Thrust aligns strictly with the aircraft's physical attitude (decoupled from raw camera look)
        double engineFactor = FlightTuning.thrustFactor(session.aircraftType, runningEngines(session));
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

        Vec3 airVelocity = velocity.subtract(wind);
        double horizontalSpeed = airVelocity.horizontalDistance();
        double indicatedAirspeed = airVelocity.length();
        double pitchUp = session.pitchDegrees;
        double angleOfAttack = FlightMath.angleOfAttackDegrees(pitchUp, velocity.y, horizontalSpeed);
        double stallSpeed = FlightTuning.engineOutStallSpeed(
                FlightTuning.icedStallSpeed(session.icingPercent, session.flapPosition), powered);
        boolean stalled = indicatedAirspeed < stallSpeed
                || angleOfAttack > FlightTuning.CRITICAL_ANGLE_OF_ATTACK_DEGREES || pitchUp >= 50.0;

        if (!powered && !stalled) {
            // Glide assist: if the aircraft is slow, allow it to trade height for airspeed along
            // its current flight path instead of bleeding straight into a stall.
            double glideSpeed = FlightTuning.engineOutGlideSpeed(stallSpeed);
            double glideAcceleration = FlightTuning.engineOutGlideAcceleration(
                    indicatedAirspeed, glideSpeed, false);
            if (glideAcceleration > 0.0 && airVelocity.lengthSqr() > 1.0e-8) {
                velocity = velocity.add(airVelocity.normalize().scale(glideAcceleration));
            }
            if (indicatedAirspeed < glideSpeed) {
                session.pitchDegrees = approach(session.pitchDegrees,
                        FlightTuning.ENGINE_OUT_GLIDE_PITCH_DEGREES, 0.08);
            }
        }

        if (stalled) {
            // Aerodynamic stall recovery: nose naturally pitches down to regain airflow
            session.pitchDegrees = approach(session.pitchDegrees, -4.0, 0.5);
        }

        double effectiveAngle = Math.max(0.0, Math.min(20.0, angleOfAttack + 6.0))
                + session.flapPosition * 2.4;
        double lift = stalled ? 0.0
                : Math.min(0.24 * FlightTuning.WORLD_SPEED_SCALE,
                indicatedAirspeed * indicatedAirspeed * 0.0011 / FlightTuning.WORLD_SPEED_SCALE
                        * effectiveAngle * FlightTuning.icingLiftFactor(session.icingPercent) * loadFactor);
        lift *= Math.cos(Math.toRadians(session.bankDegrees));
        lift *= FlightMath.groundEffectLiftMultiplier(groundEffect); // Ground effect lift cushioning
        lift *= FlightTuning.spoilerLiftFactor(session.spoilers);
        lift *= session.airbrakes ? FlightTuning.AIRBRAKE_LIFT_FACTOR : 1.0;
        lift *= FlightTuning.overspeedLiftFactor(session.overspeedTicks);
        if (!stalled && session.takeoffGraceTicks > 0) {
            lift = Math.max(lift, 0.12);
        }
        velocity = velocity.add(0.0, lift - 0.08, 0.0);
        if (session.gearDown && session.radioAltitudeFeet < 60) {
            // Ground-effect flare cushion: in the last ~20 m the wing is close
            // enough to the runway that a normal approach should not arrive at a
            // hard-landing sink rate simply because the pilot was a little late.
            // Clamp to 1.5× the safe touchdown sink speed so a gentle flare is enough.
            double maxSink = -FlightTuning.SAFE_TOUCHDOWN_SINK_SPEED * 1.5;
            if (velocity.y < maxSink) {
                velocity = new Vec3(velocity.x, maxSink, velocity.z);
            }
        }
        if (session.autoland) {
            velocity = new Vec3(velocity.x,
                    autolandFlareVerticalSpeed(velocity.y, session.radioAltitudeFeet), velocity.z);
        }

        double maximumAirspeed = session.aircraftType.maxAirspeedBlocksPerTick();
        if (velocity.lengthSqr() > maximumAirspeed * maximumAirspeed) {
            velocity = velocity.normalize().scale(maximumAirspeed);
        }
        session.airVelocity = velocity;
        player.setDeltaMovement(velocity);
        session.airspeed = (float) indicatedAirspeed;
        setPhase(player, session, stalled ? FlightPhase.STALL : FlightPhase.AIRBORNE);
    }

    private static void updateOverspeedStress(ServerPlayer player, Session session) {
        session.overspeedDamageCooldown = Math.max(0, session.overspeedDamageCooldown - 1);
        session.overspeedTicks = FlightTuning.advanceOverspeedStressTicks(
                session.overspeedTicks, session.airborne
                        && FlightTuning.isOverspeed(session.airspeed, session.aircraftType));
        if (FlightTuning.shouldApplyOverspeedDamage(session.overspeedTicks,
                session.overspeedDamageCooldown) && player.level() instanceof ServerLevel level) {
            player.hurtServer(level, player.damageSources().generic(), 1.0f);
            session.overspeedDamageCooldown = FlightTuning.OVERSPEED_DAMAGE_INTERVAL_TICKS;
        }
    }

    private static void updateFlaps(Session session) {
        double target = session.flapDetent * 0.25;
        session.flapPosition = approach(session.flapPosition, target, 0.008);
    }

    private static void updateWeather(ServerPlayer player, Session session) {
        long gameTime = player.level().getGameTime();
        long seed = player.getUUID().getLeastSignificantBits();
        float biomeTemperature = player.level().getBiome(player.blockPosition()).value().getBaseTemperature();
        WeatherSimulation.Conditions conditions = WeatherSimulation.sample(player.level().isRaining(),
                player.level().isThundering(), biomeTemperature, player.blockPosition().getX(),
                player.blockPosition().getZ(), player.getY(), gameTime);
        boolean antiIceEffective = session.antiIce && runningEngines(session) > 0 && session.engineSpool > 0.35;
        session.antiIceEffective = antiIceEffective;
        session.icingPercent = WeatherSimulation.advanceIcing(session.icingPercent,
                conditions.icingConditions() && session.airborne, antiIceEffective);
        session.windX = approach(session.windX, conditions.windX(), 0.008);
        session.windZ = approach(session.windZ, conditions.windZ(), 0.008);
        session.windY = 0.0;
        session.windSpeedKnots = (int) Math.round(Math.hypot(session.windX, session.windZ)
                * FlightTuning.KNOTS_PER_BLOCK_PER_TICK);
        session.windDirectionSector = Math.floorMod(
                (int) Math.round(conditions.windDirectionDegrees() / 5.625), 64);
        session.turbulencePercent = (int) Math.round(approach(session.turbulencePercent,
                conditions.turbulencePercent(), 2.0));
        session.gustKnots = (int) Math.round(approach(session.gustKnots, conditions.gustKnots(), 1.2));
        session.visibilityBlocks = conditions.visibilityBlocks();
        session.weatherCondition = conditions.condition();

        if (session.windShearTicks > 0) {
            session.windShearTicks--;
            // Keep the microburst turbulent while it is active, then let it decay naturally.
            session.turbulencePercent = Math.max(session.turbulencePercent,
                    70 + (int) Math.round(22.0 * session.windShearSeverity));
            if (session.windShearTicks == 0) session.windShearSeverity = 0.0;
        }
        ForcedWindshear forced = FORCED_WINDSHEARS.get(player.level().dimension());
        if (WeatherSimulation.shouldStartWindshear(player.level().isThundering(), gameTime, seed,
                forced != null)) {
            session.windShearTicks = 140;
            session.windShearSeverity = forced != null && forced.severity() != null
                    ? forced.severity() : WeatherSimulation.windshearSeverity(conditions.gustKnots());
            session.turbulencePercent = Math.max(session.turbulencePercent, 88);
            session.gustKnots = Math.max(session.gustKnots, conditions.gustKnots() + 15);
        }
    }

    private static void updateEngineTemperatures(ServerPlayer player, Session session) {
        boolean highPower = session.engineSpool > 0.88;
        for (int i = 0; i < 2; i++) {
            int state = engineState(session, i);
            double target = switch (state) {
                case ENGINE_FIRE -> 1150.0;
                case ENGINE_RUNNING -> highPower ? 950.0 : 420.0;
                case ENGINE_STARTING -> 560.0;
                default -> 20.0;
            };
            double rate = state == ENGINE_FIRE ? 8.0
                    : state == ENGINE_RUNNING && highPower ? 1.8 : 0.25;
            session.engineTemperatures[i] = approach(session.engineTemperatures[i], target, rate);
            if (state == ENGINE_RUNNING && session.engineTemperatures[i] > FlightTuning.ENGINE_HOT_TEMPERATURE) {
                session.engineHotTicks[i]++;
            } else {
                session.engineHotTicks[i] = Math.max(0, session.engineHotTicks[i] - 2);
            }
            if (session.engineHotTicks[i] > FlightTuning.ENGINE_HOT_TO_FIRE_TICKS) {
                setEngineState(session, i, ENGINE_FIRE);
                session.engineHotTicks[i] = 0;
                session.engineFireTicks[i] = 0;
                session.engineFireDamageCooldown[i] = 0;
                playFireBurst(player);
                notify(player, engineLabel(session, i) + " FIRE - discharge extinguisher or land immediately.");
            }
        }
    }

    /** Burns and damages an engine fire until it is extinguished or the aircraft lands. */
    private static void updateEngineFires(ServerPlayer player, Session session) {
        for (int i = 0; i < 2; i++) {
            if (engineState(session, i) != ENGINE_FIRE) {
                session.engineFireTicks[i] = 0;
                session.engineFireDamageCooldown[i] = 0;
                continue;
            }
            session.engineFireTicks[i]++;
            int ticks = session.engineFireTicks[i];
            if (ticks % FIRE_PARTICLE_INTERVAL_TICKS == 0 && player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, player.getX(), player.getY() + 0.4, player.getZ(),
                        3, 0.2, 0.2, 0.2, 0.02);
                level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 0.4, player.getZ(),
                        2, 0.15, 0.15, 0.15, 0.02);
            }
            if (ticks >= FlightTuning.ENGINE_FIRE_DAMAGE_DELAY_TICKS
                    && session.engineFireDamageCooldown[i] <= 0) {
                player.hurtServer(player.level(), player.damageSources().generic(), 1.0f);
                session.engineFireDamageCooldown[i] = FlightTuning.ENGINE_FIRE_DAMAGE_INTERVAL_TICKS;
            }
            session.engineFireDamageCooldown[i] = Math.max(0, session.engineFireDamageCooldown[i] - 1);
            // A fire stays confined to its own engine/group. The other engine can still be
            // affected by its own overheating, but one failure never flames out the whole aircraft.
            if (ticks > FlightTuning.ENGINE_FIRE_CATASTROPHIC_TICKS) {
                crashAircraft(player, session, 4.0);
                return;
            }
        }
    }

    private static void playFireBurst(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(),
                    SoundSource.PLAYERS, 3.0f, 0.85f);
            level.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY() + 0.6, player.getZ(),
                    3, 0.5, 0.5, 0.5, 0.05);
            level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 0.6, player.getZ(),
                    32, 0.5, 0.5, 0.5, 0.12);
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

    private static void updateHydraulicsAndPressurisation(ServerPlayer player, Session session) {
        // Hydraulic failure: gear cycling at high speed while already hot causes gradual failure
        if (!session.hydraulicFailed) {
            boolean stressedGearCycle = session.airborne && session.gearDown
                    && session.airspeed > 2.0f * FlightTuning.WORLD_SPEED_SCALE
                    && session.gearHeatTicks > 200;
            if (stressedGearCycle) {
                session.hydraulicFailTicks++;
                if (session.hydraulicFailTicks > 600) {
                    session.hydraulicFailed = true;
                    // Hydraulic failure locks flaps at current detent and stiffens controls
                    session.flapDetent = Math.min(session.flapDetent, 2);
                }
            } else {
                session.hydraulicFailTicks = Math.max(0, session.hydraulicFailTicks - 1);
            }
        }

        // Pressurisation: cabin altitude climbs when above 15000 ft and engines are off or failed
        int aircraftAltFeet = (int) Math.round(player.getY() * 3.28084);
        boolean enginesRunning = runningEngines(session) > 0;
        if (session.airborne && aircraftAltFeet > 15000 && !enginesRunning) {
            // Cabin altitude climbs ~300 fpm without engine bleed air
            session.cabinAltitudeFeet = Math.min(aircraftAltFeet,
                    session.cabinAltitudeFeet + 5);
            if (session.cabinAltitudeFeet > 10000) {
                session.pressLost = true;
            }
        } else {
            // Bleed air keeps cabin pressurised
            session.cabinAltitudeFeet = Math.max(0, session.cabinAltitudeFeet - 10);
            if (session.cabinAltitudeFeet < 8000) {
                session.pressLost = false;
            }
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
        // Gear-down flight is treated as landing configuration: normal approach sink rates must
        // not trigger Mode 1/2 GPWS just because the runway is rising toward the aircraft.
        boolean landingConfiguration = session.gearDown;
        if (session.phase == FlightPhase.STALL) warnings |= FlightWarning.STALL;
        if (shouldSinkRateWarning(session.airborne, radioAltitude, verticalSpeed, landingConfiguration)) {
            warnings |= FlightWarning.SINK_RATE;
        }
        if (session.airborne && session.takeoffWarningTicks > 0 && radioAltitude < 200
                && verticalSpeed < -300) warnings |= FlightWarning.DONT_SINK;
        boolean descendingTerrainAhead = false;
        if (session.windShearTicks > 0) warnings |= FlightWarning.WINDSHEAR;
        if (session.icingPercent >= 20.0) warnings |= FlightWarning.ICE_ACCUMULATION;
        if (session.fuelPercent <= FlightTuning.FUEL_EMPTY_PERCENT) warnings |= FlightWarning.FUEL_EMPTY;
        else if (session.fuelPercent <= FlightTuning.FUEL_LOW_PERCENT) warnings |= FlightWarning.FUEL_LOW;
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
        if (session.airborne && !landingConfiguration && radioAltitude < 250 && verticalSpeed < -500) {
            warnings |= FlightWarning.TOO_LOW_TERRAIN;
        }

        // Mode 2 GPWS: Look-ahead terrain closure rate (rising mountains or hills ahead).
        // Flat ground ahead of a normal approach is not a terrain threat; only terrain that
        // genuinely rises into the flight path should command a pull-up.
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
            descendingTerrainAhead = isDescendingTerrainThreat(session.airborne, session.airVelocity.y,
                    projectedClearanceFeet, risingTerrainFeet, radioAltitude);
            if (descendingTerrainAhead) {
                warnings |= FlightWarning.PULL_UP;
            } else if (risingTerrainFeet > 40 && radioAltitude < 600) {
                warnings |= FlightWarning.TERRAIN;
            }
        }
        if (shouldPullUpWarning(session.airborne, radioAltitude, verticalSpeed,
                descendingTerrainAhead, landingConfiguration)) {
            warnings |= FlightWarning.PULL_UP;
        }

        if (!session.airborne && session.throttle > 0.65
                && (session.flapDetent == 0 || session.spoilers || session.brakesActive)) {
            warnings |= FlightWarning.TAKEOFF_CONFIG;
        }
        if (session.airborne && FlightTuning.isOverspeed(session.airspeed, session.aircraftType)) {
            warnings |= FlightWarning.OVERSPEED;
        }
        if (session.overspeedTicks >= FlightTuning.OVERSPEED_STRESS_START_TICKS) {
            warnings |= FlightWarning.AIRFRAME_STRESS;
        }
        if (session.hydraulicFailed) warnings |= FlightWarning.HYDRAULIC_FAIL;
        if (session.pressLost) warnings |= FlightWarning.PRESSURISATION;
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
                double timeToClosest = TcasGeometry.timeToClosestApproachTicks(separation, relativeVelocity);
                if (timeToClosest < 0.0 || timeToClosest > 400.0) continue;
                Vec3 closestSeparation = TcasGeometry.closestSeparation(separation, relativeVelocity);
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
                    resolutionCommand = TcasGeometry.verticalResolutionCommand(separation.y);
                    if (separation.y * relativeVelocity.y < 0.0) resolutionCommand |= FlightWarning.TCAS_CROSSING;
                }
            }
            for (MobFlightController.Traffic traffic : MobFlightController.aircraftIn(player.level())) {
                Vec3 separation = traffic.position().subtract(player.position());
                recordTrafficContact(session, separation, traffic.airborne());
                closestScopeContact = recordScopeContact(session, separation, closestScopeContact);
                Vec3 relativeVelocity = traffic.velocity().subtract(session.airVelocity);
                double timeToClosest = TcasGeometry.timeToClosestApproachTicks(separation, relativeVelocity);
                if (timeToClosest < 0.0 || timeToClosest > 400.0) continue;
                Vec3 closestSeparation = TcasGeometry.closestSeparation(separation, relativeVelocity);
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
                    resolutionCommand = TcasGeometry.verticalResolutionCommand(separation.y);
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
        if (shouldSendRadar(server)) {
            sendFlightRadar(player, session, server);
        }
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

    static boolean shouldSinkRateWarning(boolean airborne, int radioAltitudeFeet, int verticalSpeedFpm,
                                         boolean landingConfiguration) {
        if (!airborne || radioAltitudeFeet > 2450) return false;
        int threshold = FlightTuning.sinkRateWarningThresholdFpm(radioAltitudeFeet);
        if (landingConfiguration) threshold = Math.max(threshold, 1400);
        return verticalSpeedFpm < -threshold;
    }

    static boolean shouldPullUpWarning(boolean airborne, int radioAltitudeFeet, int verticalSpeedFpm,
                                       boolean descendingTerrainAhead, boolean landingConfiguration) {
        if (!airborne) return false;
        if (descendingTerrainAhead) return true;
        // On a gear-down approach the sink-rate envelope is the authority. Pull-up is only
        // commanded for a genuinely hard, uncontrolled descent, never for a normal flare.
        if (landingConfiguration && radioAltitudeFeet <= 1000) return false;
        return radioAltitudeFeet < 180 && verticalSpeedFpm < -1400;
    }

    static boolean isDescendingTerrainThreat(boolean airborne, double verticalSpeed,
                                             double projectedClearanceFeet, int risingTerrainFeet,
                                             int radioAltitudeFeet) {
        return airborne && verticalSpeed < -0.02
                && projectedClearanceFeet < 50.0
                && risingTerrainFeet > 60
                && radioAltitudeFeet > 60;
    }

    static boolean isOverbank(double bankDegrees) {
        return Math.abs(bankDegrees) >= FlightTuning.CRITICAL_BANK_DEGREES;
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
                glideSlopeDegrees, runwayHeading, (float) fromThreshold.horizontalDistance());
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
        long tick = player.level().getGameTime();
        FlightStatePayload state = session == null
                ? new FlightStatePayload(false, FlightPhase.OFF.ordinal(), NavMode.HDG.id(), 0.0f, 0.0f,
                (float) FlightTuning.ROTATION_SPEED, 0.0f, 0.0f, 0.0f)
                : new FlightStatePayload(enabled, session.phase.ordinal(), session.navMode.id(),
                quantize1(session.airspeed), quantize2((float) session.throttle),
                (float) FlightTuning.ROTATION_SPEED,
                quantize2((float) (session.airborne ? session.airVelocity.x : player.getDeltaMovement().x)),
                quantize2((float) (session.airborne ? session.airVelocity.y : player.getDeltaMovement().y)),
                quantize2((float) (session.airborne ? session.airVelocity.z : player.getDeltaMovement().z)));

        FlightInstrumentPayload instruments;
        EmergencyStatusPayload emergency = buildEmergencyStatus(player, session);
        String planeId = (session != null ? session.aircraftType : getAircraftType(player)).id();
        if (session == null) {
            instruments = new FlightInstrumentPayload(0, 0, 0.0f, 0.0f, 0.0f, 0, 0, false, 0,
                    new FlightInstrumentPayload.AirframeStatus(0, false),
                    FlightInstrumentPayload.TrafficGuidance.none(),
                    new FlightInstrumentPayload.CockpitGuidance(
                            FlightInstrumentPayload.IlsGuidance.inactive(),
                            FlightInstrumentPayload.AutopilotGuidance.defaults(),
                            FlightInstrumentPayload.WeatherGuidance.defaults()));
            ServerPlayNetworking.send(player, state);
            ServerPlayNetworking.send(player, instruments);
            ServerPlayNetworking.send(player, emergency);
            ServerPlayNetworking.send(player, new dev.flightline.network.SelectPlanePayload(planeId));
            return;
        }

        FlightInstrumentPayload.IlsGuidance ils = session.ilsGuidance;
        FlightInstrumentPayload.IlsGuidance quantizedIls = new FlightInstrumentPayload.IlsGuidance(
                ils.active(), quantize1(ils.localizerDegrees()), quantize1(ils.glideslopeDegrees()),
                quantize1(ils.referenceGlideSlopeDegrees()), quantize1(ils.runwayHeadingDegrees()),
                quantize1(ils.thresholdDistanceBlocks()));
        int systems = (session.engine1State & 7) | ((session.engine2State & 7) << 3)
                | (session.brakesActive ? 1 << 6 : 0) | (session.pushback ? 1 << 7 : 0)
                | (session.spoilers ? 1 << 8 : 0) | ((session.flapDetent & 7) << 9)
                | (Math.round((float) session.engineSpool * 100.0f) << 12)
                | ((Math.min(127, session.windSpeedKnots) & 0x7F) << 19)
                | ((session.windDirectionSector & 0x3F) << 26);
        int gearState = session.gearFailed ? 2 : (session.gearDown ? 0 : 1);
        int warnings = session.warningFlags | (session.gearFailed ? FlightWarning.GEAR_FAILED : 0);
        boolean atcControlled = session.atcClearance != null;
        instruments = new FlightInstrumentPayload(session.radioAltitudeFeet, session.verticalSpeedFpm,
                quantize1((float) session.bankDegrees), quantize1((float) session.pitchDegrees),
                quantize1((float) session.headingDegrees), gearState, systems,
                session.autopilot, warnings,
                new FlightInstrumentPayload.AirframeStatus((int) Math.round(session.fuelPercent), session.airbrakes),
                new FlightInstrumentPayload.TrafficGuidance(quantize1((float) session.trafficBearingDegrees),
                        session.trafficRangeBlocks, session.trafficAltitudeFeet,
                        (Math.min(255, session.airborneContacts) << 8) | Math.min(255, session.groundContacts)),
                new FlightInstrumentPayload.CockpitGuidance(quantizedIls,
                        new FlightInstrumentPayload.AutopilotGuidance(quantize1((float) session.autopilotHeading),
                        session.autopilotAltitudeFeet, session.autopilotVerticalSpeedFpm,
                        session.autopilotAirspeedKnots, (float) FlightTuning.AUTOTHROTTLE_DEADBAND_KNOTS,
                        session.autoland, atcControlled),
                        new FlightInstrumentPayload.WeatherGuidance(session.windSpeedKnots,
                                session.windDirectionSector * 360 / 64, session.turbulencePercent,
                                session.visibilityBlocks, session.weatherCondition,
                                (int) Math.round(session.icingPercent), session.antiIce, session.antiIceEffective,
                                session.gustKnots)));

        StatusSignature signature = new StatusSignature(session.phase.ordinal(), gearState,
                systems & 0x0FFF, session.autopilot, warnings,
                (int) Math.round(session.fuelPercent), session.airbrakes, atcControlled,
                session.autoland, ils.active(), session.weatherCondition,
                session.antiIce, session.antiIceEffective, emergency.conditions());
        boolean resync = tick - session.lastResyncTick >= STATUS_RESYNC_TICKS;
        boolean discrete = !signature.equals(session.lastStatusSignature);
        boolean stateDue = tick - session.lastStateTick >= STATUS_SEND_INTERVAL_TICKS;
        boolean instrumentsDue = tick - session.lastInstrumentTick >= STATUS_SEND_INTERVAL_TICKS;
        boolean emergencyChanged = !emergency.statusOnly().equals(session.lastEmergencyStatusKey);

        if (state.phase() != session.lastStatePhase || discrete || stateDue || resync) {
            ServerPlayNetworking.send(player, state);
            session.lastFlightState = state;
            session.lastStateTick = tick;
            session.lastStatePhase = state.phase();
        }
        if (discrete || instrumentsDue || resync) {
            ServerPlayNetworking.send(player, instruments);
            session.lastInstruments = instruments;
            session.lastInstrumentTick = tick;
        }
        if (emergencyChanged || tick - session.lastEmergencyTick >= EMERGENCY_STATUS_SEND_INTERVAL_TICKS
                || resync) {
            ServerPlayNetworking.send(player, emergency);
            session.lastEmergencyStatus = emergency;
            session.lastEmergencyStatusKey = emergency.statusOnly();
            session.lastEmergencyTick = tick;
        }
        if (!planeId.equals(session.lastPlaneId) || resync) {
            ServerPlayNetworking.send(player, new dev.flightline.network.SelectPlanePayload(planeId));
            session.lastPlaneId = planeId;
        }
        if (resync) session.lastResyncTick = tick;
        session.lastStatusSignature = signature;
    }

    private static float quantize1(float value) {
        return Math.round(value * 10.0f) / 10.0f;
    }

    private static float quantize2(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }

    private record StatusSignature(int phase, int gearState, int systems, boolean autopilot,
                                   int warnings, int fuelPercent, boolean airbrakes,
                                   boolean atcControlled, boolean autoland, boolean ilsActive,
                                   int weatherCondition, boolean antiIceActive,
                                   boolean antiIceEffective, int emergencyConditions) {
    }

    private static boolean shouldSendRadar(MinecraftServer server) {
        long interval = server.getPlayerList().getPlayers().size() > 48 ? 10 : 5;
        return server.getTickCount() % interval == 0;
    }

    private static void sendFlightRadar(ServerPlayer player, Session session, MinecraftServer server) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long tick = level.getGameTime();
        RadarSnapshot snapshot = RADAR_SNAPSHOTS.get(level.dimension());
        if (snapshot == null || snapshot.tick() != tick) {
            snapshot = buildRadarSnapshot(level, server, tick);
            RADAR_SNAPSHOTS.put(level.dimension(), snapshot);
        }

        Vec3 ownPosition = player.position();
        double scopeBlocks = RADAR_SCOPE_BLOCKS;
        double scopeSquared = scopeBlocks * scopeBlocks;
        List<RadarCandidate> candidates = new ArrayList<>();
        for (FlightRadarPayload.Contact contact : snapshot.contacts()) {
            if (contact.entityId() == player.getId()) continue;
            double dx = contact.x() - ownPosition.x;
            double dz = contact.z() - ownPosition.z;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared <= scopeSquared) candidates.add(new RadarCandidate(distanceSquared, contact));
        }
        candidates.sort(Comparator.comparingDouble(RadarCandidate::distanceSquared));
        List<FlightRadarPayload.Contact> contacts = candidates.stream()
                .limit(MAX_RADAR_CONTACTS).map(RadarCandidate::contact).toList();
        ServerPlayNetworking.send(player, new FlightRadarPayload(player.blockPosition().getX(),
                player.blockPosition().getZ(), (int) Math.round(player.getY() * 3.28084),
                quantize1((float) session.headingDegrees), (int) Math.round(scopeBlocks), contacts));
    }

    /** One authoritative contact list per dimension and radar tick instead of one per player. */
    private static RadarSnapshot buildRadarSnapshot(ServerLevel level, MinecraftServer server, long tick) {
        List<FlightRadarPayload.Contact> contacts = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.level() != level || other.isSpectator() || !other.isAlive()) continue;
            Session otherSession = SESSIONS.get(other.getUUID());
            Vec3 velocity = otherSession != null && otherSession.airborne
                    ? otherSession.airVelocity : other.getDeltaMovement();
            boolean airborne = otherSession != null && otherSession.airborne && !otherSession.crashed;
            contacts.add(new FlightRadarPayload.Contact(other.getId(),
                    radarName(other.getGameProfile().name()), true, airborne,
                    other.blockPosition().getX(), other.blockPosition().getZ(),
                    (int) Math.round(other.getY() * 3.28084),
                    (int) Math.round(velocity.horizontalDistance() * FlightTuning.KNOTS_PER_BLOCK_PER_TICK),
                    radarHeading(otherSession != null ? otherSession.headingDegrees : other.getYRot()),
                    (int) Math.round(velocity.y * 3937.0 / FlightTuning.WORLD_SPEED_SCALE)));
        }
        for (MobFlightController.Traffic traffic : MobFlightController.aircraftIn(level)) {
            contacts.add(new FlightRadarPayload.Contact(traffic.entityId(), radarName(traffic.name()),
                    false, traffic.airborne(), (int) Math.floor(traffic.position().x),
                    (int) Math.floor(traffic.position().z),
                    (int) Math.round(traffic.position().y * 3.28084),
                    (int) Math.round(traffic.velocity().horizontalDistance()
                            * FlightTuning.KNOTS_PER_BLOCK_PER_TICK),
                    radarHeading(traffic.headingDegrees()),
                    (int) Math.round(traffic.velocity().y * 3937.0 / FlightTuning.WORLD_SPEED_SCALE)));
        }
        return new RadarSnapshot(tick, List.copyOf(contacts));
    }

    private static String radarName(String name) {
        return name.codePoints().limit(16).collect(StringBuilder::new, StringBuilder::appendCodePoint,
                StringBuilder::append).toString();
    }

    private static float radarHeading(double heading) {
        return Math.floorMod((int) Math.round(heading), 360);
    }

    private record RadarCandidate(double distanceSquared, FlightRadarPayload.Contact contact) {
    }

    private record RadarSnapshot(long tick, List<FlightRadarPayload.Contact> contacts) {
    }

    private record ForcedWindshear(int remainingTicks, @Nullable Double severity) {
    }

    private static final class Session {
        private AircraftType aircraftType = AircraftType.KASA_A380;
        private final boolean hadNoGravity;
        private int controls;
        private int takeoffGraceTicks;
        private int takeoffWarningTicks;
        private final int[] engineStartTicks = new int[2];
        private final int[] engineRestartAttempts = new int[2];
        private final int[] engineHotTicks = new int[2];
        private final int[] engineFireTicks = new int[2];
        private final int[] engineFireDamageCooldown = new int[2];
        private final double[] engineTemperatures = {20.0, 20.0};
        private int extinguisherBottles = FIRE_BOTTLES;
        private boolean emergencyDeclared;
        private boolean alternateGearExtended;
        private boolean hydraulicBackupUsed;
        private boolean emergencyDescent;
        private boolean fuelDumping;
        private int fuelDumpTicks;
        private int gearHeatTicks;
        private int gearGroundAlarmTicks;
        private int windShearTicks;
        private int flapDetent;
        private int windSpeedKnots;
        private int windDirectionSector;
        private int turbulencePercent;
        private int visibilityBlocks = 320;
        private int weatherCondition;
        private double flapPosition;
        private double icingPercent;
        private int gustKnots;
        private double windShearSeverity;
        private double fuelPercent;
        private double windX, windY, windZ;
        private int radioAltitudeFeet;
        private int verticalSpeedFpm;
        private int warningFlags;
        private int overspeedTicks;
        private int overspeedDamageCooldown;
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
        private boolean airbrakes;
        private boolean antiIce;
        private boolean antiIceEffective;
        private boolean autopilot;
        private boolean autoland;
        private NavMode navMode = NavMode.HDG;
        private @Nullable FlightPlan flightPlan;
        private long lastNavSyncTick = Long.MIN_VALUE;
        private int autolandRolloutTicks;
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
        private int hydraulicFailTicks;
        private boolean hydraulicFailed;
        private int cabinAltitudeFeet;
        private boolean pressLost;
        private FlightPhase phase = FlightPhase.TAXI;
        private @Nullable UUID atcControllerId;
        private int atcControllerEntityId = -1;
        private @Nullable AtcClearance atcClearance;
        private int atcControlTicks;
        private @Nullable FlightStatePayload lastFlightState;
        private @Nullable FlightInstrumentPayload lastInstruments;
        private @Nullable EmergencyStatusPayload lastEmergencyStatus;
        private @Nullable EmergencyStatusPayload lastEmergencyStatusKey;
        private @Nullable String lastPlaneId;
        private @Nullable StatusSignature lastStatusSignature;
        private int lastStatePhase = -1;
        private long lastStateTick = Long.MIN_VALUE;
        private long lastInstrumentTick = Long.MIN_VALUE;
        private long lastEmergencyTick = Long.MIN_VALUE;
        private long lastResyncTick = Long.MIN_VALUE;

        private Session(boolean hadNoGravity) {
            this.hadNoGravity = hadNoGravity;
        }
    }
}
