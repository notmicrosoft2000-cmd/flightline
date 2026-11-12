package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flightline.FlightLine;
import dev.flightline.flight.FlightControls;
import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightWarning;
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
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;

public class FlightLineClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(FlightLine.id("controls"));
    private static final KeyMapping TOGGLE_GEAR = key("key.flightline.toggle_gear", 71);
    private static final KeyMapping START_ENGINES = key("key.flightline.start_engines", 74);
    private static final KeyMapping SHUTDOWN_ENGINES = key("key.flightline.shutdown_engines", 76);
    private static final KeyMapping TOGGLE_PUSHBACK = key("key.flightline.toggle_pushback", 75);
    private static final KeyMapping BRAKES = key("key.flightline.brakes", 77);
    private static final KeyMapping FLAPS_UP = key("key.flightline.flaps_up", 67);
    private static final KeyMapping FLAPS_DOWN = key("key.flightline.flaps_down", 86);
    private static final KeyMapping SPOILERS_EXTEND = key("key.flightline.spoilers_extend", 88);
    private static final KeyMapping SPOILERS_RETRACT = key("key.flightline.spoilers_retract", 90);
    private static final KeyMapping TOGGLE_AUTOPILOT = key("key.flightline.autopilot", 72);
    private static volatile boolean flightEnabled;
    private boolean savedViewBobbing;
    private boolean hasSavedViewBobbing;
    private static volatile float bankDegrees;
    private static volatile boolean airborne;
    private static volatile boolean enginesActive;
    private int lastSentControls = -1;
    private boolean bindingsMigrated;
    private boolean gearWasDown;
    private boolean startEnginesWasDown;
    private boolean shutdownEnginesWasDown;
    private boolean pushbackWasDown;
    private boolean flapsUpWasDown;
    private boolean flapsDownWasDown;
    private boolean spoilersExtendWasDown;
    private boolean spoilersRetractWasDown;
    private static int lastWarningCallout;
    private static int warningRepeatCooldown;
    private static int activeAlertId;
    private static final Queue<QueuedVoice> VOICE_QUEUE = new ArrayDeque<>();
    private static @Nullable SoundInstance activeVoice;
    private static boolean groundGearWarningWasActive;
    private static int previousRadioAltitude = -1;
    private static int armedCallouts = 0;
    private static boolean retardFired;
    private @Nullable FlightEngineSound engineSound;

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
            FlightCameraEffects.updateFlightState(FlightPhase.fromId(payload.phase()));
            Minecraft client = context.client();
            updateViewBobbing(client);
            var player = client.player;
            if (player != null && payload.enabled()) {
                player.setNoGravity(payload.phase() == 4 || payload.phase() == 5);
                player.setDeltaMovement(payload.velocityX(), payload.velocityY(), payload.velocityZ());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(FlightInstrumentPayload.TYPE, (payload, context) -> {
            bankDegrees = payload.bankDegrees();
            var player = context.client().player;
            if (player != null && flightEnabled) {
                player.setYRot(payload.headingDegrees());
                player.setYHeadRot(payload.headingDegrees());
            }
            int engine1 = payload.systems() & 7;
            int engine2 = (payload.systems() >> 3) & 7;
            enginesActive = engine1 == 1 || engine1 == 2 || engine2 == 1 || engine2 == 2;
            FlightHud.updateInstruments(payload);
            FlightCameraEffects.updateInstruments(payload.bankDegrees(), payload.warnings(),
                    payload.radioAltitudeFeet(), payload.verticalSpeedFpm(), (payload.systems() >> 19) & 0x7F);
            playCockpitAlerts(context.client(), payload.warnings(), payload.radioAltitudeFeet(), payload.verticalSpeedFpm());
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                FlightLine.id("flight_status"), FlightHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            migrateLegacyBindings();
            updateViewBobbing(client);
            updateEngineSound(client);
            var player = client.player;
            if (player != null && flightEnabled && client.gui.screen() == null
                    && client.options.keyInventory.consumeClick()) {
                client.gui.setScreen(new InventoryScreen(player));
            }
            boolean apClick = TOGGLE_AUTOPILOT.consumeClick();
            if (apClick && player != null && client.level != null && client.gui.screen() == null) {
                client.gui.setScreen(new AutopilotScreen(
                        FlightHud.isAutopilotEngaged(),
                        Math.round(FlightHud.currentHeading()),
                        FlightHud.currentAltitudeFeet(),
                        FlightHud.currentVsFpm(),
                        FlightHud.currentSpeedKnots()
                ));
            }
            if (player == null || client.level == null || !flightEnabled) return;
            if (client.gui.screen() != null) {
                discardFlightInput();
                if (lastSentControls != 0) {
                    ClientPlayNetworking.send(new FlightInputPayload(0));
                    lastSentControls = 0;
                }
                return;
            }
            boolean gearClick = TOGGLE_GEAR.consumeClick() || (TOGGLE_GEAR.isDown() && !gearWasDown);
            gearWasDown = TOGGLE_GEAR.isDown();
            if (gearClick) ClientPlayNetworking.send(new ToggleGearPayload());

            boolean startEnginesClick = START_ENGINES.consumeClick() || (START_ENGINES.isDown() && !startEnginesWasDown);
            startEnginesWasDown = START_ENGINES.isDown();
            if (startEnginesClick) ClientPlayNetworking.send(new StartEnginesPayload());

            boolean shutdownEnginesClick = SHUTDOWN_ENGINES.consumeClick() || (SHUTDOWN_ENGINES.isDown() && !shutdownEnginesWasDown);
            shutdownEnginesWasDown = SHUTDOWN_ENGINES.isDown();
            if (shutdownEnginesClick) ClientPlayNetworking.send(new ShutdownEnginesPayload());

            boolean pushbackClick = TOGGLE_PUSHBACK.consumeClick() || (TOGGLE_PUSHBACK.isDown() && !pushbackWasDown);
            pushbackWasDown = TOGGLE_PUSHBACK.isDown();
            if (pushbackClick) ClientPlayNetworking.send(new TogglePushbackPayload());

            boolean flapsUpClick = FLAPS_UP.consumeClick() || (FLAPS_UP.isDown() && !flapsUpWasDown);
            flapsUpWasDown = FLAPS_UP.isDown();
            if (flapsUpClick) ClientPlayNetworking.send(new AdjustFlapsPayload(1));

            boolean flapsDownClick = FLAPS_DOWN.consumeClick() || (FLAPS_DOWN.isDown() && !flapsDownWasDown);
            flapsDownWasDown = FLAPS_DOWN.isDown();
            if (flapsDownClick) ClientPlayNetworking.send(new AdjustFlapsPayload(-1));

            boolean spoilersExtendClick = SPOILERS_EXTEND.consumeClick() || (SPOILERS_EXTEND.isDown() && !spoilersExtendWasDown);
            spoilersExtendWasDown = SPOILERS_EXTEND.isDown();
            if (spoilersExtendClick) ClientPlayNetworking.send(new ToggleSpoilersPayload(true));

            boolean spoilersRetractClick = SPOILERS_RETRACT.consumeClick() || (SPOILERS_RETRACT.isDown() && !spoilersRetractWasDown);
            spoilersRetractWasDown = SPOILERS_RETRACT.isDown();
            if (spoilersRetractClick) ClientPlayNetworking.send(new ToggleSpoilersPayload(false));

            if (client.gui.screen() != null) {
                discardFlightInput();
                if (lastSentControls != 0) {
                    ClientPlayNetworking.send(new FlightInputPayload(0));
                    lastSentControls = 0;
                }
                return;
            }

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
            FlightCameraEffects.updateFlightState(FlightPhase.OFF);
            FlightCameraEffects.updateInstruments(0.0f, 0, 0, 0, 0);
            enginesActive = false;
            lastWarningCallout = 0;
            warningRepeatCooldown = 0;
            activeAlertId = 0;
            VOICE_QUEUE.clear();
            if (activeVoice != null) {
                client.getSoundManager().stop(activeVoice);
                activeVoice = null;
            }
            groundGearWarningWasActive = false;
            previousRadioAltitude = -1;
            armedCallouts = 0;
            retardFired = false;
            warningRepeatCooldown = 0;
            lastSentControls = -1;
            if (engineSound != null) {
                engineSound.stopLoop();
                engineSound = null;
            }
            FlightHud.clear();
            if (client.player != null) client.player.setNoGravity(false);
        });
    }

    private static void playCockpitAlerts(Minecraft client, int warnings, int radioAlt, int verticalSpeed) {
        int alertId = 0;
        SoundEvent sound = null;
        int repeatInterval = 40;

        if ((warnings & FlightWarning.WINDSHEAR) != 0) {
            alertId = 10;
            sound = FlightLine.GPWS_WINDSHEAR;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.PULL_UP) != 0) {
            alertId = 11;
            sound = FlightLine.GPWS_PULL_UP;
            repeatInterval = 30;
        } else if ((warnings & FlightWarning.TERRAIN) != 0) {
            alertId = 12;
            sound = FlightLine.GPWS_TERRAIN;
            repeatInterval = 35;
        } else if ((warnings & FlightWarning.TCAS_RESOLUTION) != 0) {
            boolean climb = (warnings & FlightWarning.TCAS_CLIMB) != 0;
            boolean crossing = (warnings & FlightWarning.TCAS_CROSSING) != 0;
            alertId = climb ? (crossing ? 3 : 2) : (crossing ? 5 : 4);
            sound = switch (alertId) {
                case 2 -> FlightLine.TCAS_CLIMB;
                case 3 -> FlightLine.TCAS_CLIMB_CROSSING;
                case 4 -> FlightLine.TCAS_DESCEND;
                default -> FlightLine.TCAS_DESCEND_CROSSING;
            };
            repeatInterval = 50;
        } else if ((warnings & FlightWarning.TCAS_CLEAR) != 0) {
            alertId = 6;
            sound = FlightLine.TCAS_CLEAR;
            repeatInterval = 200;
        } else if ((warnings & FlightWarning.SINK_RATE) != 0) {
            alertId = 13;
            sound = FlightLine.GPWS_SINK_RATE;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.DONT_SINK) != 0) {
            alertId = 14;
            sound = FlightLine.GPWS_DONT_SINK;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.TOO_LOW_TERRAIN) != 0) {
            alertId = 15;
            sound = FlightLine.GPWS_TOO_LOW_TERRAIN;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.GEAR_UP) != 0) {
            alertId = 16;
            sound = FlightLine.GPWS_TOO_LOW_GEAR;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.TOO_LOW_FLAPS) != 0) {
            alertId = 17;
            sound = FlightLine.GPWS_TOO_LOW_FLAPS;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) {
            alertId = 1;
            sound = FlightLine.TCAS_TRAFFIC;
            repeatInterval = 0;
        } else if ((warnings & FlightWarning.GLIDESLOPE_LOW) != 0) {
            alertId = 18;
            sound = FlightLine.GPWS_GLIDESLOPE;
            repeatInterval = 50;
        } else if ((warnings & FlightWarning.BANK_ANGLE) != 0) {
            alertId = 19;
            sound = FlightLine.GPWS_BANK_ANGLE;
            repeatInterval = 45;
        }

        activeAlertId = alertId;
        if (alertId == 0) {
            lastWarningCallout = 0;
            warningRepeatCooldown = 0;
        } else if (alertId != lastWarningCallout) {
            lastWarningCallout = alertId;
            enqueueVoice(sound, alertId);
            warningRepeatCooldown = repeatInterval;
        } else if (alertId != 1 && --warningRepeatCooldown <= 0) {
            if (activeVoice == null && VOICE_QUEUE.isEmpty()) {
                enqueueVoice(sound, alertId);
                warningRepeatCooldown = repeatInterval;
            } else {
                warningRepeatCooldown = 10;
            }
        }

        boolean groundGearWarningActive = (warnings & FlightWarning.GEAR_SELECTED_ON_GROUND) != 0;
        if (groundGearWarningActive && !groundGearWarningWasActive) {
            enqueueVoice(FlightLine.GPWS_TOO_LOW_GEAR, 0);
        }
        groundGearWarningWasActive = groundGearWarningActive;

        // Radio Altimeter Callouts during descent
        if (airborne && verticalSpeed < -40) {
            if (previousRadioAltitude >= 0) {
                checkAltimeterCallout(2500, 1 << 7, FlightLine.CALLOUT_2500, radioAlt);
                checkAltimeterCallout(1000, 1 << 8, FlightLine.CALLOUT_1000, radioAlt);
                checkAltimeterCallout(500, 1 << 0, FlightLine.CALLOUT_500, radioAlt);
                checkAltimeterCallout(100, 1 << 1, FlightLine.CALLOUT_100, radioAlt);
                checkAltimeterCallout(50, 1 << 2, FlightLine.CALLOUT_50, radioAlt);
                checkAltimeterCallout(40, 1 << 3, FlightLine.CALLOUT_40, radioAlt);
                checkAltimeterCallout(30, 1 << 4, FlightLine.CALLOUT_30, radioAlt);
                checkAltimeterCallout(20, 1 << 5, FlightLine.CALLOUT_20, radioAlt);
                checkAltimeterCallout(10, 1 << 6, FlightLine.CALLOUT_10, radioAlt);
            }

            if (radioAlt <= 20 && radioAlt > 2 && FlightHud.throttle() > 0.05f && !retardFired) {
                retardFired = true;
                enqueueVoice(FlightLine.CALLOUT_RETARD, 0);
            }
        }
        if (radioAlt > 50) {
            retardFired = false;
        }
        previousRadioAltitude = radioAlt;
        startNextVoice(client);
    }

    private void discardFlightInput() {
        while (TOGGLE_GEAR.consumeClick()) { }
        while (START_ENGINES.consumeClick()) { }
        while (SHUTDOWN_ENGINES.consumeClick()) { }
        while (TOGGLE_PUSHBACK.consumeClick()) { }
        while (FLAPS_UP.consumeClick()) { }
        while (FLAPS_DOWN.consumeClick()) { }
        while (SPOILERS_EXTEND.consumeClick()) { }
        while (SPOILERS_RETRACT.consumeClick()) { }
        while (TOGGLE_AUTOPILOT.consumeClick()) { }
        gearWasDown = TOGGLE_GEAR.isDown();
        startEnginesWasDown = START_ENGINES.isDown();
        shutdownEnginesWasDown = SHUTDOWN_ENGINES.isDown();
        pushbackWasDown = TOGGLE_PUSHBACK.isDown();
        flapsUpWasDown = FLAPS_UP.isDown();
        flapsDownWasDown = FLAPS_DOWN.isDown();
        spoilersExtendWasDown = SPOILERS_EXTEND.isDown();
        spoilersRetractWasDown = SPOILERS_RETRACT.isDown();
    }

    private static void checkAltimeterCallout(int threshold, int mask, SoundEvent sound, int currentAlt) {
        if (currentAlt > threshold + 50) {
            armedCallouts |= mask;
        } else if ((armedCallouts & mask) != 0 && previousRadioAltitude >= threshold && currentAlt < threshold) {
            armedCallouts &= ~mask;
            enqueueVoice(sound, 0);
        }
    }

    private static void enqueueVoice(@Nullable SoundEvent sound, int warningId) {
        if (sound != null) VOICE_QUEUE.add(new QueuedVoice(sound, warningId));
    }

    private static void startNextVoice(Minecraft client) {
        var soundManager = client.getSoundManager();
        if (activeVoice != null && soundManager.isActive(activeVoice)) return;
        activeVoice = null;
        while (!VOICE_QUEUE.isEmpty()) {
            QueuedVoice next = VOICE_QUEUE.remove();
            if (next.warningId() != 0 && next.warningId() != activeAlertId) continue;
            SoundInstance instance = SimpleSoundInstance.forUI(next.sound(), 1.0f, 1.0f);
            soundManager.play(instance);
            activeVoice = instance;
            return;
        }
    }

    private record QueuedVoice(SoundEvent sound, int warningId) {
    }

    private void updateViewBobbing(Minecraft client) {
        if (flightEnabled) {
            if (!hasSavedViewBobbing) {
                savedViewBobbing = client.options.bobView().get();
                hasSavedViewBobbing = true;
            }
            client.options.bobView().set(false);
        } else if (hasSavedViewBobbing) {
            client.options.bobView().set(savedViewBobbing);
            hasSavedViewBobbing = false;
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
            FlightEngineSound sound = new FlightEngineSound();
            engineSound = sound;
            client.getSoundManager().queueTickingSound(sound);
        }
    }

    private void migrateLegacyBindings() {
        if (bindingsMigrated) return;
        bindingsMigrated = true;
        boolean changed = false;
        changed |= migrateBinding(START_ENGINES, "key.keyboard.j", "key.keyboard.e", "key.keyboard.f8");
        changed |= migrateBinding(TOGGLE_PUSHBACK, "key.keyboard.k", "key.keyboard.p", "key.keyboard.f9");
        changed |= migrateBinding(BRAKES, "key.keyboard.m", "key.keyboard.b", "key.keyboard.f10");
        changed |= migrateBinding(TOGGLE_GEAR, "key.keyboard.g", "key.keyboard.u", "key.keyboard.h");
        changed |= migrateBinding(FLAPS_UP, "key.keyboard.c", "key.keyboard.x", "key.keyboard.slash", "key.keyboard.keypad.5", "key.keyboard.right.bracket");
        changed |= migrateBinding(FLAPS_DOWN, "key.keyboard.v", "key.keyboard.z", "key.keyboard.keypad.3", "key.keyboard.left.bracket");
        changed |= migrateBinding(SPOILERS_EXTEND, "key.keyboard.x", "key.keyboard.p", "key.keyboard.o");
        changed |= migrateBinding(SPOILERS_RETRACT, "key.keyboard.z");
        if (changed) KeyMapping.resetMapping();
    }

    private static boolean migrateBinding(KeyMapping mapping, String target, String... oldKeys) {
        for (String oldKey : oldKeys) {
            if (mapping.saveString().equals(oldKey)) {
                mapping.setKey(InputConstants.getKey(target));
                return true;
            }
        }
        return false;
    }

    public static float bankDegrees() { return bankDegrees; }
    public static boolean isAirborne() { return airborne; }
    public static boolean areEnginesActive() { return enginesActive; }

    static KeyMapping toggleGear() { return TOGGLE_GEAR; }
    static KeyMapping toggleAutopilot() { return TOGGLE_AUTOPILOT; }
    static KeyMapping startEngines() { return START_ENGINES; }
    static KeyMapping shutdownEngines() { return SHUTDOWN_ENGINES; }
    static KeyMapping flapsUp() { return FLAPS_UP; }
    static KeyMapping flapsDown() { return FLAPS_DOWN; }
    static KeyMapping spoilersExtend() { return SPOILERS_EXTEND; }
    static KeyMapping spoilersRetract() { return SPOILERS_RETRACT; }
    static KeyMapping pushback() { return TOGGLE_PUSHBACK; }
    static KeyMapping brakes() { return BRAKES; }
    static KeyMapping inventory() { return Minecraft.getInstance().options.keyInventory; }

    static String keyName(KeyMapping mapping) {
        return java.util.Objects.requireNonNull(mapping.getTranslatedKeyMessage().getString());
    }
}
