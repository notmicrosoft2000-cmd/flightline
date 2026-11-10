package dev.flightline.client;

import dev.flightline.FlightLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

final class FlightEngineSound extends AbstractTickableSoundInstance {
    FlightEngineSound() {
        super(FlightLine.ENGINE_LOOP, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.12f;
        this.pitch = 0.85f;
    }

    @Override
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !FlightLineClient.areEnginesActive() || !FlightLineClient.isFlightEnabled()) {
            stop();
            return;
        }

        this.x = client.player.getX();
        this.y = client.player.getY();
        this.z = client.player.getZ();
        float throttle = FlightHud.throttle();
        this.volume = 0.10f + throttle * 0.14f;
        this.pitch = 0.78f + throttle * 0.42f;
    }

    void stopLoop() {
        stop();
    }
}
