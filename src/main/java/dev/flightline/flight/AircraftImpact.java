package dev.flightline.flight;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class AircraftImpact {
    private AircraftImpact() {
    }

    public static void apply(Level level, Entity source, double impactSpeed) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        double x = source.getX();
        double y = source.getY() + source.getBbHeight() * 0.5;
        double z = source.getZ();
        serverLevel.playSound(null, source.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.BLOCKS, 2.0f, 0.72f);
        serverLevel.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        serverLevel.sendParticles(ParticleTypes.FLAME, x, y, z, 24, 0.65, 0.45, 0.65, 0.08);
        serverLevel.sendParticles(ParticleTypes.SMOKE, x, y, z, 32, 0.8, 0.55, 0.8, 0.12);
        serverLevel.sendParticles(ParticleTypes.CLOUD, x, y, z, 18, 0.7, 0.4, 0.7, 0.16);
        serverLevel.sendParticles(ParticleTypes.CRIT, x, y, z, 20, 0.6, 0.45, 0.6, 0.2);
        if (impactSpeed > 0.6) carveSmallCrater(serverLevel, source, impactSpeed);

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

    private static void carveSmallCrater(ServerLevel level, Entity source, double impactSpeed) {
        int centerX = source.blockPosition().getX();
        int centerZ = source.blockPosition().getZ();
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, centerX, centerZ) - 1;
        if (source.getY() - surfaceY > 3.0) return;

        int radius = impactSpeed > 1.8 ? 3 : 2;
        int depth = impactSpeed > 1.8 ? 2 : 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) continue;
                int columnX = centerX + dx;
                int columnZ = centerZ + dz;
                int columnSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, columnX, columnZ) - 1;
                int columnDepth = Math.max(1, (int) Math.ceil(depth * (1.0 - distance / (radius + 1.0))));
                for (int offset = 0; offset < columnDepth; offset++) {
                    BlockPos position = new BlockPos(columnX, columnSurface - offset, columnZ);
                    BlockState state = level.getBlockState(position);
                    if (level.getBlockEntity(position) != null
                            || !(state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                            || state.is(BlockTags.BASE_STONE_OVERWORLD))) break;
                    level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
