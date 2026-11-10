package dev.flightline.mixin;

import dev.flightline.client.FlightLineClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class EngineInventoryMixin {
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
    private boolean flightline$requireEnginesOffForInventory(KeyMapping mapping) {
        Minecraft client = (Minecraft) (Object) this;
        if (mapping == client.options.keyInventory && FlightLineClient.areEnginesActive()) {
            // Discard the queued click while locked; leaving it pending causes E to
            // open inventory later when the engines are shut down.
            mapping.consumeClick();
            return false;
        }
        return mapping.consumeClick();
    }
}
