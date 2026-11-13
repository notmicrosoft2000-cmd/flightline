package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flightline.FlightLine;
import dev.flightline.flight.FlightControls;
import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightWarning;
import dev.flightline.network.AdjustFlapsPayload;
import dev.flightline.network.FlightInputPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightRadarPayload;
import dev.flightline.network.FlightStatePayload;
import dev.flightline.network.StartEnginesPayload;
import dev.flightline.network.ShutdownEnginesPayload;
import dev.flightline.network.ToggleGearPayload;
import dev.flightline.network.TogglePushbackPayload;
import dev.flightline.network.ToggleSpoilersPayload;
import dev.flightline.network.ToggleAntiIcePayload;
import dev.flightline.network.AtcOrderPayload;
import dev.flightline.network.AtcRadioPayload;
import dev.flightline.network.AtcAckPayload;
import dev.flightline.network.OpenAtcPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

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
    private static final KeyMapping OPEN_FLIGHT_RADAR = key("key.flightline.flight_radar", 82);
    private static final KeyMapping TOGGLE_PFD = key("key.flightline.toggle_pfd", 85);
    private static final KeyMapping TOGGLE_EICAS = key("key.flightline.toggle_eicas", 73);
    private static final KeyMapping TOGGLE_ND_RADAR = key("key.flightline.toggle_nd_radar", 66);
    private static final KeyMapping TOGGLE_RADIO_ALTIMETER = key("key.flightline.toggle_radio_altimeter", 78);
    private static final KeyMapping TOGGLE_KEYBIND_HINTS = key("key.flightline.toggle_keybind_hints", 298);
    private static final KeyMapping OPEN_CONTROLS = key("key.flightline.open_controls", 299);
    private static final KeyMapping ACK_ATC = key("key.flightline.ack_atc", 296);
    private static final KeyMapping TOGGLE_ANTI_ICE = key("key.flightline.toggle_anti_ice", 84);
    private static final KeyMapping BANK_LEFT = key("key.flightline.bank_left", 65);
    private static final KeyMapping BANK_RIGHT = key("key.flightline.bank_right", 68);
    private static final List<KeyMapping> FLIGHTLINE_BINDINGS = List.of(
            TOGGLE_GEAR, START_ENGINES, SHUTDOWN_ENGINES, TOGGLE_PUSHBACK, BRAKES,
            FLAPS_UP, FLAPS_DOWN, SPOILERS_EXTEND, SPOILERS_RETRACT, TOGGLE_AUTOPILOT,
            OPEN_FLIGHT_RADAR, TOGGLE_PFD, TOGGLE_EICAS, TOGGLE_ND_RADAR,
            TOGGLE_RADIO_ALTIMETER, TOGGLE_KEYBIND_HINTS, OPEN_CONTROLS, ACK_ATC, TOGGLE_ANTI_ICE,
            BANK_LEFT, BANK_RIGHT);
    private static final List<String> LAPTOP_KEY_CANDIDATES = List.of(
            "q", "e", "r", "t", "y", "u", "i", "o", "p",
            "a", "s", "d", "f", "g", "h", "j", "k", "l",
            "z", "x", "c", "v", "b", "n", "m", "w");
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
    private static final PriorityQueue<QueuedVoice> VOICE_QUEUE = new PriorityQueue<>(
            Comparator.comparingInt(QueuedVoice::priority).reversed()
                    .thenComparingLong(QueuedVoice::sequence));
    private static @Nullable SoundInstance activeVoice;
    private static int activeVoicePriority = -1;
    private static int activeVoiceWarningId;
    private static long nextVoiceSequence;
    private static boolean groundGearWarningWasActive;
    private static int previousRadioAltitude = -1;
    private static int armedCallouts = 0;
    private static boolean retardFired;
    private static boolean approachingMinimumsFired;
    private static boolean minimumsFired;
    private static int previousAircraftAltitudeFeet = -1;
    private static int lastVerticalVoiceId;
    private static int verticalVoiceCooldown;
    private @Nullable FlightEngineSound engineSound;
    private boolean engineSoundCruise;
    private static int cameraFollowEntityId = -1;
    private static int atcHighlightedEntityId = -1;
    private static @Nullable Entity atcHighlightedEntity;
    private static boolean atcHighlightOriginalGlow;

    private static KeyMapping key(String translation, int keyCode) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translation,
                InputConstants.Type.KEYBOARD, keyCode, CATEGORY));
    }

    public static boolean isFlightEnabled() { return flightEnabled; }
    public static KeyMapping flightRadar() { return OPEN_FLIGHT_RADAR; }
    public static KeyMapping togglePfd() { return TOGGLE_PFD; }
    public static KeyMapping toggleEicas() { return TOGGLE_EICAS; }
    public static KeyMapping toggleNdRadar() { return TOGGLE_ND_RADAR; }
    public static KeyMapping toggleRadioAltimeter() { return TOGGLE_RADIO_ALTIMETER; }
    public static KeyMapping toggleKeybindHints() { return TOGGLE_KEYBIND_HINTS; }
    public static KeyMapping openControls() { return OPEN_CONTROLS; }

    public static void playTcasTest(Minecraft client) {
        SoundEvent result = FlightHud.hasRadarData() ? FlightLine.TCAS_TEST_PASS : FlightLine.TCAS_TEST_FAIL;
        client.getSoundManager().play(SimpleSoundInstance.forUI(result, 1.6f, 1.0f));
    }

    @Override
    public void onInitializeClient() {
        FlightHud.loadPreferences();
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
                    payload.radioAltitudeFeet(), payload.verticalSpeedFpm(), (payload.systems() >> 19) & 0x7F,
                    payload.cockpitGuidance().weatherGuidance().turbulencePercent());
            playCockpitAlerts(context.client(), payload.warnings(), payload.radioAltitudeFeet(), payload.verticalSpeedFpm());
        });
        ClientPlayNetworking.registerGlobalReceiver(FlightRadarPayload.TYPE, (payload, context) ->
                FlightHud.updateRadar(payload));
        ClientPlayNetworking.registerGlobalReceiver(AtcRadioPayload.TYPE, (payload, context) -> {
            FlightHud.showAtcMessage(payload.message(), payload.senderEntityId());
            context.client().getSoundManager().play(SimpleSoundInstance.forUI(
                    FlightLine.CALLOUT_DING, 1.3f, 1.0f));
        });
        ClientPlayNetworking.registerGlobalReceiver(OpenAtcPayload.TYPE, (payload, context) ->
                context.client().execute(() -> context.client().gui.setScreen(new AtcScreen())));
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
            if (client.gui.screen() == null) {
                if (ACK_ATC.consumeClick() && player != null && FlightHud.hasAtcMessage()) {
                    int controllerId = FlightHud.atcControllerEntityId();
                    if (controllerId >= 0) {
                        ClientPlayNetworking.send(new AtcAckPayload(controllerId));
                        FlightHud.showAtcMessage("ACK SENT TO ATC", -1);
                    }
                }
                if (TOGGLE_PFD.consumeClick()) FlightHud.togglePfd();
                if (TOGGLE_EICAS.consumeClick()) FlightHud.toggleEicas();
                if (TOGGLE_ND_RADAR.consumeClick()) FlightHud.toggleRadar();
                if (TOGGLE_RADIO_ALTIMETER.consumeClick()) FlightHud.toggleRadioAltimeter();
                if (TOGGLE_KEYBIND_HINTS.consumeClick()) FlightHud.toggleKeybinds();
                if (OPEN_CONTROLS.consumeClick()) client.gui.setScreen(new FlightControlsScreen());
                if (TOGGLE_ANTI_ICE.consumeClick() && player != null && flightEnabled) {
                    ClientPlayNetworking.send(new ToggleAntiIcePayload(!FlightHud.weatherGuidance().antiIceActive()));
                }
                if (OPEN_FLIGHT_RADAR.consumeClick() && flightEnabled
                        && player != null && client.level != null) {
                    client.gui.setScreen(new FlightRadarScreen());
                }
            }
            updateAtcCameraFollow(client);
            if (player != null && FlightHud.isAutopilotEngaged()) {
                player.setYRot(FlightHud.currentHeading());
                player.setYHeadRot(FlightHud.currentHeading());
                player.setXRot(-FlightHud.currentPitch());
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
            if (BANK_LEFT.isDown()) controls |= FlightControls.LEFT;
            if (BANK_RIGHT.isDown()) controls |= FlightControls.RIGHT;
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
            FlightCameraEffects.updateInstruments(0.0f, 0, 0, 0, 0, 0);
            enginesActive = false;
            lastWarningCallout = 0;
            warningRepeatCooldown = 0;
            activeAlertId = 0;
            VOICE_QUEUE.clear();
            if (activeVoice != null) {
                client.getSoundManager().stop(activeVoice);
                activeVoice = null;
            }
            activeVoicePriority = -1;
            activeVoiceWarningId = 0;
            nextVoiceSequence = 0;
            groundGearWarningWasActive = false;
            previousRadioAltitude = -1;
            armedCallouts = 0;
            retardFired = false;
            approachingMinimumsFired = false;
            minimumsFired = false;
            previousAircraftAltitudeFeet = -1;
            lastVerticalVoiceId = 0;
            verticalVoiceCooldown = 0;
            warningRepeatCooldown = 0;
            lastSentControls = -1;
            if (engineSound != null) {
                engineSound.stopLoop();
                engineSound = null;
            }
            FlightHud.clear();
            stopAtcCameraFollow(client);
            clearAtcHighlight();
            if (client.player != null) client.player.setNoGravity(false);
        });
    }

    private static void playCockpitAlerts(Minecraft client, int warnings, int radioAlt, int verticalSpeed) {
        int alertId = 0;
        SoundEvent sound = null;
        SoundEvent[] phrase = null;
        boolean cautionPreamble = false;
        int repeatInterval = 40;

        if ((warnings & FlightWarning.WINDSHEAR) != 0) {
            alertId = 10;
            sound = FlightLine.GPWS_WINDSHEAR;
            cautionPreamble = true;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.PULL_UP) != 0) {
            alertId = 11;
            phrase = new SoundEvent[]{FlightLine.GPWS_TERRAIN, FlightLine.GPWS_TERRAIN,
                    FlightLine.GPWS_PULL_UP};
            cautionPreamble = true;
            repeatInterval = 30;
        } else if ((warnings & FlightWarning.TERRAIN) != 0) {
            alertId = 12;
            phrase = new SoundEvent[]{FlightLine.GPWS_TERRAIN, FlightLine.GPWS_TERRAIN};
            cautionPreamble = true;
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
            cautionPreamble = true;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.DONT_SINK) != 0) {
            alertId = 14;
            sound = FlightLine.GPWS_DONT_SINK;
            cautionPreamble = true;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.TOO_LOW_TERRAIN) != 0) {
            alertId = 15;
            sound = FlightLine.GPWS_TOO_LOW_TERRAIN;
            cautionPreamble = true;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.GEAR_UP) != 0) {
            alertId = 16;
            sound = FlightLine.GPWS_TOO_LOW_GEAR;
            cautionPreamble = true;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.TOO_LOW_FLAPS) != 0) {
            alertId = 17;
            sound = FlightLine.GPWS_TOO_LOW_FLAPS;
            cautionPreamble = true;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) {
            alertId = 1;
            sound = FlightLine.TCAS_TRAFFIC;
            cautionPreamble = true;
            repeatInterval = 0;
        } else if ((warnings & (FlightWarning.GLIDESLOPE_LOW | FlightWarning.GLIDESLOPE_HIGH)) != 0) {
            alertId = 18;
            sound = FlightLine.GPWS_GLIDESLOPE;
            cautionPreamble = true;
            repeatInterval = 50;
        } else if ((warnings & FlightWarning.BANK_ANGLE) != 0) {
            alertId = 19;
            sound = FlightLine.GPWS_BANK_ANGLE;
            cautionPreamble = true;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.STALL) != 0) {
            alertId = 20;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.ENGINE_FIRE) != 0) {
            alertId = 21;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.ENGINE_FAILURE) != 0) {
            alertId = 22;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.FUEL_EMPTY) != 0) {
            alertId = 23;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 60;
        } else if ((warnings & FlightWarning.FUEL_LOW) != 0) {
            alertId = 24;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 60;
        } else if ((warnings & FlightWarning.GEAR_FAILED) != 0) {
            alertId = 25;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 60;
        } else if ((warnings & FlightWarning.GEAR_HOT) != 0) {
            alertId = 26;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 60;
        } else if ((warnings & FlightWarning.TAKEOFF_CONFIG) != 0) {
            alertId = 27;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 60;
        } else if ((warnings & FlightWarning.AIRFRAME_STRESS) != 0) {
            alertId = 29;
            sound = FlightLine.CAUTION_ALARM;
            cautionPreamble = true;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.OVERSPEED) != 0) {
            alertId = 28;
            sound = FlightLine.CAUTION_ALARM;
            repeatInterval = 45;
        }

        activeAlertId = alertId;
        if (alertId == 0) {
            lastWarningCallout = 0;
            warningRepeatCooldown = 0;
        } else if (alertId != lastWarningCallout) {
            lastWarningCallout = alertId;
            preemptLowerPriorityVoice(client, alertId, alertPriority(alertId));
            if (cautionPreamble) enqueueVoice(FlightLine.CAUTION_ALARM, alertId);
            if (phrase != null) enqueueVoiceSequence(alertId, phrase);
            else enqueueVoice(sound, alertId);
            warningRepeatCooldown = repeatInterval;
        } else if (alertId != 1 && --warningRepeatCooldown <= 0) {
            if (activeVoice == null && VOICE_QUEUE.isEmpty()) {
                if (phrase != null) enqueueVoiceSequence(alertId, phrase);
                else enqueueVoice(sound, alertId);
                warningRepeatCooldown = repeatInterval;
            } else {
                warningRepeatCooldown = 10;
            }
        }

        boolean groundGearWarningActive = (warnings & FlightWarning.GEAR_SELECTED_ON_GROUND) != 0;
        if (groundGearWarningActive && !groundGearWarningWasActive) {
            enqueueCalloutVoice(FlightLine.CAUTION_ALARM);
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
                enqueueCalloutVoice(FlightLine.CALLOUT_RETARD);
            }
            if (FlightHud.isIlsActive() && !approachingMinimumsFired
                    && previousRadioAltitude > 200 && radioAlt <= 200) {
                approachingMinimumsFired = true;
                enqueueCalloutVoice(FlightLine.VOICE_APPROACHING_MINIMUMS);
            }
            if (FlightHud.isIlsActive() && !minimumsFired
                    && previousRadioAltitude > 100 && radioAlt <= 100) {
                minimumsFired = true;
                enqueueCalloutVoice(FlightLine.VOICE_MINIMUMS);
            }
        }
        if (radioAlt > 50) {
            retardFired = false;
        }
        if (radioAlt > 250) {
            approachingMinimumsFired = false;
            minimumsFired = false;
        }
        playAltitudeAndVerticalGuidance(client, verticalSpeed);
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
            enqueueCalloutVoice(sound);
        }
    }

    private static void playAltitudeAndVerticalGuidance(Minecraft client, int verticalSpeed) {
        var player = client.player;
        if (player == null) return;
        int currentAltitudeFeet = (int) Math.round(player.getY() * 3.28084);
        var guidance = FlightHud.autopilotGuidance();
        if (FlightHud.isAutopilotEngaged()) {
            int plus100Altitude = guidance.targetAltitudeFeet() + 100;
            if (previousAircraftAltitudeFeet >= 0
                    && ((previousAircraftAltitudeFeet < plus100Altitude && currentAltitudeFeet >= plus100Altitude)
                    || (previousAircraftAltitudeFeet > plus100Altitude && currentAltitudeFeet <= plus100Altitude))) {
                enqueueCalloutVoice(FlightLine.CALLOUT_PLUS_100);
            }
            int error = guidance.targetVerticalSpeedFpm() - verticalSpeed;
            int voiceId;
            SoundEvent voice;
            if (Math.abs(error) > 800) {
                voiceId = 7;
                voice = FlightLine.VOICE_ADJUST_VERTICAL_SPEED;
            } else if (Math.abs(error) <= 150) {
                voiceId = 1;
                voice = FlightLine.VOICE_MAINTAIN_VERTICAL_SPEED;
            } else if (guidance.targetVerticalSpeedFpm() > 0) {
                voiceId = error > 0 ? 2 : 3;
                voice = error > 0 ? FlightLine.VOICE_INCREASE_CLIMB : FlightLine.VOICE_REDUCE_CLIMB;
            } else if (guidance.targetVerticalSpeedFpm() < 0) {
                voiceId = error > 0 ? 4 : 5;
                voice = error > 0 ? FlightLine.VOICE_INCREASE_DESCEND : FlightLine.VOICE_REDUCE_DESCEND;
            } else {
                voiceId = 6;
                voice = FlightLine.VOICE_MONITOR_VERTICAL_SPEED;
            }
            if (voiceId != lastVerticalVoiceId && verticalVoiceCooldown <= 0) {
                enqueueGuidanceVoice(voice);
                lastVerticalVoiceId = voiceId;
                verticalVoiceCooldown = 100;
            }
            if (verticalVoiceCooldown > 0) verticalVoiceCooldown--;
        } else {
            lastVerticalVoiceId = 0;
            verticalVoiceCooldown = 0;
        }
        previousAircraftAltitudeFeet = currentAltitudeFeet;
    }

    private static void enqueueGuidanceVoice(SoundEvent sound) {
        enqueueVoice(sound, 0);
    }

    private static void enqueueVoice(@Nullable SoundEvent sound, int warningId) {
        if (sound != null) {
            VOICE_QUEUE.add(new QueuedVoice(sound, warningId,
                    warningId == 0 ? 0 : alertPriority(warningId), nextVoiceSequence++));
        }
    }

    private static void enqueueCalloutVoice(SoundEvent sound) {
        VOICE_QUEUE.add(new QueuedVoice(sound, 0, 2, nextVoiceSequence++));
    }

    private static int alertPriority(int warningId) {
        return switch (warningId) {
            case 2, 3, 4, 5, 10, 11, 12, 13, 15, 16, 17, 29 -> 4;
            default -> 3;
        };
    }

    private static void preemptLowerPriorityVoice(Minecraft client, int warningId, int priority) {
        VOICE_QUEUE.removeIf(voice -> voice.priority() < priority
                || voice.warningId() != 0 && voice.warningId() != warningId);
        if (activeVoice != null && activeVoiceWarningId != warningId
                && activeVoicePriority <= priority) {
            client.getSoundManager().stop(activeVoice);
            activeVoice = null;
            activeVoicePriority = -1;
            activeVoiceWarningId = 0;
        }
    }

    private static void enqueueVoiceSequence(int warningId, SoundEvent... sounds) {
        for (SoundEvent sound : sounds) enqueueVoice(sound, warningId);
    }

    private static void startNextVoice(Minecraft client) {
        var soundManager = client.getSoundManager();
        if (activeVoice != null && soundManager.isActive(activeVoice)) return;
        activeVoice = null;
        activeVoicePriority = -1;
        activeVoiceWarningId = 0;
        while (!VOICE_QUEUE.isEmpty()) {
            QueuedVoice next = VOICE_QUEUE.remove();
            if (next.warningId() != 0 && next.warningId() != activeAlertId) continue;
            SoundInstance instance = SimpleSoundInstance.forUI(next.sound(), 1.6f, 1.0f);
            soundManager.play(instance);
            activeVoice = instance;
            activeVoicePriority = next.priority();
            activeVoiceWarningId = next.warningId();
            return;
        }
    }

    private record QueuedVoice(SoundEvent sound, int warningId, int priority, long sequence) {
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
        boolean cruise = airborne;
        if (engineSound != null && engineSoundCruise != cruise) {
            engineSound.stopLoop();
            engineSound = null;
        }
        if (engineSound == null || engineSound.isStopped()) {
            FlightEngineSound sound = new FlightEngineSound(cruise);
            engineSound = sound;
            engineSoundCruise = cruise;
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
        changed |= migrateBinding(TOGGLE_PFD, "key.keyboard.u", "key.keyboard.f6");
        changed |= migrateBinding(TOGGLE_EICAS, "key.keyboard.i", "key.keyboard.f7");
        changed |= migrateBinding(TOGGLE_ND_RADAR, "key.keyboard.b", "key.keyboard.f8");
        changed |= migrateBinding(TOGGLE_RADIO_ALTIMETER, "key.keyboard.n", "key.keyboard.f9");
        changed |= migrateBinding(TOGGLE_KEYBIND_HINTS, "key.keyboard.f9", "key.keyboard.o", "key.keyboard.f10");
        changed |= assignFreeDefaultBindings(Minecraft.getInstance());
        if (changed) KeyMapping.resetMapping();
    }

    private static boolean assignFreeDefaultBindings(Minecraft client) {
        Set<KeyMapping> flightlineBindings = Set.copyOf(FLIGHTLINE_BINDINGS);
        List<KeyMapping> occupied = new ArrayList<>();
        for (KeyMapping mapping : client.options.keyMappings) {
            if (!flightlineBindings.contains(mapping) && !mapping.isUnbound()) occupied.add(mapping);
        }

        boolean changed = false;
        for (KeyMapping mapping : FLIGHTLINE_BINDINGS) {
            boolean conflict = occupied.stream().anyMatch(other -> mapping.same(other)
                    && !isDefaultBankKeyOverlap(mapping, other, client));
            if (conflict && mapping.isDefault()) {
                boolean assigned = false;
                for (String candidateName : LAPTOP_KEY_CANDIDATES) {
                    InputConstants.Key candidate = InputConstants.getKey("key.keyboard." + candidateName);
                    if (occupied.stream().noneMatch(other -> other.matches(candidate))) {
                        mapping.setKey(candidate);
                        FlightLine.LOGGER.info("Moved conflicting Flight Line keybind {} to {}",
                                mapping.getName(), candidateName.toUpperCase(java.util.Locale.ROOT));
                        changed = true;
                        assigned = true;
                        break;
                    }
                }
                if (!assigned) {
                    FlightLine.LOGGER.warn("No free laptop-friendly key found for Flight Line binding {}",
                            mapping.getName());
                }
            }
            if (!mapping.isUnbound()) occupied.add(mapping);
        }
        return changed;
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

    private static boolean isDefaultBankKeyOverlap(KeyMapping mapping, KeyMapping occupied,
                                                   Minecraft client) {
        return (mapping == BANK_LEFT && occupied == client.options.keyLeft)
                || (mapping == BANK_RIGHT && occupied == client.options.keyRight);
    }

    private static void updateAtcCameraFollow(Minecraft client) {
        updateAtcHighlight(client);
        if (cameraFollowEntityId < 0 || client.level == null || client.player == null) return;
        Entity target = client.level.getEntity(cameraFollowEntityId);
        if (target == null || !target.isAlive()) {
            stopAtcCameraFollow(client);
            return;
        }
        if (client.getCameraEntity() != target) client.setCameraEntity(target);
    }

    private static void updateAtcHighlight(Minecraft client) {
        if (atcHighlightedEntityId < 0 || client.level == null) return;
        Entity target = client.level.getEntity(atcHighlightedEntityId);
        if (target == null || !target.isAlive()) {
            clearAtcHighlight();
            return;
        }
        if (atcHighlightedEntity != target) {
            int selectedEntityId = atcHighlightedEntityId;
            clearAtcHighlight();
            atcHighlightedEntityId = selectedEntityId;
            atcHighlightedEntity = target;
            atcHighlightOriginalGlow = target.isCurrentlyGlowing();
        }
        if (!target.isCurrentlyGlowing()) target.setGlowingTag(true);
    }

    public static void setAtcSelectedTarget(int entityId) {
        if (atcHighlightedEntityId == entityId) return;
        clearAtcHighlight();
        atcHighlightedEntityId = entityId;
        updateAtcHighlight(Minecraft.getInstance());
    }

    static int atcSelectedTarget() {
        return atcHighlightedEntityId;
    }

    public static void toggleAtcCameraFollow(int entityId) {
        Minecraft client = Minecraft.getInstance();
        if (cameraFollowEntityId == entityId) {
            stopAtcCameraFollow(client);
        } else {
            stopAtcCameraFollow(client);
            cameraFollowEntityId = entityId;
            updateAtcCameraFollow(client);
        }
    }

    public static boolean isFollowingAtcTarget(int entityId) {
        return cameraFollowEntityId == entityId;
    }

    static KeyMapping ackAtc() { return ACK_ATC; }

    private static void stopAtcCameraFollow(Minecraft client) {
        cameraFollowEntityId = -1;
        if (client.player != null && client.getCameraEntity() != client.player) {
            client.setCameraEntity(client.player);
        }
    }

    private static void clearAtcHighlight() {
        if (atcHighlightedEntity != null) {
            atcHighlightedEntity.setGlowingTag(atcHighlightOriginalGlow);
            atcHighlightedEntity = null;
        }
        atcHighlightedEntityId = -1;
    }

    public static List<KeyMapping> flightlineBindings() {
        return FLIGHTLINE_BINDINGS;
    }

    public static List<KeyMapping> controlScreenBindings() {
        List<KeyMapping> bindings = new ArrayList<>(FLIGHTLINE_BINDINGS);
        bindings.add(Minecraft.getInstance().options.keyInventory);
        bindings.sort(Comparator.comparing(FlightLineClient::keyName, String.CASE_INSENSITIVE_ORDER));
        return bindings;
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
    static KeyMapping antiIce() { return TOGGLE_ANTI_ICE; }
    static KeyMapping inventory() { return Minecraft.getInstance().options.keyInventory; }
    static KeyMapping bankLeft() { return BANK_LEFT; }
    static KeyMapping bankRight() { return BANK_RIGHT; }

    static String keyName(KeyMapping mapping) {
        return java.util.Objects.requireNonNull(mapping.getTranslatedKeyMessage().getString());
    }
}
