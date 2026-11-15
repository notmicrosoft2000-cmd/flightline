package dev.flightline.client;

import dev.flightline.FlightLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

final class FlightEngineSound extends AbstractTickableSoundInstance {
    private final boolean cruise;

    FlightEngineSound(boolean cruise) {
        super(cruise ? FlightLine.ENGINE_CRUISE : FlightLine.ENGINE_LOOP,
                SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.cruise = cruise;
        this.looping = true;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = cruise ? 0.9f : 0.45f;
        this.pitch = cruise ? 0.95f : 0.65f;
    }

    @Override
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || !FlightLineClient.areEnginesActive() || !FlightLineClient.isFlightEnabled()) {
            stop();
            return;
        }

        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        float throttle = FlightHud.throttle();
        if (cruise) {
            // High altitude / airborne high thrust jet roar
            this.volume = 0.80f + throttle * 0.20f;
            this.pitch = 0.92f + throttle * 0.18f;
        } else {
            // Distinct ground idle turbofan whine, rumble, and taxi spool-up
            this.volume = 0.48f + throttle * 0.42f;
            this.pitch = 0.60f + throttle * 0.38f;
        }
    }

    boolean isCruise() { return cruise; }

    void stopLoop() {
        stop();
    }
}
