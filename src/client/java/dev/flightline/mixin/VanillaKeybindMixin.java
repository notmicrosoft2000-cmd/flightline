package dev.flightline.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flightline.client.FlightLineClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class VanillaKeybindMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void flightline$drainSuppressedClicks(CallbackInfo callbackInfo) {
        FlightLineClient.drainSuppressedVanillaClicks();
    }

    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean flightline$suppressHeldVanillaKeys(KeyMapping mapping) {
        return !FlightLineClient.isVanillaKeySuppressed(mapping) && mapping.isDown();
    }

    @Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
    private void flightline$suppressFriendsKey(InputConstants.Key key, boolean controlDown,
                                               CallbackInfoReturnable<Boolean> callbackInfo) {
        Minecraft client = (Minecraft) (Object) this;
        if (FlightLineClient.isVanillaKeySuppressed(client.options.keyFriends)
                && client.options.keyFriends.matches(key)) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void flightline$glowSelectedAtcEntity(net.minecraft.world.entity.Entity entity,
                                                  CallbackInfoReturnable<Boolean> callbackInfo) {
        if (entity != null && FlightLineClient.isAtcSelectedTarget(entity.getId())) {
            callbackInfo.setReturnValue(true);
        }
    }
}
