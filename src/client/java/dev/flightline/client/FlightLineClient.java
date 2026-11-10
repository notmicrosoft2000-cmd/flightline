package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flightline.FlightLine;
import dev.flightline.flight.FlightControls;
import dev.flightline.network.AdjustFlapsPayload;
import dev.flightline.network.FlightInputPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightStatePayload;
import dev.flightline.network.StartEnginesPayload;
import dev.flightline.network.ShutdownEnginesPayload;
import dev.flightline.network.ToggleGearPayload;
import dev.flightline.network.TogglePushbackPayload;
import dev.flightline.network.ToggleSpoilersPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public class FlightLineClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(FlightLine.id("controls"));
    private static final KeyMapping TOGGLE_GEAR = key("key.flightline.toggle_gear", 85);
    private static final KeyMapping START_ENGINES = key("key.flightline.start_engines", 74);
    private static final KeyMapping SHUTDOWN_ENGINES = key("key.flightline.shutdown_engines", 76);
    private static final KeyMapping TOGGLE_PUSHBACK = key("key.flightline.toggle_pushback", 75);
    private static final KeyMapping BRAKES = key("key.flightline.brakes", 77);
    private static final KeyMapping FLAPS_UP = key("key.flightline.flaps_up", 88);
    private static final KeyMapping FLAPS_DOWN = key("key.flightline.flaps_down", 90);
    private static final KeyMapping TOGGLE_SPOILERS = key("key.flightline.spoilers", 79);
    private static volatile boolean flightEnabled;
    private Boolean savedViewBobbing;
    private static volatile float bankDegrees;
    private static volatile boolean airborne;
    private static volatile boolean enginesActive;
    private int lastSentControls = -1;
    private boolean bindingsMigrated;
    private FlightEngineSound engineSound;

    private static KeyMapping key(String translation, int keyCode) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translation,
                InputConstants.Type.KEYBOARD, keyCode, CATEGORY));
    }

    public static boolean isFlightEnabled() { return flightEnabled; }

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(FlightStatePayload.TYPE, (payload, context) -> {
            flightEnabled = payload.enabled();
            airborne = payload.enabled() && (payload.phase() == 4 || payload.phase() == 5);
            if (lastSentControls == -1) lastSentControls = 0;
            FlightHud.update(payload);
            Minecraft client = context.client();
            updateViewBobbing(client);
            if (client.player != null && payload.enabled()) {
                client.player.setNoGravity(payload.phase() == 4 || payload.phase() == 5);
                client.player.setDeltaMovement(payload.velocityX(), payload.velocityY(), payload.velocityZ());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(FlightInstrumentPayload.TYPE, (payload, context) -> {
            bankDegrees = payload.bankDegrees();
            if (context.client().player != null && flightEnabled) {
                context.client().player.setYRot(payload.headingDegrees());
                context.client().player.setYHeadRot(payload.headingDegrees());
            }
            int engine1 = payload.systems() & 7;
            int engine2 = (payload.systems() >> 3) & 7;
            enginesActive = engine1 == 1 || engine1 == 2 || engine2 == 1 || engine2 == 2;
            FlightHud.updateInstruments(payload);
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                FlightLine.id("flight_status"), FlightHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            migrateLegacyBindings();
            updateViewBobbing(client);
            updateEngineSound(client);
            if (client.player == null || client.level == null || !flightEnabled) return;
            while (TOGGLE_GEAR.consumeClick()) ClientPlayNetworking.send(new ToggleGearPayload());
            while (START_ENGINES.consumeClick()) ClientPlayNetworking.send(new StartEnginesPayload());
            while (SHUTDOWN_ENGINES.consumeClick()) ClientPlayNetworking.send(new ShutdownEnginesPayload());
            while (TOGGLE_PUSHBACK.consumeClick()) ClientPlayNetworking.send(new TogglePushbackPayload());
            while (FLAPS_UP.consumeClick()) ClientPlayNetworking.send(new AdjustFlapsPayload(1));
            while (FLAPS_DOWN.consumeClick()) ClientPlayNetworking.send(new AdjustFlapsPayload(-1));
            while (TOGGLE_SPOILERS.consumeClick()) ClientPlayNetworking.send(new ToggleSpoilersPayload());

            int controls = 0;
            if (client.options.keyUp.isDown()) controls |= FlightControls.FORWARD;
            if (client.options.keyDown.isDown()) controls |= FlightControls.REVERSE;
            if (client.options.keyLeft.isDown()) controls |= FlightControls.LEFT;
            if (client.options.keyRight.isDown()) controls |= FlightControls.RIGHT;
            if (BRAKES.isDown()) controls |= FlightControls.BRAKE;
            if (controls != lastSentControls) {
                ClientPlayNetworking.send(new FlightInputPayload(controls));
                lastSentControls = controls;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            flightEnabled = false;
            updateViewBobbing(client);
            bankDegrees = 0.0f;
            airborne = false;
            enginesActive = false;
            lastSentControls = -1;
            if (engineSound != null) {
                engineSound.stopLoop();
                engineSound = null;
            }
            FlightHud.clear();
            if (client.player != null) client.player.setNoGravity(false);
        });
    }

    private void updateViewBobbing(Minecraft client) {
        if (flightEnabled) {
            if (savedViewBobbing == null) savedViewBobbing = client.options.bobView().get();
            client.options.bobView().set(false);
        } else if (savedViewBobbing != null) {
            client.options.bobView().set(savedViewBobbing);
            savedViewBobbing = null;
        }
    }

    private void updateEngineSound(Minecraft client) {
        boolean shouldPlay = flightEnabled && enginesActive && client.player != null && client.level != null;
        if (!shouldPlay) {
            if (engineSound != null) {
                engineSound.stopLoop();
                engineSound = null;
            }
            return;
        }
        if (engineSound == null || engineSound.isStopped()) {
            engineSound = new FlightEngineSound();
            client.getSoundManager().queueTickingSound(engineSound);
        }
    }

    private void migrateLegacyBindings() {
        if (bindingsMigrated) return;
        bindingsMigrated = true;
        migrateBinding(START_ENGINES, "key.keyboard.j", "key.keyboard.e", "key.keyboard.f8");
        migrateBinding(TOGGLE_PUSHBACK, "key.keyboard.k", "key.keyboard.p", "key.keyboard.f9");
        migrateBinding(BRAKES, "key.keyboard.m", "key.keyboard.b", "key.keyboard.f10");
        migrateBinding(TOGGLE_GEAR, "key.keyboard.u", "key.keyboard.h", "key.keyboard.f12");
        migrateBinding(FLAPS_UP, "key.keyboard.x", "key.keyboard.slash", "key.keyboard.keypad.5", "key.keyboard.right.bracket");
        migrateBinding(FLAPS_DOWN, "key.keyboard.z", "key.keyboard.keypad.3", "key.keyboard.left.bracket");
    }

    private static void migrateBinding(KeyMapping mapping, String target, String... oldKeys) {
        for (String oldKey : oldKeys) {
            if (mapping.saveString().equals(oldKey)) {
                mapping.setKey(InputConstants.getKey(target));
                return;
            }
        }
    }

    public static float bankDegrees() { return bankDegrees; }
    public static boolean isAirborne() { return airborne; }
    public static boolean areEnginesActive() { return enginesActive; }
}
