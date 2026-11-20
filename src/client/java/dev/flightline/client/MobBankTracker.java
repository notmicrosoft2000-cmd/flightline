package dev.flightline.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.Map;

/**
 * Estimates a visual roll angle and dynamic flight buffeting for nearby mob aircraft from their heading changes.
 * The server still owns the authoritative flight path; this makes the model
 * lean into turns and visually wobble in the air instead of sliding around perfectly upright.
 */
public final class MobBankTracker {
    private static final Map<Integer, BankState> STATES = new HashMap<>();

    private MobBankTracker() {
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) {
            STATES.clear();
            return;
        }
        Map<Integer, BankState> next = new HashMap<>();
        double time = System.currentTimeMillis() / 1000.0;
        for (Mob mob : client.level.getEntitiesOfClass(Mob.class,
                client.player.getBoundingBox().inflate(256.0), Mob::isAlive)) {
            int id = mob.getId();
            BankState state = STATES.get(id);
            if (state == null) {
                state = new BankState(mob.getYRot(), 0.0f, 0.0f);
            }
            double delta = wrapDegrees(mob.getYRot() - state.lastYaw);
            state.lastYaw = mob.getYRot();
            if (Math.abs(delta) > 45.0) delta = 0.0;
            float target = mob.onGround() ? 0.0f : (float) Math.max(-35.0, Math.min(35.0, delta * 16.0));
            float response = Math.abs(target) > 0.5f ? 0.25f : 0.12f;
            state.bank += (target - state.bank) * response;

            float pitchTarget = mob.onGround() ? 0.0f : (float) Math.max(-45.0, Math.min(45.0, -mob.getXRot()));
            state.pitch += (pitchTarget - state.pitch) * 0.20f;

            if (!mob.onGround()) {
                state.wobble = (float) (Math.sin(time * 9.0 + id * 1.7) * 2.2);
            } else {
                state.wobble = 0.0f;
            }
            next.put(id, state);
        }
        STATES.clear();
        STATES.putAll(next);
    }

    public static float bankFor(int entityId) {
        BankState state = STATES.get(entityId);
        return state == null ? 0.0f : (state.bank + state.wobble);
    }

    public static float pitchFor(int entityId) {
        BankState state = STATES.get(entityId);
        return state == null ? 0.0f : state.pitch;
    }

    public static void clear() {
        STATES.clear();
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static final class BankState {
        private double lastYaw;
        private float bank;
        private float pitch;
        private float wobble;

        private BankState(double lastYaw, float bank, float pitch) {
            this.lastYaw = lastYaw;
            this.bank = bank;
            this.pitch = pitch;
            this.wobble = 0.0f;
        }
    }
}
