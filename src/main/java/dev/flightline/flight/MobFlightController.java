package dev.flightline.flight;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Gives AI-driven mobs autonomous, server-owned aircraft movement for traffic simulation. */
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
                if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isPassenger() || mob.isVehicle()) continue;
                loaded.add(mob.getUUID());
                MobAircraft aircraft = AIRCRAFT.get(mob.getUUID());
                if (aircraft == null) {
                    if (mob.isNoAi()) continue;
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

    private static void simulate(MobAircraft aircraft, MinecraftServer server) {
        Mob mob = aircraft.mob;
        mob.setNoAi(true);
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

        if (mob.horizontalCollision || (aircraft.airborne && mob.verticalCollision)) {
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
        if (aircraft.targetTicks-- <= 0 || mob.position().distanceToSqr(aircraft.target) < 64.0) {
            chooseTarget(aircraft, server);
            aircraft.targetTicks = 100 + Math.floorMod(mob.getUUID().hashCode() + aircraft.ticks, 100);
        }

        double desiredHeading = bearing(mob.position(), aircraft.target);
        double headingError = wrap(desiredHeading - aircraft.heading);
        double desiredBank = Math.max(-28.0, Math.min(28.0, headingError * 0.45));
        aircraft.bank = approach(aircraft.bank, desiredBank, 0.18);
        aircraft.heading = wrap(aircraft.heading + Math.max(-2.8, Math.min(2.8,
                Math.tan(Math.toRadians(aircraft.bank)) * 3.2)));

        double altitudeError = aircraft.target.y - mob.getY();
        double desiredVerticalSpeed = Math.max(-0.13, Math.min(0.13, altitudeError * 0.006));
        aircraft.verticalSpeed = approach(aircraft.verticalSpeed, desiredVerticalSpeed, 0.006);
        aircraft.speed = approach(aircraft.speed, CRUISE_SPEED, 0.002);
        Vec3 forward = heading(aircraft.heading).scale(aircraft.speed);
        aircraft.velocity = new Vec3(forward.x, aircraft.verticalSpeed, forward.z);

        mob.setYRot((float) aircraft.heading);
        mob.setYHeadRot((float) aircraft.heading);
        mob.setXRot((float) -Math.toDegrees(Math.atan2(aircraft.verticalSpeed, Math.max(0.001, aircraft.speed))));
        mob.setDeltaMovement(aircraft.velocity);

        avoidPlayerCollision(aircraft, server);
    }

    private static void chooseTarget(MobAircraft aircraft, MinecraftServer server) {
        Mob mob = aircraft.mob;
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

    private static void avoidPlayerCollision(MobAircraft aircraft, MinecraftServer server) {
        Mob mob = aircraft.mob;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != mob.level() || !player.isAlive() || player.isSpectator()) continue;
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
        if (impactSpeed > 0.7) {
            aircraft.mob.hurt(aircraft.mob.damageSources().generic(), (float) Math.min(20.0, impactSpeed * 8.0));
        }
    }

    private static void restore(MobAircraft aircraft) {
        Mob mob = aircraft.mob;
        if (mob.isRemoved()) return;
        mob.setNoAi(aircraft.originalNoAi);
        mob.setNoGravity(aircraft.originalNoGravity);
    }

    public static List<Traffic> aircraftIn(ServerLevel level) {
        List<Traffic> traffic = new ArrayList<>();
        for (MobAircraft aircraft : AIRCRAFT.values()) {
            if (aircraft.airborne && !aircraft.crashed && aircraft.mob.level() == level && aircraft.mob.isAlive()) {
                traffic.add(new Traffic(aircraft.mob, aircraft.velocity));
            }
        }
        return traffic;
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

    public record Traffic(Mob mob, Vec3 velocity) {
    }

    private static final class MobAircraft {
        private Mob mob;
        private final boolean originalNoAi;
        private final boolean originalNoGravity;
        private int ticks;
        private int targetTicks;
        private int crashTicks;
        private double speed;
        private double heading;
        private double bank;
        private double verticalSpeed;
        private Vec3 velocity = Vec3.ZERO;
        private Vec3 target;
        private boolean airborne;
        private boolean crashed;

        private MobAircraft(Mob mob) {
            this.mob = mob;
            this.originalNoAi = mob.isNoAi();
            this.originalNoGravity = mob.isNoGravity();
            this.heading = mob.getYRot();
            this.speed = mob.getDeltaMovement().horizontalDistance();
            this.velocity = mob.getDeltaMovement();
            this.target = mob.position();
        }
    }
}
