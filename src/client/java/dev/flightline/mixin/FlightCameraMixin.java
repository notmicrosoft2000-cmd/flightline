package dev.flightline.mixin;

import dev.flightline.client.FlightCameraEffects;
import dev.flightline.client.FlightLineClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
    private static final float ROLL_TAU_SECONDS = 0.25f;

    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private int matrixPropertiesDirty;
    @Shadow private Vec3 position;
    @Shadow @Final private static Vector3fc FORWARDS;
    @Shadow @Final private static Vector3fc UP;
    @Shadow @Final private static Vector3fc LEFT;
    @Shadow private Entity entity;

    @Shadow
    protected void setPosition(Vec3 position) {
    }

    @Unique
    private static float flightline$interpolatedRoll = 0.0f;
    @Unique
    private static float flightline$frameRoll = 0.0f;
    @Unique
    private static double flightline$offsetX = 0.0;
    @Unique
    private static double flightline$offsetY = 0.0;
    @Unique
    private static double flightline$offsetZ = 0.0;
    @Unique
    private static boolean flightline$hasAppliedOffset = false;

    /**
     * One sample and one roll interpolation per rendered frame, before vanilla aligns the camera.
     * The previous frame's render-only offset is removed first so long flights cannot drift.
     */
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void flightline$beginCameraFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
        flightline$removeAppliedOffset();

        if (!flightline$shouldApplyEffects()) {
            flightline$clearRoll();
            FlightCameraEffects.reset();
            return;
        }

        FlightCameraEffects.beginFrame(System.nanoTime());
        float targetRoll = FlightLineClient.isAirborne()
                ? (float) Math.toRadians(-FlightLineClient.bankDegrees())
                : 0.0f;
        float rollAlpha = 1.0f - (float) Math.exp(-FlightCameraEffects.frameDeltaSeconds() / ROLL_TAU_SECONDS);
        flightline$interpolatedRoll += (targetRoll - flightline$interpolatedRoll) * rollAlpha;
        flightline$frameRoll = flightline$interpolatedRoll + FlightCameraEffects.currentSample().rollRadians();
    }

    /** Adds the capped jitter offset after vanilla has positioned the camera, before culling. */
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
                    shift = At.Shift.AFTER))
    private void flightline$applyCameraOffset(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!flightline$shouldApplyEffects()) return;
        FlightCameraEffects.Shake shake = FlightCameraEffects.currentSample();
        if (shake == FlightCameraEffects.Shake.NONE) return;

        this.setPosition(this.position.add(shake.offsetX(), shake.offsetY(), shake.offsetZ()));
        flightline$offsetX = shake.offsetX();
        flightline$offsetY = shake.offsetY();
        flightline$offsetZ = shake.offsetZ();
        flightline$hasAppliedOffset = true;
    }

    /** Rotation shake path retained from the original implementation, now driven by one sample. */
    @Inject(method = "setRotation", at = @At("TAIL"))
    protected void flightline$applyCameraRollAndShake(float yRot, float xRot, CallbackInfo ci) {
        if (!flightline$shouldApplyEffects()) {
            flightline$removeAppliedOffset();
            flightline$clearRoll();
            FlightCameraEffects.reset();
            return;
        }

        FlightCameraEffects.Shake shake = FlightCameraEffects.currentSample();

        float yAngle = (float) Math.PI - (yRot * 0.017453292f) + shake.yawRadians();
        float xAngle = -(xRot * 0.017453292f) + shake.pitchRadians();
        float zAngle = flightline$frameRoll;

        this.rotation.rotationYXZ(yAngle, xAngle, zAngle);
        FORWARDS.rotate(this.rotation, this.forwards);
        UP.rotate(this.rotation, this.up);
        LEFT.rotate(this.rotation, this.left);
        this.matrixPropertiesDirty |= 3;
    }

    /** ATC camera-follow points the camera at another entity; keep flight shake off that view. */
    private boolean flightline$shouldApplyEffects() {
        Minecraft client = Minecraft.getInstance();
        return FlightCameraEffects.isActive()
                && client.level != null
                && client.player != null
                && (this.entity == null || this.entity == client.player);
    }

    private void flightline$removeAppliedOffset() {
        if (!flightline$hasAppliedOffset) return;
        this.setPosition(this.position.add(-flightline$offsetX, -flightline$offsetY, -flightline$offsetZ));
        flightline$offsetX = 0.0;
        flightline$offsetY = 0.0;
        flightline$offsetZ = 0.0;
        flightline$hasAppliedOffset = false;
    }

    private void flightline$clearRoll() {
        flightline$interpolatedRoll = 0.0f;
        flightline$frameRoll = 0.0f;
    }
}
