package dev.flightline.mixin;

import dev.flightline.client.FlightCameraEffects;
import dev.flightline.client.FlightLineClient;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class FlightCameraMixin {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private int matrixPropertiesDirty;
    @Shadow @Final private static Vector3fc FORWARDS;
    @Shadow @Final private static Vector3fc UP;
    @Shadow @Final private static Vector3fc LEFT;

    @Unique
    private static float flightline$interpolatedRoll = 0.0f;

    @Inject(method = "setRotation", at = @At("TAIL"))
    protected void flightline$applyCameraRollAndShake(float yRot, float xRot, CallbackInfo ci) {
        if (FlightLineClient.isFlightEnabled()) {
            float targetRoll = FlightLineClient.isAirborne()
                    ? (float) Math.toRadians(-FlightLineClient.bankDegrees())
                    : 0.0f;
            flightline$interpolatedRoll += (targetRoll - flightline$interpolatedRoll) * 0.22f;
            FlightCameraEffects.Shake shake = FlightCameraEffects.sample(System.nanoTime());

            float totalRoll = flightline$interpolatedRoll + shake.rollRadians();
            float pitchShake = shake.pitchRadians();
            float yawShake = shake.yawRadians();

            float yAngle = (float) Math.PI - (yRot * 0.017453292f) + yawShake;
            float xAngle = -(xRot * 0.017453292f) + pitchShake;
            float zAngle = totalRoll;

            this.rotation.rotationYXZ(yAngle, xAngle, zAngle);
            FORWARDS.rotate(this.rotation, this.forwards);
            UP.rotate(this.rotation, this.up);
            LEFT.rotate(this.rotation, this.left);
            this.matrixPropertiesDirty |= 3;
        } else {
            flightline$interpolatedRoll = 0.0f;
        }
    }
}
