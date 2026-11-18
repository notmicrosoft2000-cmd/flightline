package dev.flightline.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.flightline.FlightLine;
import dev.flightline.flight.AircraftType;
import dev.flightline.flight.FlightControls;
import dev.flightline.flight.FlightPhase;
import dev.flightline.flight.FlightWarning;
import dev.flightline.network.AdjustFlapsPayload;
import dev.flightline.network.EmergencyActionPayload;
import dev.flightline.network.EmergencyStatusPayload;
import dev.flightline.network.FlightInputPayload;
import dev.flightline.network.FlightNoticePayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightPlanPayload;
import dev.flightline.network.FlightRadarPayload;
import dev.flightline.network.FlightStatePayload;
import dev.flightline.network.NavDataPayload;
import dev.flightline.network.SelectPlanePayload;
import dev.flightline.network.StartEnginesPayload;
import dev.flightline.network.ShutdownEnginesPayload;
import dev.flightline.network.ToggleAirbrakePayload;
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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class FlightLineClient implements ClientModInitializer {
    private static final float VOICE_PITCH = 1.0f;
    private static final float VOICE_VOLUME = 1.4f;
    private static final float CUE_VOLUME = 1.3f;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(FlightLine.id("controls"));
    private static final KeyMapping TOGGLE_GEAR = key("key.flightline.gear", InputConstants.KEY_G);
    private static final KeyMapping SHUTDOWN_ENGINES = key("key.flightline.shutdown", InputConstants.KEY_J);
    private static final KeyMapping TOGGLE_PUSHBACK = key("key.flightline.pushback", InputConstants.KEY_K);
    private static final KeyMapping BRAKES = key("key.flightline.brakes", InputConstants.KEY_M);
    private static final KeyMapping FLAPS_INCREASE = key("key.flightline.flaps_increase", InputConstants.KEY_C);
    private static final KeyMapping FLAPS_DECREASE = key("key.flightline.flaps_decrease", InputConstants.KEY_V);
    private static final KeyMapping TOGGLE_SPOILERS = key("key.flightline.spoilers_toggle", InputConstants.KEY_X);
    private static final KeyMapping TOGGLE_AIRBRAKE = key("key.flightline.airbrake_toggle", InputConstants.KEY_N);
    private static final KeyMapping TOGGLE_AUTOPILOT = key("key.flightline.autopilot", InputConstants.KEY_H);
    private static final KeyMapping OPEN_FLIGHT_RADAR = key("key.flightline.radar", InputConstants.KEY_R);
    private static final KeyMapping TOGGLE_PFD = key("key.flightline.pfd", InputConstants.KEY_U);
    private static final KeyMapping TOGGLE_EICAS = key("key.flightline.eicas", InputConstants.KEY_I);
    private static final KeyMapping TOGGLE_ND_RADAR = key("key.flightline.nd", InputConstants.KEY_B);
    private static final KeyMapping TOGGLE_TELEMETRY = key("key.flightline.telemetry", InputConstants.KEY_Y);
    private static final KeyMapping OPEN_TUTORIAL = key("key.flightline.tutorial", InputConstants.KEY_L);
    private static final KeyMapping OPEN_WEATHER_MAP = key("key.flightline.weather_map", InputConstants.KEY_P);
    private static final KeyMapping TOGGLE_KEYBIND_HINTS = key("key.flightline.keybind_hints", InputConstants.KEY_F9);
    private static final KeyMapping OPEN_CONTROLS = key("key.flightline.controls", InputConstants.KEY_F10);
    private static final KeyMapping ACK_ATC = key("key.flightline.atc_ack", InputConstants.KEY_F7);
    private static final KeyMapping TOGGLE_ANTI_ICE = key("key.flightline.anti_ice", InputConstants.KEY_F8);
    private static final KeyMapping EMERGENCY_CHECKLIST = key("key.flightline.emergency_checklist", InputConstants.KEY_O);
    private static final KeyMapping EXECUTE_EMERGENCY = key("key.flightline.emergency_execute", InputConstants.KEY_T);
    private static final List<KeyMapping> FLIGHTLINE_BINDINGS = List.of(
            TOGGLE_GEAR, SHUTDOWN_ENGINES, TOGGLE_PUSHBACK, BRAKES,
            FLAPS_INCREASE, FLAPS_DECREASE, TOGGLE_SPOILERS, TOGGLE_AIRBRAKE, TOGGLE_AUTOPILOT,
            OPEN_FLIGHT_RADAR, TOGGLE_PFD, TOGGLE_EICAS, TOGGLE_ND_RADAR,
            TOGGLE_TELEMETRY, OPEN_TUTORIAL, OPEN_WEATHER_MAP,
            TOGGLE_KEYBIND_HINTS, OPEN_CONTROLS, ACK_ATC, TOGGLE_ANTI_ICE,
            EMERGENCY_CHECKLIST, EXECUTE_EMERGENCY);
    private static volatile boolean flightEnabled;
    private boolean savedViewBobbing;
    private boolean hasSavedViewBobbing;
    private static volatile float bankDegrees;
    private static volatile boolean airborne;
    private static volatile boolean enginesActive;
    private static volatile boolean enginesStarting;
    private static volatile boolean enginesRunning;
    private static int engineStartRequestTicks;
    private int lastSentControls = -1;
    private static int lastWarningCallout;
    private static int activeAlertId;
    /**
     * Cockpit callouts (altitude, configuration, autopilot modes) are spoken one at a time so they
     * stay intelligible. Alarms never enter this queue: each alarm gets its own playback timer so
     * one alarm can overlap another without silencing it.
     */
    private static final PriorityQueue<QueuedVoice> VOICE_QUEUE = new PriorityQueue<>(
            Comparator.comparingInt(QueuedVoice::priority).reversed()
                    .thenComparingLong(QueuedVoice::sequence));
    private static final Map<Integer, Long> ALERT_NEXT_PLAY_TICK = new HashMap<>();
    private static @Nullable SoundInstance activeVoice;
    private static long clientTicks;
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
    private static int lastFlapDetent = -1;
    private static int lastGearState = -1;
    private static boolean lastAutopilotState;
    private static boolean autopilotStateKnown;
    private static volatile AircraftType currentAircraft = AircraftType.KASA_A380;
    private @Nullable FlightEngineSound engineSound;
    private boolean engineSoundCruise;
    private static int cameraFollowEntityId = -1;
    private static volatile float targetHeadingDegrees;
    private static volatile float targetPitchDegrees;
    private static boolean targetRotationInitialized;
    private static int atcHighlightedEntityId = -1;
    private static @Nullable Entity atcHighlightedEntity;
    private static boolean atcHighlightOriginalGlow;
    private static float vanillaHudSlide;
    private static long lastVanillaHudSlideNanos = System.nanoTime();

    private static KeyMapping key(String translation, int keyCode) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translation,
                InputConstants.Type.KEYBOARD, keyCode, CATEGORY));
    }

    private static float shortestAngle(float delta) {
        float wrapped = delta % 360.0f;
        if (wrapped > 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    public static boolean isFlightEnabled() { return flightEnabled; }
    public static AircraftType getAircraftType() { return currentAircraft; }
    public static void setAircraftType(AircraftType type) { currentAircraft = type; }
    public static KeyMapping flightRadar() { return OPEN_FLIGHT_RADAR; }
    public static KeyMapping togglePfd() { return TOGGLE_PFD; }
    public static KeyMapping toggleEicas() { return TOGGLE_EICAS; }
    public static KeyMapping toggleNdRadar() { return TOGGLE_ND_RADAR; }
    public static KeyMapping telemetry() { return TOGGLE_TELEMETRY; }
    public static KeyMapping flightTutorial() { return OPEN_TUTORIAL; }
    public static KeyMapping weatherMap() { return OPEN_WEATHER_MAP; }
    public static KeyMapping toggleKeybindHints() { return TOGGLE_KEYBIND_HINTS; }
    public static KeyMapping openControls() { return OPEN_CONTROLS; }
    static KeyMapping emergencyChecklist() { return EMERGENCY_CHECKLIST; }
    static KeyMapping executeEmergency() { return EXECUTE_EMERGENCY; }

    public static void playTcasTest(Minecraft client) {
        boolean gina = currentAircraft == AircraftType.GINA_787;
        SoundEvent result = FlightHud.hasRadarData()
                ? (gina ? FlightLine.GINA_TCAS_TEST_PASS : FlightLine.TCAS_TEST_PASS)
                : (gina ? FlightLine.GINA_TCAS_TEST_FAIL : FlightLine.TCAS_TEST_FAIL);
        client.getSoundManager().play(SimpleSoundInstance.forUI(result, VOICE_PITCH, VOICE_VOLUME));
    }

    private static void executeRecommendedEmergency() {
        var recommendation = FlightHud.emergencyStatus().recommendation();
        if (recommendation.present()) {
            ClientPlayNetworking.send(new EmergencyActionPayload(
                    recommendation.action(), recommendation.target()));
        }
    }

    @Override
    public void onInitializeClient() {
        FlightHud.loadPreferences();
        ClientPlayNetworking.registerGlobalReceiver(FlightStatePayload.TYPE, (payload, context) -> {
            flightEnabled = payload.enabled();
            airborne = payload.enabled() && (payload.phase() == 4 || payload.phase() == 5);
            if (!payload.enabled()) {
                engineStartRequestTicks = 0;
                targetRotationInitialized = false;
            }
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
                targetHeadingDegrees = payload.headingDegrees();
                targetPitchDegrees = payload.pitchDegrees();
                if (!targetRotationInitialized) {
                    player.setYRot(targetHeadingDegrees);
                    player.setYHeadRot(targetHeadingDegrees);
                    player.setXRot(-targetPitchDegrees);
                    targetRotationInitialized = true;
                }
            }
            int engine1 = payload.systems() & 7;
            int engine2 = (payload.systems() >> 3) & 7;
            enginesStarting = engine1 == 1 || engine2 == 1;
            enginesRunning = engine1 == 2 || engine2 == 2;
            enginesActive = enginesStarting || enginesRunning;
            FlightHud.updateInstruments(payload);
            FlightCameraEffects.updateInstruments(payload.bankDegrees(), payload.warnings(),
                    payload.radioAltitudeFeet(), payload.verticalSpeedFpm(), (payload.systems() >> 19) & 0x7F,
                    payload.cockpitGuidance().weatherGuidance().turbulencePercent(),
                    payload.cockpitGuidance().weatherGuidance().gustKnots(),
                    payload.cockpitGuidance().weatherGuidance().condition());
            playCockpitAlerts(context.client(), payload.warnings(), payload.radioAltitudeFeet(), payload.verticalSpeedFpm());
            playSystemCallouts(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(FlightRadarPayload.TYPE, (payload, context) ->
                FlightHud.updateRadar(payload));
        ClientPlayNetworking.registerGlobalReceiver(NavDataPayload.TYPE, (payload, context) ->
                NavigationState.setNavData(payload));
        ClientPlayNetworking.registerGlobalReceiver(FlightPlanPayload.TYPE, (payload, context) ->
                NavigationState.setPlan(payload));
        ClientPlayNetworking.registerGlobalReceiver(EmergencyStatusPayload.TYPE, (payload, context) ->
                FlightHud.updateEmergency(payload));
        ClientPlayNetworking.registerGlobalReceiver(FlightNoticePayload.TYPE, (payload, context) ->
                FlightHud.showNotice(payload.message()));
        ClientPlayNetworking.registerGlobalReceiver(AtcRadioPayload.TYPE, (payload, context) -> {
            FlightHud.showAtcMessage(payload.message(), payload.senderEntityId());
            context.client().getSoundManager().play(SimpleSoundInstance.forUI(
                    FlightLine.CALLOUT_DING, VOICE_PITCH, CUE_VOLUME));
        });
        ClientPlayNetworking.registerGlobalReceiver(OpenAtcPayload.TYPE, (payload, context) ->
                context.client().execute(() -> context.client().gui.setScreen(new AtcScreen())));
        ClientPlayNetworking.registerGlobalReceiver(SelectPlanePayload.TYPE, (payload, context) -> {
            AircraftType type = AircraftType.fromId(payload.planeId());
            if (type != currentAircraft) {
                currentAircraft = type;
                resetCockpitAudio(context.client());
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                FlightLine.id("flight_status"), FlightHud::render);
        for (Identifier element : List.of(
                VanillaHudElements.HOTBAR,
                VanillaHudElements.ARMOR_BAR,
                VanillaHudElements.HEALTH_BAR,
                VanillaHudElements.FOOD_BAR,
                VanillaHudElements.AIR_BAR,
                VanillaHudElements.INFO_BAR,
                VanillaHudElements.EXPERIENCE_LEVEL,
                VanillaHudElements.INFO_BAR_FOREGROUND)) {
            HudElementRegistry.replaceElement(element, FlightLineClient::slideDuringFlight);
        }

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            MobBankTracker.tick(client);
            if (engineStartRequestTicks > 0) engineStartRequestTicks--;
            if (!flightEnabled || client.player == null || client.gui.screen() != null) return;

            // G is the landing gear key in flight; keep vanilla quick actions from opening.
            while (client.options.keyQuickActions.consumeClick()) { }

            // E starts the engines until they are running, then behaves as the inventory key.
            // Once engines are running, clear the start-request cooldown and do NOT consume
            // the inventory key — vanilla will handle it and open the player's inventory.
            if (enginesRunning) {
                engineStartRequestTicks = 0;
                // intentional fall-through: do not consume keyInventory clicks here
            } else if (enginesStarting || engineStartRequestTicks > 0) {
                // Block the inventory key while start sequence is in progress so the keypress
                // that triggered engine start doesn't also open the inventory on the same tick.
                while (client.options.keyInventory.consumeClick()) { }
            } else {
                boolean startRequested = false;
                while (client.options.keyInventory.consumeClick()) startRequested = true;
                if (startRequested) {
                    ClientPlayNetworking.send(new StartEnginesPayload());
                    if (!FlightHud.isCrashed()) engineStartRequestTicks = 120;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTicks++;
            updateViewBobbing(client);
            updateEngineSound(client);
            FlightTutorial.tick();
            var player = client.player;

            if (player != null && client.gui.screen() == null) {
                if (TOGGLE_AUTOPILOT.consumeClick() && client.level != null) {
                    client.gui.setScreen(new AutopilotScreen(
                            FlightHud.isAutopilotEngaged(),
                            Math.round(FlightHud.currentHeading()),
                            FlightHud.currentAltitudeFeet(),
                            FlightHud.currentVsFpm(),
                            FlightHud.currentSpeedKnots(),
                            1
                    ));
                }
                if (client.gui.screen() == null) {
                    if (ACK_ATC.consumeClick() && FlightHud.hasAtcMessage()) {
                        int controllerId = FlightHud.atcControllerEntityId();
                        ClientPlayNetworking.send(new AtcAckPayload(controllerId));
                        FlightHud.showAtcMessage("ACK SENT TO ATC", -1);
                    }
                    if (TOGGLE_PFD.consumeClick()) FlightHud.togglePfd();
                    if (TOGGLE_EICAS.consumeClick()) FlightHud.toggleEicas();
                    if (TOGGLE_ND_RADAR.consumeClick()) FlightHud.toggleRadar();
                    if (TOGGLE_TELEMETRY.consumeClick()) FlightHud.toggleTelemetry();
                    if (TOGGLE_KEYBIND_HINTS.consumeClick()) FlightHud.toggleKeybinds();
                    if (OPEN_TUTORIAL.consumeClick()) {
                        FlightTutorial.setActive(true);
                        client.gui.setScreen(new FlightTutorialScreen());
                    }
                    if (OPEN_WEATHER_MAP.consumeClick() && flightEnabled && client.level != null) {
                        client.gui.setScreen(new WeatherMapScreen());
                    }
                    if (OPEN_CONTROLS.consumeClick()) client.gui.setScreen(new FlightControlsScreen());
                    if (EMERGENCY_CHECKLIST.consumeClick() && flightEnabled) {
                        client.gui.setScreen(new EmergencyScreen());
                    }
                    if (EXECUTE_EMERGENCY.consumeClick() && flightEnabled) {
                        executeRecommendedEmergency();
                    }
                    if (TOGGLE_ANTI_ICE.consumeClick() && flightEnabled) {
                        ClientPlayNetworking.send(new ToggleAntiIcePayload(!FlightHud.weatherGuidance().antiIceActive()));
                    }
                    if (OPEN_FLIGHT_RADAR.consumeClick() && flightEnabled && client.level != null) {
                        client.gui.setScreen(new FlightRadarScreen());
                    }
                }
            }
            updateAtcCameraFollow(client);
            if (player != null && flightEnabled && targetRotationInitialized && client.gui.screen() == null) {
                float currentYaw = player.getYRot();
                float yaw = currentYaw + shortestAngle(targetHeadingDegrees - currentYaw) * 0.35f;
                player.setYRot(yaw);
                player.setYHeadRot(yaw);

                // Do not pull the view toward the server's smoothed aircraft pitch while the pilot
                // is flying manually: the camera IS the pitch control, so that feedback loop fights
                // every mouse movement and makes looking up/down feel heavy. Only autopilot/ATC
                // owns the camera pitch, where the view should follow the aircraft instead.
                if (FlightHud.isAutopilotEngaged() || FlightHud.isAtcControlled()) {
                    float currentPitch = player.getXRot();
                    player.setXRot(currentPitch + (-targetPitchDegrees - currentPitch) * 0.35f);
                }
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

            if (TOGGLE_GEAR.consumeClick()) ClientPlayNetworking.send(new ToggleGearPayload());
            if (SHUTDOWN_ENGINES.consumeClick()) ClientPlayNetworking.send(new ShutdownEnginesPayload());
            if (TOGGLE_PUSHBACK.consumeClick()) ClientPlayNetworking.send(new TogglePushbackPayload());
            if (FLAPS_INCREASE.consumeClick()) ClientPlayNetworking.send(new AdjustFlapsPayload(1));
            if (FLAPS_DECREASE.consumeClick()) ClientPlayNetworking.send(new AdjustFlapsPayload(-1));
            if (TOGGLE_SPOILERS.consumeClick()) {
                ClientPlayNetworking.send(new ToggleSpoilersPayload(!FlightHud.spoilersDeployed()));
            }
            if (TOGGLE_AIRBRAKE.consumeClick()) {
                ClientPlayNetworking.send(new ToggleAirbrakePayload(!FlightHud.airbrakesDeployed()));
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
            targetRotationInitialized = false;
            bankDegrees = 0.0f;
            airborne = false;
            FlightCameraEffects.updateFlightState(FlightPhase.OFF);
            FlightCameraEffects.updateInstruments(0.0f, 0, 0, 0, 0, 0, 0, 0);
            enginesActive = false;
            enginesStarting = false;
            enginesRunning = false;
            FlightTutorial.reset();
            engineStartRequestTicks = 0;
            resetCockpitAudio(client);
            clientTicks = 0;
            nextVoiceSequence = 0;
            lastSentControls = -1;
            if (engineSound != null) {
                engineSound.stopLoop();
                engineSound = null;
            }
            FlightHud.clear();
            NavigationState.reset();
            stopAtcCameraFollow(client);
            clearAtcHighlight();
            vanillaHudSlide = 0.0f;
            lastVanillaHudSlideNanos = System.nanoTime();
            if (client.player != null) client.player.setNoGravity(false);
        });
    }

    /**
     * Wraps a vanilla HUD element so it slides below the screen while the aircraft is airborne and
     * returns on short final. The animation is time-based so it looks the same at any frame rate.
     */
    private static HudElement slideDuringFlight(HudElement original) {
        return (graphics, tracker) -> {
            float progress = updateVanillaHudSlide();
            if (progress >= 0.999f) return;
            if (progress <= 0.001f) {
                original.extractRenderState(graphics, tracker);
                return;
            }
            var pose = graphics.pose();
            pose.pushMatrix();
            // Smooth easeInOut cubic curve for sleek animation
            float smooth = progress * progress * (3.0f - 2.0f * progress);
            pose.translate(0.0f, smooth * 65.0f);
            original.extractRenderState(graphics, tracker);
            pose.popMatrix();
        };
    }

    private static float updateVanillaHudSlide() {
        long now = System.nanoTime();
        long elapsed = now - lastVanillaHudSlideNanos;
        if (elapsed < 2_000_000L) return vanillaHudSlide;
        lastVanillaHudSlideNanos = now;
        float delta = Math.min(0.08f, elapsed / 1_000_000_000.0f);
        float target = FlightHud.shouldHideVanillaHud() ? 1.0f : 0.0f;
        vanillaHudSlide += (target - vanillaHudSlide)
                * (1.0f - (float) Math.exp(-delta * 9.0f));
        if (Math.abs(target - vanillaHudSlide) < 0.003f) vanillaHudSlide = target;
        return vanillaHudSlide;
    }

    /** Cockpit callouts for configuration changes: flaps, gear and autopilot mode changes. */
    private static void playSystemCallouts(FlightInstrumentPayload payload) {
        boolean gina = currentAircraft == AircraftType.GINA_787;
        // The Gina sound set has no flap/gear recordings, so use its neutral chime instead of
        // borrowing the Kasa voice.
        SoundEvent flapsSound = gina ? FlightLine.CALLOUT_DING : FlightLine.CALLOUT_FLAPS;
        SoundEvent gearSound = gina ? FlightLine.CALLOUT_DING : FlightLine.CALLOUT_GEAR;
        int flapDetent = (payload.systems() >> 9) & 7;
        int gear = payload.gearState();
        if (lastFlapDetent >= 0 && flapDetent != lastFlapDetent) {
            enqueueCalloutVoice(flapsSound);
        }
        if (lastGearState >= 0 && gear != lastGearState && gear != 2) {
            enqueueCalloutVoice(gearSound);
        }
        if (autopilotStateKnown && payload.autopilot() != lastAutopilotState) {
            enqueueCalloutVoice(FlightLine.CALLOUT_DING);
        }
        lastFlapDetent = flapDetent;
        lastGearState = gear;
        lastAutopilotState = payload.autopilot();
        autopilotStateKnown = true;
    }

    private static void playCockpitAlerts(Minecraft client, int warnings, int radioAlt, int verticalSpeed) {
        int alertId = 0;
        SoundEvent sound = null;
        SoundEvent[] phrase = null;
        int repeatInterval = 40;
        boolean gina = currentAircraft == AircraftType.GINA_787;
        // Repeat intervals track the actual clip length. Gina's urgent alarm is a short chirp
        // (0.51 s) while its caution alarm is a 3.17 s tone, so a single shared interval would
        // either retrigger the chirp on top of itself or leave the long tone almost silent.
        int urgentInterval = gina ? 14 : 30;
        int cautionInterval = gina ? 70 : 30;

        if ((warnings & FlightWarning.WINDSHEAR) != 0) {
            alertId = 10;
            sound = gina ? FlightLine.GINA_GPWS_WINDSHEAR : FlightLine.GPWS_WINDSHEAR;
            repeatInterval = 40;
        } else if ((warnings & FlightWarning.PULL_UP) != 0) {
            alertId = 11;
            if (gina) {
                sound = FlightLine.GINA_GPWS_TERRAIN_PULLUP;
            } else {
                phrase = new SoundEvent[]{FlightLine.GPWS_TERRAIN, FlightLine.GPWS_TERRAIN,
                        FlightLine.GPWS_PULL_UP};
            }
            repeatInterval = gina ? 60 : 30;
        } else if ((warnings & FlightWarning.TOO_LOW_TERRAIN) != 0) {
            alertId = 15;
            sound = gina ? FlightLine.GINA_GPWS_TOO_LOW_TERRAIN : FlightLine.GPWS_TOO_LOW_TERRAIN;
            repeatInterval = gina ? 45 : 40;
        } else if ((warnings & FlightWarning.TERRAIN) != 0) {
            alertId = 12;
            if (gina) {
                sound = FlightLine.GINA_GPWS_TERRAIN_PULLUP;
            } else {
                phrase = new SoundEvent[]{FlightLine.GPWS_TERRAIN, FlightLine.GPWS_TERRAIN};
            }
            repeatInterval = gina ? 60 : 35;
        } else if ((warnings & FlightWarning.TCAS_RESOLUTION) != 0) {
            boolean climb = (warnings & FlightWarning.TCAS_CLIMB) != 0;
            boolean crossing = (warnings & FlightWarning.TCAS_CROSSING) != 0;
            alertId = climb ? (crossing ? 3 : 2) : (crossing ? 5 : 4);
            sound = switch (alertId) {
                case 2 -> gina ? FlightLine.GINA_TCAS_CLIMB_NOW : FlightLine.TCAS_CLIMB;
                case 3 -> gina ? FlightLine.GINA_TCAS_CLIMB_CROSSING : FlightLine.TCAS_CLIMB_CROSSING;
                case 4 -> gina ? FlightLine.GINA_TCAS_DESCEND_NOW : FlightLine.TCAS_DESCEND;
                default -> gina ? FlightLine.GINA_TCAS_DESCEND_CROSSING : FlightLine.TCAS_DESCEND_CROSSING;
            };
            repeatInterval = gina ? 55 : 50;
        } else if ((warnings & FlightWarning.TCAS_CLEAR) != 0) {
            alertId = 6;
            sound = gina ? FlightLine.GINA_TCAS_CLEAR : FlightLine.TCAS_CLEAR;
            repeatInterval = gina ? 120 : 200;
        } else if ((warnings & FlightWarning.SINK_RATE) != 0) {
            alertId = 13;
            sound = gina ? FlightLine.GINA_GPWS_SINK_RATE : FlightLine.GPWS_SINK_RATE;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.DONT_SINK) != 0) {
            alertId = 14;
            sound = gina ? FlightLine.GINA_GPWS_DONT_SINK : FlightLine.GPWS_DONT_SINK;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.GEAR_UP) != 0) {
            alertId = 16;
            sound = gina ? FlightLine.GINA_GPWS_TOO_LOW_GEAR : FlightLine.GPWS_TOO_LOW_GEAR;
            repeatInterval = gina ? 70 : 40;
        } else if ((warnings & FlightWarning.TOO_LOW_FLAPS) != 0) {
            alertId = 17;
            sound = gina ? FlightLine.GINA_GPWS_TOO_LOW_TERRAIN : FlightLine.GPWS_TOO_LOW_FLAPS;
            repeatInterval = gina ? 45 : 40;
        } else if ((warnings & FlightWarning.TCAS_TRAFFIC) != 0) {
            alertId = 1;
            sound = gina ? FlightLine.GINA_TCAS_TRAFFIC : FlightLine.TCAS_TRAFFIC;
            repeatInterval = 0;
        } else if ((warnings & (FlightWarning.GLIDESLOPE_LOW | FlightWarning.GLIDESLOPE_HIGH)) != 0) {
            alertId = 18;
            sound = gina ? FlightLine.GINA_GPWS_GLIDESLOPE : FlightLine.GPWS_GLIDESLOPE;
            repeatInterval = 50;
        } else if ((warnings & FlightWarning.BANK_ANGLE) != 0) {
            alertId = 19;
            sound = gina ? FlightLine.GINA_GPWS_BANK_ANGLE : FlightLine.GPWS_BANK_ANGLE;
            repeatInterval = 45;
        } else if ((warnings & FlightWarning.STALL) != 0) {
            alertId = 20;
            sound = gina ? FlightLine.GINA_STALL_STALL : FlightLine.CAUTION_ALARM;
            repeatInterval = gina ? 45 : 40;
        } else if ((warnings & FlightWarning.TAKEOFF_CONFIG) != 0) {
            alertId = 27;
            sound = gina ? FlightLine.GINA_TAKEOFF_CONFIG_WARN : FlightLine.CAUTION_ALARM;
            repeatInterval = gina ? 50 : 50;
        } else if ((warnings & FlightWarning.ENGINE_FIRE) != 0) {
            alertId = 21;
            sound = gina ? FlightLine.GINA_URGENT_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = urgentInterval;
        } else if ((warnings & FlightWarning.ENGINE_FAILURE) != 0) {
            alertId = 22;
            sound = gina ? FlightLine.GINA_CAUTION_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = cautionInterval;
        } else if ((warnings & FlightWarning.FUEL_EMPTY) != 0) {
            alertId = 23;
            sound = gina ? FlightLine.GINA_URGENT_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = urgentInterval;
        } else if ((warnings & FlightWarning.FUEL_LOW) != 0) {
            alertId = 24;
            sound = gina ? FlightLine.GINA_CAUTION_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = cautionInterval;
        } else if ((warnings & FlightWarning.GEAR_FAILED) != 0) {
            alertId = 25;
            sound = gina ? FlightLine.GINA_CAUTION_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = cautionInterval;
        } else if ((warnings & FlightWarning.GEAR_HOT) != 0) {
            alertId = 26;
            sound = gina ? FlightLine.GINA_CAUTION_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = cautionInterval;
        } else if ((warnings & FlightWarning.AIRFRAME_STRESS) != 0) {
            alertId = 29;
            sound = gina ? FlightLine.GINA_URGENT_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = urgentInterval;
        } else if ((warnings & FlightWarning.OVERSPEED) != 0) {
            alertId = 28;
            sound = gina ? FlightLine.GINA_URGENT_ALARM : FlightLine.CAUTION_ALARM;
            repeatInterval = urgentInterval;
        }

        int previousAlert = lastWarningCallout;
        activeAlertId = alertId;
        lastWarningCallout = alertId;
        if (alertId == 0) {
            ALERT_NEXT_PLAY_TICK.clear();
        } else {
            // Every alarm keeps its own timer. A new alarm is never blocked by, and never stops,
            // an alarm that is still sounding, so simultaneous failures stay audible.
            boolean due = repeatInterval <= 0
                    ? alertId != previousAlert
                    : clientTicks >= ALERT_NEXT_PLAY_TICK.getOrDefault(alertId, Long.MIN_VALUE);
            if (due) {
                if (sound != null) {
                    playAlertSound(client, sound);
                } else if (phrase != null) {
                    for (SoundEvent part : phrase) enqueueCalloutVoice(part, 4);
                }
                if (repeatInterval > 0) {
                    ALERT_NEXT_PLAY_TICK.put(alertId, clientTicks + repeatInterval);
                }
            }
        }

        boolean groundGearWarningActive = (warnings & FlightWarning.GEAR_SELECTED_ON_GROUND) != 0;
        if (groundGearWarningActive && !groundGearWarningWasActive) {
            playAlertSound(client, gina ? FlightLine.GINA_CAUTION_ALARM : FlightLine.CAUTION_ALARM);
        }
        groundGearWarningWasActive = groundGearWarningActive;

        // Radio Altimeter Callouts during descent
        if (airborne && verticalSpeed < -40) {
            if (previousRadioAltitude >= 0) {
                checkAltimeterCallout(2500, 1 << 7, gina ? FlightLine.GINA_CALLOUT_2500 : FlightLine.CALLOUT_2500, radioAlt);
                checkAltimeterCallout(1000, 1 << 8, gina ? FlightLine.GINA_CALLOUT_1000 : FlightLine.CALLOUT_1000, radioAlt);
                checkAltimeterCallout(500, 1 << 0, gina ? FlightLine.GINA_CALLOUT_500 : FlightLine.CALLOUT_500, radioAlt);
                if (gina) {
                    checkAltimeterCallout(400, 1 << 9, FlightLine.GINA_CALLOUT_400, radioAlt);
                    checkAltimeterCallout(300, 1 << 10, FlightLine.GINA_CALLOUT_300, radioAlt);
                    checkAltimeterCallout(200, 1 << 11, FlightLine.GINA_CALLOUT_200, radioAlt);
                }
                checkAltimeterCallout(100, 1 << 1, gina ? FlightLine.GINA_CALLOUT_100 : FlightLine.CALLOUT_100, radioAlt);
                checkAltimeterCallout(50, 1 << 2, gina ? null : FlightLine.CALLOUT_50, radioAlt);
                checkAltimeterCallout(40, 1 << 3, gina ? FlightLine.GINA_CALLOUT_40 : FlightLine.CALLOUT_40, radioAlt);
                checkAltimeterCallout(30, 1 << 4, gina ? FlightLine.GINA_CALLOUT_30 : FlightLine.CALLOUT_30, radioAlt);
                checkAltimeterCallout(20, 1 << 5, gina ? FlightLine.GINA_CALLOUT_20 : FlightLine.CALLOUT_20, radioAlt);
                checkAltimeterCallout(10, 1 << 6, gina ? FlightLine.GINA_CALLOUT_10 : FlightLine.CALLOUT_10, radioAlt);
            }

            if (radioAlt <= 20 && radioAlt > 2 && FlightHud.throttle() > 0.05f && !retardFired) {
                retardFired = true;
                if (!gina) enqueueCalloutVoice(FlightLine.CALLOUT_RETARD);
            }
            if (FlightHud.isIlsActive() && !approachingMinimumsFired
                    && previousRadioAltitude > 200 && radioAlt <= 200) {
                approachingMinimumsFired = true;
                enqueueCalloutVoice(gina ? FlightLine.GINA_VOICE_APPROACHING_MINIMUMS : FlightLine.VOICE_APPROACHING_MINIMUMS);
            }
            if (FlightHud.isIlsActive() && !minimumsFired
                    && previousRadioAltitude > 100 && radioAlt <= 100) {
                minimumsFired = true;
                enqueueCalloutVoice(gina ? FlightLine.GINA_VOICE_MINIMUMS : FlightLine.VOICE_MINIMUMS);
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
        startNextCallout(client);
    }

    /** Plays an alarm immediately. Other alarms already sounding are intentionally left alone. */
    private static void playAlertSound(Minecraft client, SoundEvent sound) {
        client.getSoundManager().play(SimpleSoundInstance.forUI(sound, VOICE_PITCH, VOICE_VOLUME));
    }

    private static void resetCockpitAudio(Minecraft client) {
        VOICE_QUEUE.clear();
        ALERT_NEXT_PLAY_TICK.clear();
        if (activeVoice != null) {
            client.getSoundManager().stop(activeVoice);
            activeVoice = null;
        }
        lastWarningCallout = 0;
        activeAlertId = 0;
        groundGearWarningWasActive = false;
        previousRadioAltitude = -1;
        armedCallouts = 0;
        retardFired = false;
        approachingMinimumsFired = false;
        minimumsFired = false;
        previousAircraftAltitudeFeet = -1;
        lastVerticalVoiceId = 0;
        verticalVoiceCooldown = 0;
        lastFlapDetent = -1;
        lastGearState = -1;
        lastAutopilotState = false;
        autopilotStateKnown = false;
    }

    public static boolean isVanillaKeySuppressed(@Nullable KeyMapping mapping) {
        if (!flightEnabled || mapping == null) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return false;
        var options = client.options;
        return mapping == options.keyQuickActions
                || mapping == options.keyAdvancements
                || mapping == options.keySocialInteractions
                || mapping == options.keyFriends
                || mapping == options.keySaveHotbarActivator
                || mapping == options.keyLoadHotbarActivator
                || mapping == options.keySwapOffhand
                || mapping == options.keyDrop;
    }

    public static void drainSuppressedVanillaClicks() {
        if (!flightEnabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        var options = client.options;
        drainClicks(options.keyQuickActions);
        drainClicks(options.keyAdvancements);
        drainClicks(options.keySocialInteractions);
        drainClicks(options.keyFriends);
        drainClicks(options.keySaveHotbarActivator);
        drainClicks(options.keyLoadHotbarActivator);
        drainClicks(options.keySwapOffhand);
        drainClicks(options.keyDrop);
    }

    private static void drainClicks(KeyMapping mapping) {
        while (mapping.consumeClick()) { }
    }

    private void discardFlightInput() {
        for (KeyMapping mapping : FLIGHTLINE_BINDINGS) {
            while (mapping.consumeClick()) { }
        }
        while (Minecraft.getInstance().options.keyInventory.consumeClick()) { }
    }

    private static void checkAltimeterCallout(int threshold, int mask, @Nullable SoundEvent sound, int currentAlt) {
        if (currentAlt > threshold + 50) {
            armedCallouts |= mask;
        } else if ((armedCallouts & mask) != 0 && previousRadioAltitude >= threshold && currentAlt < threshold) {
            armedCallouts &= ~mask;
            if (sound != null) enqueueCalloutVoice(sound);
        }
    }

    private static void playAltitudeAndVerticalGuidance(Minecraft client, int verticalSpeed) {
        var player = client.player;
        if (player == null) return;
        int currentAltitudeFeet = (int) Math.round(player.getY() * 3.28084);
        var guidance = FlightHud.autopilotGuidance();
        boolean gina = currentAircraft == AircraftType.GINA_787;
        if (FlightHud.isAutopilotEngaged()) {
            int plus100Altitude = guidance.targetAltitudeFeet() + 100;
            if (previousAircraftAltitudeFeet >= 0
                    && ((previousAircraftAltitudeFeet < plus100Altitude && currentAltitudeFeet >= plus100Altitude)
                    || (previousAircraftAltitudeFeet > plus100Altitude && currentAltitudeFeet <= plus100Altitude))) {
                enqueueCalloutVoice(gina ? FlightLine.CALLOUT_DING : FlightLine.CALLOUT_PLUS_100);
            }
            int error = guidance.targetVerticalSpeedFpm() - verticalSpeed;
            int voiceId;
            SoundEvent voice;
            if (Math.abs(error) > 800) {
                voiceId = 7;
                voice = gina ? FlightLine.GINA_VOICE_ADJUST_VERTICAL_SPEED
                        : FlightLine.VOICE_ADJUST_VERTICAL_SPEED;
            } else if (Math.abs(error) <= 150) {
                voiceId = 1;
                voice = gina ? null : FlightLine.VOICE_MAINTAIN_VERTICAL_SPEED;
            } else if (guidance.targetVerticalSpeedFpm() > 0) {
                voiceId = error > 0 ? 2 : 3;
                voice = gina ? FlightLine.GINA_VOICE_ADJUST_VERTICAL_SPEED
                        : (error > 0 ? FlightLine.VOICE_INCREASE_CLIMB : FlightLine.VOICE_REDUCE_CLIMB);
            } else if (guidance.targetVerticalSpeedFpm() < 0) {
                voiceId = error > 0 ? 4 : 5;
                voice = gina ? FlightLine.GINA_VOICE_ADJUST_VERTICAL_SPEED
                        : (error > 0 ? FlightLine.VOICE_INCREASE_DESCEND : FlightLine.VOICE_REDUCE_DESCEND);
            } else {
                voiceId = 6;
                voice = gina ? null : FlightLine.VOICE_MONITOR_VERTICAL_SPEED;
            }
            if (voice != null && voiceId != lastVerticalVoiceId && verticalVoiceCooldown <= 0) {
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
        enqueueCalloutVoice(sound, 0);
    }

    private static void enqueueCalloutVoice(SoundEvent sound) {
        enqueueCalloutVoice(sound, 2);
    }

    private static void enqueueCalloutVoice(SoundEvent sound, int priority) {
        VOICE_QUEUE.add(new QueuedVoice(sound, priority, nextVoiceSequence++));
    }

    /**
     * Speaks one queued callout at a time. Alarms bypass this queue entirely, so a running alarm
     * can never delay or mute altitude and configuration callouts.
     */
    private static void startNextCallout(Minecraft client) {
        if (activeVoice != null && client.getSoundManager().isActive(activeVoice)) return;
        activeVoice = null;
        if (VOICE_QUEUE.isEmpty()) return;
        QueuedVoice next = VOICE_QUEUE.remove();
        SoundInstance instance = SimpleSoundInstance.forUI(next.sound(), VOICE_PITCH, VOICE_VOLUME);
        client.getSoundManager().play(instance);
        activeVoice = instance;
    }

    private record QueuedVoice(SoundEvent sound, int priority, long sequence) {
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

    public static int atcSelectedTarget() {
        return atcHighlightedEntityId;
    }

    public static boolean isAtcSelectedTarget(int entityId) {
        return atcHighlightedEntityId >= 0 && atcHighlightedEntityId == entityId;
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

    public static List<KeyMapping> controlScreenBindings() {
        Minecraft client = Minecraft.getInstance();
        List<KeyMapping> bindings = new ArrayList<>(FLIGHTLINE_BINDINGS);
        bindings.add(client.options.keyUp);
        bindings.add(client.options.keyDown);
        bindings.add(client.options.keyLeft);
        bindings.add(client.options.keyRight);
        bindings.add(client.options.keyInventory);
        bindings.sort(Comparator.comparing(FlightLineClient::keyName, String.CASE_INSENSITIVE_ORDER));
        return bindings;
    }

    public static float bankDegrees() { return bankDegrees; }
    public static boolean isAirborne() { return airborne; }
    public static boolean areEnginesActive() { return enginesActive; }

    static KeyMapping toggleGear() { return TOGGLE_GEAR; }
    static KeyMapping toggleAutopilot() { return TOGGLE_AUTOPILOT; }
    static KeyMapping shutdownEngines() { return SHUTDOWN_ENGINES; }
    static KeyMapping flapsIncrease() { return FLAPS_INCREASE; }
    static KeyMapping flapsDecrease() { return FLAPS_DECREASE; }
    static KeyMapping spoilersToggle() { return TOGGLE_SPOILERS; }
    static KeyMapping airbrakeToggle() { return TOGGLE_AIRBRAKE; }
    static KeyMapping pushback() { return TOGGLE_PUSHBACK; }
    static KeyMapping brakes() { return BRAKES; }
    static KeyMapping antiIce() { return TOGGLE_ANTI_ICE; }
    static KeyMapping inventory() { return Minecraft.getInstance().options.keyInventory; }
    static KeyMapping throttleUp() { return Minecraft.getInstance().options.keyUp; }
    static KeyMapping throttleDown() { return Minecraft.getInstance().options.keyDown; }
    static KeyMapping rudderLeft() { return Minecraft.getInstance().options.keyLeft; }
    static KeyMapping rudderRight() { return Minecraft.getInstance().options.keyRight; }

    static String keyName(KeyMapping mapping) {
        return java.util.Objects.requireNonNull(mapping.getTranslatedKeyMessage().getString());
    }
}
