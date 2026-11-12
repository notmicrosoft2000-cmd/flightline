package dev.flightline.flight;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

public final class AircraftImpact {
    private AircraftImpact() {
    }

    public static void apply(Level level, Entity source, double impactSpeed) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        double x = source.getX();
        double y = source.getY() + source.getBbHeight() * 0.5;
        double z = source.getZ();
        serverLevel.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        serverLevel.sendParticles(ParticleTypes.FLAME, x, y, z, 24, 0.65, 0.45, 0.65, 0.08);
        serverLevel.sendParticles(ParticleTypes.SMOKE, x, y, z, 32, 0.8, 0.55, 0.8, 0.12);
        serverLevel.sendParticles(ParticleTypes.CLOUD, x, y, z, 18, 0.7, 0.4, 0.7, 0.16);
        serverLevel.sendParticles(ParticleTypes.CRIT, x, y, z, 20, 0.6, 0.45, 0.6, 0.2);

        double radius = impactRadius(impactSpeed);
        double maximumImpulse = knockbackStrength(impactSpeed);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                source.getBoundingBox().inflate(radius), candidate -> candidate != source && candidate.isAlive())) {
            double dx = target.getX() - x;
            double dz = target.getZ() - z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > radius) continue;

            double falloff = 1.0 - distance / radius;
            double impulse = maximumImpulse * falloff;
            if (distance < 1.0e-4) {
                dx = source.getLookAngle().x;
                dz = source.getLookAngle().z;
                distance = Math.max(1.0e-4, Math.hypot(dx, dz));
            }
            target.push(dx / distance * impulse, 0.45 + impulse * 0.28, dz / distance * impulse);
        }
    }

    static double impactRadius(double impactSpeed) {
        return Math.min(8.0, 4.0 + impactSpeed);
    }

    static double knockbackStrength(double impactSpeed) {
        return Math.max(1.0, Math.min(3.2, impactSpeed * 1.8));
    }
}
