package dev.flightline.mixin;

import dev.flightline.client.FlightCameraEffects;
import dev.flightline.client.FlightLineClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class FlightCameraMixin {
    @Unique
    private float flightline$rollRadians;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void flightline$tiltCameraWithBank(CameraRenderState state, DeltaTracker deltaTracker,
                                               CallbackInfo callbackInfo) {
        float target = FlightLineClient.isFlightEnabled()
                ? (float) Math.toRadians(-FlightLineClient.bankDegrees())
                : 0.0f;
        float difference = target - flightline$rollRadians;
        flightline$rollRadians += Math.max(-0.025f, Math.min(0.025f, difference));
        state.orientation.rotateLocalZ(flightline$rollRadians);
        state.viewRotationMatrix.rotateLocalZ(flightline$rollRadians);

        FlightCameraEffects.Shake shake = FlightCameraEffects.sample(System.nanoTime());
        state.orientation.rotateLocalX(shake.pitchRadians());
        state.orientation.rotateLocalY(shake.yawRadians());
        state.orientation.rotateLocalZ(shake.rollRadians());
        state.viewRotationMatrix.rotateLocalX(shake.pitchRadians());
        state.viewRotationMatrix.rotateLocalY(shake.yawRadians());
        state.viewRotationMatrix.rotateLocalZ(shake.rollRadians());
    }
}
