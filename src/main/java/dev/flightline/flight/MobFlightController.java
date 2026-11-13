package dev.flightline.flight;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import dev.flightline.network.AtcOrderPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adds server-owned aircraft movement to eligible mobs while retaining their native AI. */
public final class MobFlightController {
    private static final double TAXI_ACCELERATION = 0.009;
    private static final double TAKEOFF_SPEED = 0.58;
    private static final double CRUISE_SPEED = 0.62;
    private static final Map<UUID, MobAircraft> AIRCRAFT = new HashMap<>();

    private MobFlightController() {
    }

    public static void tick(MinecraftServer server) {
        Set<UUID> loaded = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
                if (mob.isNoAi() || mob.isPassenger() || mob.isVehicle()) {
                    MobAircraft existing = AIRCRAFT.remove(mob.getUUID());
                    if (existing != null) restore(existing);
                    continue;
                }
                loaded.add(mob.getUUID());
                MobAircraft aircraft = AIRCRAFT.get(mob.getUUID());
                if (aircraft == null) {
                    aircraft = new MobAircraft(mob);
                    AIRCRAFT.put(mob.getUUID(), aircraft);
                }
                aircraft.mob = mob;
                simulate(aircraft, server);
            }
        }

        Iterator<Map.Entry<UUID, MobAircraft>> iterator = AIRCRAFT.entrySet().iterator();
        while (iterator.hasNext()) {
            MobAircraft aircraft = iterator.next().getValue();
            if (!loaded.contains(aircraft.mob.getUUID())) {
                restore(aircraft);
                iterator.remove();
            }
        }
    }

    public static boolean issueAtcOrder(ServerPlayer commander, Mob mob, int order, int value, int referenceEntityId) {
        MobAircraft aircraft = AIRCRAFT.get(mob.getUUID());
        if (aircraft == null || aircraft.crashed || !mob.isAlive()) return false;
        if (order < AtcOrderPayload.HOLD || order > AtcOrderPayload.FOLLOW) return false;

        switch (order) {
            case AtcOrderPayload.HOLD -> {
                aircraft.targetHeading = aircraft.heading;
                aircraft.targetAltitude = mob.getY();
            }
            case AtcOrderPayload.TURN -> {
                if (value < 0 || value >= 360) return false;
                aircraft.targetHeading = value;
                aircraft.targetAltitude = mob.getY();
            }
            case AtcOrderPayload.CLIMB, AtcOrderPayload.DESCEND -> {
                if (value < 0 || value > 40000) return false;
                aircraft.targetHeading = aircraft.heading;
                aircraft.targetAltitude = Math.max(mob.level().getMinY() + 8.0,
                        Math.min(mob.level().getMaxY() - 8.0, value * 0.3048));
                if (order == AtcOrderPayload.CLIMB && aircraft.targetAltitude <= mob.getY()) return false;
                if (order == AtcOrderPayload.DESCEND && aircraft.targetAltitude >= mob.getY()) return false;
            }
            case AtcOrderPayload.SPEED -> {
                if (value < 40 || value > 300) return false;
                aircraft.targetSpeed = Math.max(0.30, Math.min(0.75, CRUISE_SPEED * value / 130.0));
            }
            case AtcOrderPayload.ORBIT, AtcOrderPayload.RETURN -> {
                aircraft.commanderId = commander.getId();
            }
            case AtcOrderPayload.FOLLOW -> {
                Entity reference = mob.level().getEntity(referenceEntityId);
                if (reference == null || reference == mob || reference == commander) return false;
                if (!(reference instanceof ServerPlayer)
                        && (!(reference instanceof Mob leadMob) || !AIRCRAFT.containsKey(leadMob.getUUID()))) {
                    return false;
                }
                aircraft.followEntityId = referenceEntityId;
            }
            default -> {
                return false;
            }
        }
        aircraft.atcOrder = order;
        aircraft.targetTicks = 0;
        return true;
    }

    private static void simulate(MobAircraft aircraft, MinecraftServer server) {
        Mob mob = aircraft.mob;
        aircraft.ticks++;

        if (aircraft.crashed) {
            mob.setNoGravity(false);
            if (aircraft.crashTicks-- <= 0) {
                restore(aircraft);
                AIRCRAFT.remove(mob.getUUID());
            } else {
                mob.setDeltaMovement(aircraft.velocity);
                aircraft.velocity = aircraft.velocity.scale(0.58).add(0.0, -0.035, 0.0);
            }
            return;
        }

        if (aircraft.airborne && mob.onGround()) {
            aircraft.airborne = false;
            aircraft.touchdownTicks = 120;
            aircraft.speed = mob.getDeltaMovement().horizontalDistance();
            aircraft.verticalSpeed = 0.0;
            aircraft.velocity = heading(aircraft.heading).scale(aircraft.speed);
            mob.setNoGravity(aircraft.originalNoGravity);
            mob.setXRot(0.0f);
            mob.resetFallDistance();
            mob.setDeltaMovement(aircraft.velocity);
            return;
        }

        if (aircraft.touchdownTicks > 0) {
            aircraft.touchdownTicks--;
            mob.setNoGravity(aircraft.originalNoGravity);
            aircraft.speed = approach(aircraft.speed, 0.0, 0.009);
            aircraft.velocity = heading(aircraft.heading).scale(aircraft.speed);
            mob.setDeltaMovement(aircraft.velocity);
            return;
        }

        if ((aircraft.airborne && (mob.horizontalCollision || mob.verticalCollision))
                || (!aircraft.airborne && aircraft.touchdownTicks <= 0 && mob.horizontalCollision)) {
            crash(aircraft, aircraft.velocity.length());
            return;
        }

        if (!aircraft.airborne) {
            mob.setNoGravity(false);
            aircraft.speed = Math.min(TAKEOFF_SPEED, aircraft.speed + TAXI_ACCELERATION);
            mob.setYRot((float) aircraft.heading);
            mob.setYHeadRot((float) aircraft.heading);
            aircraft.velocity = heading(aircraft.heading).scale(aircraft.speed);
            mob.setDeltaMovement(aircraft.velocity);
            if (aircraft.speed >= TAKEOFF_SPEED) {
                aircraft.airborne = true;
                aircraft.velocity = aircraft.velocity.add(0.0, 0.14, 0.0);
                mob.setNoGravity(true);
            }
            return;
        }

        mob.setNoGravity(true);
        if (aircraft.atcOrder != -1) {
            updateAtcTarget(aircraft);
        } else if (aircraft.targetTicks-- <= 0 || mob.position().distanceToSqr(aircraft.target) < 64.0) {
            chooseTarget(aircraft, server);
            aircraft.targetTicks = 100 + Math.floorMod(mob.getUUID().hashCode() + aircraft.ticks, 100);
        }

        double desiredHeading = bearing(mob.position(), aircraft.target);
        double headingError = wrap(desiredHeading - aircraft.heading);
        double desiredBank = Math.max(-28.0, Math.min(28.0, headingError * 0.45));
        aircraft.bank = approach(aircraft.bank, desiredBank, 0.18);
        double turnRate = Math.tan(Math.toRadians(aircraft.bank)) * 3.2
                * CRUISE_SPEED / Math.max(0.35, aircraft.speed);
        aircraft.heading = wrap(aircraft.heading + Math.max(-2.8, Math.min(2.8, turnRate)));

        double altitudeError = aircraft.target.y - mob.getY();
        double desiredVerticalSpeed = Math.max(-0.13, Math.min(0.13, altitudeError * 0.006));
        desiredVerticalSpeed = tcasAvoidance(mob, aircraft, desiredVerticalSpeed);
        aircraft.verticalSpeed = approach(aircraft.verticalSpeed, desiredVerticalSpeed, 0.006);
        aircraft.speed = approach(aircraft.speed, aircraft.targetSpeed, 0.002);
        Vec3 forward = heading(aircraft.heading).scale(aircraft.speed);
        aircraft.velocity = new Vec3(forward.x, aircraft.verticalSpeed, forward.z);

        mob.setYRot((float) aircraft.heading);
        mob.setYHeadRot((float) aircraft.heading);
        mob.setXRot((float) -Math.toDegrees(Math.atan2(aircraft.verticalSpeed, Math.max(0.001, aircraft.speed))));
        mob.setDeltaMovement(aircraft.velocity);

        avoidPlayerCollision(aircraft);
    }

    private static void updateAtcTarget(MobAircraft aircraft) {
        Mob mob = aircraft.mob;
        if (aircraft.atcOrder == AtcOrderPayload.ORBIT || aircraft.atcOrder == AtcOrderPayload.RETURN) {
            Entity commander = mob.level().getEntity(aircraft.commanderId);
            if (commander == null || !commander.isAlive()) {
                aircraft.atcOrder = -1;
                return;
            }
            if (aircraft.atcOrder == AtcOrderPayload.RETURN) {
                aircraft.target = commander.position().add(0.0, 4.0, 0.0);
            } else {
                double angle = aircraft.ticks * 0.025;
                aircraft.target = commander.position().add(Math.cos(angle) * 24.0, 8.0,
                        Math.sin(angle) * 24.0);
            }
            return;
        }
        if (aircraft.atcOrder == AtcOrderPayload.FOLLOW) {
            Entity reference = mob.level().getEntity(aircraft.followEntityId);
            if (reference == null || !reference.isAlive()) {
                aircraft.atcOrder = -1;
                return;
            }
            aircraft.target = reference.position().add(0.0, 5.0, 0.0);
            return;
        }

        Vec3 forward = heading(aircraft.targetHeading);
        aircraft.target = new Vec3(mob.getX() + forward.x * 240.0,
                aircraft.targetAltitude, mob.getZ() + forward.z * 240.0);
    }

    private static double tcasAvoidance(Mob mob, MobAircraft aircraft, double desiredVerticalSpeed) {
        // 1. Avoid nearby airborne players
        for (ServerPlayer player : mob.level().getEntitiesOfClass(ServerPlayer.class,
                mob.getBoundingBox().inflate(80.0), candidate -> candidate.isAlive() && !candidate.isSpectator()
                        && FlightController.isAirborne(candidate))) {
            Vec3 separation = mob.position().subtract(player.position());
            Vec3 relativeVelocity = aircraft.velocity.subtract(player.getDeltaMovement());
            double speedSquared = relativeVelocity.lengthSqr();
            double time = speedSquared < 1.0e-6 ? 0.0 : -separation.dot(relativeVelocity) / speedSquared;
            if (time < 0.0 || time > 300.0) continue;
            Vec3 closest = separation.add(relativeVelocity.scale(time));
            if (closest.horizontalDistance() >= 24.0 || Math.abs(closest.y) >= 12.0) continue;
            // Coordinated escape with player: if mob is higher than player, mob climbs; otherwise descends
            double escape = separation.y >= 0.0 ? 0.14 : -0.14;
            if (relativeVelocity.horizontalDistance() > 0.35) {
                aircraft.heading = wrap(aircraft.heading + 1.2);
            }
            return escape;
        }

        // 2. Avoid other airborne mobs (Mob-to-Mob TCAS)
        for (MobAircraft other : AIRCRAFT.values()) {
            if (other == aircraft || other.crashed || !other.airborne || other.mob.level() != mob.level() || !other.mob.isAlive()) {
                continue;
            }
            Vec3 separation = mob.position().subtract(other.mob.position());
            if (separation.lengthSqr() > 80.0 * 80.0) continue;
            Vec3 relativeVelocity = aircraft.velocity.subtract(other.velocity);
            double speedSquared = relativeVelocity.lengthSqr();
            double time = speedSquared < 1.0e-6 ? 0.0 : -separation.dot(relativeVelocity) / speedSquared;
            if (time < 0.0 || time > 300.0) continue;
            Vec3 closest = separation.add(relativeVelocity.scale(time));
            if (closest.horizontalDistance() >= 24.0 || Math.abs(closest.y) >= 12.0) continue;

            // Deterministic complementary vertical resolution:
            double escape;
            if (Math.abs(separation.y) > 0.8) {
                escape = separation.y > 0.0 ? 0.14 : -0.14;
            } else {
                // Same altitude tie-breaker: deterministic opposite commands
                escape = mob.getUUID().compareTo(other.mob.getUUID()) > 0 ? 0.14 : -0.14;
            }
            if (relativeVelocity.horizontalDistance() > 0.35) {
                aircraft.heading = wrap(aircraft.heading + 1.2);
            }
            return escape;
        }

        return desiredVerticalSpeed;
    }

    private static void chooseTarget(MobAircraft aircraft, MinecraftServer server) {
        Mob mob = aircraft.mob;
        LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) {
            aircraft.target = target.position().add(0.0, 4.0, 0.0);
            return;
        }
        ServerPlayer nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != mob.level() || !player.isAlive() || player.isSpectator()) continue;
            double distance = player.distanceToSqr(mob);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }

        if (nearest != null) {
            double orbitAngle = Math.floorMod(mob.getUUID().hashCode(), 360) * Math.PI / 180.0
                    + aircraft.ticks * 0.004;
            aircraft.target = nearest.position().add(Math.cos(orbitAngle) * 7.5,
                    3.0, Math.sin(orbitAngle) * 7.5);
        } else {
            long seed = mob.getUUID().getLeastSignificantBits() + aircraft.ticks;
            double angle = Math.floorMod(seed, 360L) * Math.PI / 180.0;
            aircraft.target = mob.position().add(Math.cos(angle) * 80.0,
                    Math.sin(angle * 2.0) * 8.0, Math.sin(angle) * 80.0);
        }
        double maxY = mob.level().getMaxY() - 8.0;
        aircraft.target = new Vec3(aircraft.target.x,
                Math.max(mob.level().getMinY() + 16.0, Math.min(maxY, aircraft.target.y)), aircraft.target.z);
    }

    private static void avoidPlayerCollision(MobAircraft aircraft) {
        Mob mob = aircraft.mob;
        for (ServerPlayer player : mob.level().getEntitiesOfClass(ServerPlayer.class,
                mob.getBoundingBox().inflate(1.5), candidate -> candidate.isAlive() && !candidate.isSpectator())) {
            Vec3 separation = player.position().subtract(mob.position());
            if (separation.lengthSqr() > 1.5 * 1.5) continue;
            FlightController.handleMobCollision(player, mob, aircraft.velocity);
            crash(aircraft, aircraft.velocity.length());
            return;
        }
        for (Mob otherMob : mob.level().getEntitiesOfClass(Mob.class,
                mob.getBoundingBox().inflate(1.2), candidate -> candidate != mob)) {
            MobAircraft other = AIRCRAFT.get(otherMob.getUUID());
            if (other == null || other.crashed || !other.airborne) continue;
            if (mob.distanceToSqr(otherMob) < 1.2 * 1.2) {
                crash(aircraft, aircraft.velocity.subtract(other.velocity).length());
                crash(other, other.velocity.subtract(aircraft.velocity).length());
                return;
            }
        }
    }

    private static void crash(MobAircraft aircraft, double impactSpeed) {
        if (aircraft.crashed) return;
        aircraft.crashed = true;
        aircraft.crashTicks = 40;
        aircraft.mob.setNoGravity(false);
        aircraft.velocity = new Vec3(-aircraft.velocity.x * 0.28,
                Math.max(0.08, -aircraft.velocity.y * 0.16), -aircraft.velocity.z * 0.28);
        aircraft.mob.setDeltaMovement(aircraft.velocity);
        if (impactSpeed > 0.35) {
            AircraftImpact.apply(aircraft.mob.level(), aircraft.mob, impactSpeed);
            if (aircraft.mob.level() instanceof ServerLevel serverLevel) {
                aircraft.mob.hurtServer(serverLevel, aircraft.mob.damageSources().generic(),
                        (float) Math.min(20.0, impactSpeed * 8.0));
            }
        }
    }

    private static void restore(MobAircraft aircraft) {
        Mob mob = aircraft.mob;
        mob.setNoGravity(aircraft.originalNoGravity);
    }

    public static List<Traffic> aircraftIn(ServerLevel level) {
        List<Traffic> traffic = new ArrayList<>();
        for (MobAircraft aircraft : AIRCRAFT.values()) {
            if (!aircraft.crashed && aircraft.mob.level() == level && aircraft.mob.isAlive()) {
                traffic.add(new Traffic(aircraft.mob.getId(), aircraft.mob.getName().getString(), aircraft.mob.position(),
                        aircraft.velocity, aircraft.airborne, aircraft.mob.getYRot()));
            }
        }
        return traffic;
    }

    public static boolean suppressVanillaFallDamage(Mob mob) {
        MobAircraft aircraft = AIRCRAFT.get(mob.getUUID());
        return aircraft != null && !aircraft.crashed;
    }

    private static Vec3 heading(double yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    private static double bearing(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return Math.toDegrees(Math.atan2(-delta.x, delta.z));
    }

    private static double wrap(double value) {
        value %= 360.0;
        if (value >= 180.0) value -= 360.0;
        if (value < -180.0) value += 360.0;
        return value;
    }

    private static double approach(double current, double target, double step) {
        if (current < target) return Math.min(target, current + step);
        return Math.max(target, current - step);
    }

    public record Traffic(int entityId, String name, Vec3 position, Vec3 velocity,
                          boolean airborne, float headingDegrees) {
    }

    private static final class MobAircraft {
        private Mob mob;
        private final boolean originalNoGravity;
        private int ticks;
        private int targetTicks;
        private int crashTicks;
        private int touchdownTicks;
        private double speed;
        private double heading;
        private double bank;
        private double verticalSpeed;
        private double targetHeading;
        private double targetAltitude;
        private double targetSpeed = CRUISE_SPEED;
        private int atcOrder = -1;
        private int commanderId = -1;
        private int followEntityId = -1;
        private Vec3 velocity = Vec3.ZERO;
        private Vec3 target;
        private boolean airborne;
        private boolean crashed;

        private MobAircraft(Mob mob) {
            this.mob = mob;
            this.originalNoGravity = mob.isNoGravity();
            this.heading = mob.getYRot();
            this.targetHeading = this.heading;
            this.targetAltitude = mob.getY();
            this.speed = mob.getDeltaMovement().horizontalDistance();
            this.velocity = mob.getDeltaMovement();
            this.target = mob.position();
        }
    }
}
