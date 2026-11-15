package dev.flightline.flight;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

        // Explosion power and block destruction scale with crash severity
        float explosionPower = (float) Math.min(9.0, Math.max(2.5, impactSpeed * 3.8));
        boolean createFire = impactSpeed > 0.65;
        serverLevel.explode(source, x, y, z, explosionPower, createFire, Level.ExplosionInteraction.BLOCK);

        serverLevel.playSound(null, source.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.BLOCKS, 4.0f, 0.65f);
        if (impactSpeed > 0.8) {
            serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 2, 0.5, 0.5, 0.5, 0.0);
        }
        serverLevel.sendParticles(ParticleTypes.FLAME, x, y, z, 48, 1.2, 0.8, 1.2, 0.15);
        serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 64, 1.5, 1.2, 1.5, 0.18);
        serverLevel.sendParticles(ParticleTypes.LAVA, x, y, z, 30, 0.9, 0.6, 0.9, 0.2);

        // Giant knockback flinging surrounding mobs and players away
        double radius = impactRadius(impactSpeed);
        double maximumImpulse = knockbackStrength(impactSpeed);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                source.getBoundingBox().inflate(radius), candidate -> candidate != source && candidate.isAlive())) {
            double dx = target.getX() - x;
            double dz = target.getZ() - z;
            double distance = Math.hypot(dx, dz);
            if (distance > radius) continue;

            double falloff = 1.0 - distance / radius;
            double impulse = maximumImpulse * falloff;
            if (distance < 1.0e-4) {
                dx = source.getLookAngle().x;
                dz = source.getLookAngle().z;
                distance = Math.max(1.0e-4, Math.hypot(dx, dz));
            }
            target.push(dx / distance * impulse, 0.65 + impulse * 0.35, dz / distance * impulse);
        }
    }

    static double impactRadius(double impactSpeed) {
        return Math.min(24.0, 6.0 + impactSpeed * 8.0);
    }

    static double knockbackStrength(double impactSpeed) {
        return Math.max(1.5, Math.min(6.5, impactSpeed * 3.6));
    }
}
