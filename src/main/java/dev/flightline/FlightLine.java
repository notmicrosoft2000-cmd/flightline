package dev.flightline;

import dev.flightline.flight.AircraftType;
import dev.flightline.flight.FlightController;
import dev.flightline.flight.MobFlightController;
import dev.flightline.flight.RunwayGenerator;
import dev.flightline.network.AtcOrderPayload;
import dev.flightline.network.AtcRadioPayload;
import dev.flightline.network.AtcAckPayload;
import dev.flightline.network.OpenAtcPayload;
import dev.flightline.network.FlightInputPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightRadarPayload;
import dev.flightline.network.FlightStatePayload;
import dev.flightline.network.SelectPlanePayload;
import dev.flightline.network.StartEnginesPayload;
import dev.flightline.network.TogglePushbackPayload;
import dev.flightline.network.ToggleGearPayload;
import dev.flightline.network.AdjustFlapsPayload;
import dev.flightline.network.ToggleAirbrakePayload;
import dev.flightline.network.ToggleSpoilersPayload;
import dev.flightline.network.ShutdownEnginesPayload;
import dev.flightline.network.ToggleAutopilotPayload;
import dev.flightline.network.ConfigureAutopilotPayload;
import dev.flightline.network.ConfigureIlsPayload;
import dev.flightline.network.ToggleAutolandPayload;
import dev.flightline.network.ToggleAntiIcePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Prediction;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class FlightLine implements ModInitializer {
    public static final String MOD_ID = "flightline";
    private static final Map<UUID, PendingAtcAcknowledgement> PENDING_ATC_ACKS = new HashMap<>();
    public static final Logger LOGGER = java.util.Objects.requireNonNull(LoggerFactory.getLogger(MOD_ID));
    @SuppressWarnings("null")
    public static final SoundEvent ENGINE_LOOP = Registry.register(BuiltInRegistries.SOUND_EVENT,
            id("engine_loop"), SoundEvent.createVariableRangeEvent(id("engine_loop")));
    public static final SoundEvent ENGINE_CRUISE = registerSound("engine_cruise");
    public static final SoundEvent TCAS_TRAFFIC = registerSound("tcas_traffic");
    public static final SoundEvent TCAS_CLIMB = registerSound("tcas_climb_now");
    public static final SoundEvent TCAS_CLIMB_CROSSING = registerSound("tcas_climb_crossing");
    public static final SoundEvent TCAS_DESCEND = registerSound("tcas_descend_now");
    public static final SoundEvent TCAS_DESCEND_CROSSING = registerSound("tcas_descend_crossing");
    public static final SoundEvent TCAS_CLEAR = registerSound("tcas_clear");
    public static final SoundEvent GPWS_PULL_UP = registerSound("gpws_pull_up");
    public static final SoundEvent GPWS_TERRAIN = registerSound("gpws_terrain");
    public static final SoundEvent GPWS_SINK_RATE = registerSound("gpws_sink_rate");
    public static final SoundEvent GPWS_DONT_SINK = registerSound("gpws_dont_sink");
    public static final SoundEvent GPWS_TOO_LOW_GEAR = registerSound("gpws_too_low_gear");
    public static final SoundEvent GPWS_TOO_LOW_FLAPS = registerSound("gpws_too_low_flaps");
    public static final SoundEvent GPWS_TOO_LOW_TERRAIN = registerSound("gpws_too_low_terrain");
    public static final SoundEvent GPWS_BANK_ANGLE = registerSound("gpws_bank_angle");
    public static final SoundEvent GPWS_GLIDESLOPE = registerSound("gpws_glideslope");
    public static final SoundEvent GPWS_WINDSHEAR = registerSound("gpws_windshear");
    public static final SoundEvent CAUTION_ALARM = registerSound("caution_alarm");
    public static final SoundEvent CALLOUT_DING = registerSound("callout_ding");
    public static final SoundEvent CALLOUT_2500 = registerSound("callout_2500");
    public static final SoundEvent CALLOUT_1000 = registerSound("callout_1000");
    public static final SoundEvent CALLOUT_500 = registerSound("callout_500");
    public static final SoundEvent CALLOUT_100 = registerSound("callout_100");
    public static final SoundEvent CALLOUT_50 = registerSound("callout_50");
    public static final SoundEvent CALLOUT_40 = registerSound("callout_40");
    public static final SoundEvent CALLOUT_30 = registerSound("callout_30");
    public static final SoundEvent CALLOUT_20 = registerSound("callout_20");
    public static final SoundEvent CALLOUT_10 = registerSound("callout_10");
    public static final SoundEvent CALLOUT_RETARD = registerSound("callout_retard");
    public static final SoundEvent CALLOUT_PLUS_100 = registerSound("callout_plus_100");
    public static final SoundEvent CALLOUT_FLAPS = registerSound("callout_flaps");
    public static final SoundEvent CALLOUT_GEAR = registerSound("callout_gear");
    public static final SoundEvent VOICE_APPROACHING_MINIMUMS = registerSound("voice_approaching_minimums");
    public static final SoundEvent VOICE_MINIMUMS = registerSound("voice_minimums");
    public static final SoundEvent VOICE_ADJUST_VERTICAL_SPEED = registerSound("voice_adjust_vertical_speed");
    public static final SoundEvent VOICE_MAINTAIN_VERTICAL_SPEED = registerSound("voice_maintain_vertical_speed");
    public static final SoundEvent VOICE_MONITOR_VERTICAL_SPEED = registerSound("voice_monitor_vertical_speed");
    public static final SoundEvent VOICE_INCREASE_CLIMB = registerSound("voice_increase_climb");
    public static final SoundEvent VOICE_INCREASE_DESCEND = registerSound("voice_increase_descend");
    public static final SoundEvent VOICE_REDUCE_CLIMB = registerSound("voice_reduce_climb");
    public static final SoundEvent VOICE_REDUCE_DESCEND = registerSound("voice_reduce_descend");
    public static final SoundEvent TCAS_TEST_PASS = registerSound("tcas_test_pass");
    public static final SoundEvent TCAS_TEST_FAIL = registerSound("tcas_test_fail");

    // Gina 787 specific voice alerts and alarms
    public static final SoundEvent GINA_CALLOUT_2500 = registerSound("gina_callout_2500");
    public static final SoundEvent GINA_CALLOUT_1000 = registerSound("gina_callout_1000");
    public static final SoundEvent GINA_CALLOUT_500 = registerSound("gina_callout_500");
    public static final SoundEvent GINA_CALLOUT_400 = registerSound("gina_callout_400");
    public static final SoundEvent GINA_CALLOUT_300 = registerSound("gina_callout_300");
    public static final SoundEvent GINA_CALLOUT_200 = registerSound("gina_callout_200");
    public static final SoundEvent GINA_CALLOUT_100 = registerSound("gina_callout_100");
    public static final SoundEvent GINA_CALLOUT_40 = registerSound("gina_callout_40");
    public static final SoundEvent GINA_CALLOUT_30 = registerSound("gina_callout_30");
    public static final SoundEvent GINA_CALLOUT_20 = registerSound("gina_callout_20");
    public static final SoundEvent GINA_CALLOUT_10 = registerSound("gina_callout_10");
    public static final SoundEvent GINA_CAUTION_ALARM = registerSound("gina_caution_alarm");
    public static final SoundEvent GINA_URGENT_ALARM = registerSound("gina_urgent_alarm");
    public static final SoundEvent GINA_STALL_STALL = registerSound("gina_stall_stall");
    public static final SoundEvent GINA_TAKEOFF_CONFIG_WARN = registerSound("gina_takeoff_config_warn");
    public static final SoundEvent GINA_GPWS_BANK_ANGLE = registerSound("gina_gpws_bank_angle");
    public static final SoundEvent GINA_GPWS_DONT_SINK = registerSound("gina_gpws_dont_sink");
    public static final SoundEvent GINA_GPWS_GLIDESLOPE = registerSound("gina_gpws_glideslope");
    public static final SoundEvent GINA_GPWS_PULL_UP = registerSound("gina_gpws_pull_up");
    public static final SoundEvent GINA_GPWS_TERRAIN_PULLUP = registerSound("gina_gpws_terrain_pullup");
    public static final SoundEvent GINA_GPWS_SINK_RATE = registerSound("gina_gpws_sink_rate");
    public static final SoundEvent GINA_GPWS_TOO_LOW_GEAR = registerSound("gina_gpws_too_low_gear");
    public static final SoundEvent GINA_GPWS_TOO_LOW_TERRAIN = registerSound("gina_gpws_too_low_terrain");
    public static final SoundEvent GINA_GPWS_WINDSHEAR = registerSound("gina_gpws_windshear");
    public static final SoundEvent GINA_GPWS_WINDSHEAR_AHEAD = registerSound("gina_gpws_windshear_ahead");
    public static final SoundEvent GINA_TCAS_CLEAR = registerSound("gina_tcas_clear");
    public static final SoundEvent GINA_TCAS_CLIMB = registerSound("gina_tcas_climb");
    public static final SoundEvent GINA_TCAS_CLIMB_NOW = registerSound("gina_tcas_climb_now");
    public static final SoundEvent GINA_TCAS_CLIMB_CROSSING = registerSound("gina_tcas_climb_crossing");
    public static final SoundEvent GINA_TCAS_DESCEND = registerSound("gina_tcas_descend");
    public static final SoundEvent GINA_TCAS_DESCEND_NOW = registerSound("gina_tcas_descend_now");
    public static final SoundEvent GINA_TCAS_DESCEND_CROSSING = registerSound("gina_tcas_descend_crossing");
    public static final SoundEvent GINA_TCAS_TRAFFIC = registerSound("gina_tcas_traffic");
    public static final SoundEvent GINA_TCAS_TEST_PASS = registerSound("gina_tcas_test_pass");
    public static final SoundEvent GINA_TCAS_TEST_FAIL = registerSound("gina_tcas_test_fail");
    public static final SoundEvent GINA_VOICE_ADJUST_VERTICAL_SPEED = registerSound("gina_voice_adjust_vertical_speed");
    public static final SoundEvent GINA_VOICE_APPROACHING_MINIMUMS = registerSound("gina_voice_approaching_minimums");
    public static final SoundEvent GINA_VOICE_APPROACHING_DECISION_HEIGHT = registerSound("gina_voice_approaching_decision_height");
    public static final SoundEvent GINA_VOICE_MINIMUMS = registerSound("gina_voice_minimums");

    @SuppressWarnings("null")
    private static SoundEvent registerSound(String path) {
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id(path), SoundEvent.createVariableRangeEvent(id(path)));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(AdjustFlapsPayload.TYPE, AdjustFlapsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleSpoilersPayload.TYPE, ToggleSpoilersPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleAirbrakePayload.TYPE, ToggleAirbrakePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleAutopilotPayload.TYPE, ToggleAutopilotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigureAutopilotPayload.TYPE, ConfigureAutopilotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigureIlsPayload.TYPE, ConfigureIlsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleAutolandPayload.TYPE, ToggleAutolandPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleAntiIcePayload.TYPE, ToggleAntiIcePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AtcOrderPayload.TYPE, AtcOrderPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AtcAckPayload.TYPE, AtcAckPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShutdownEnginesPayload.TYPE, ShutdownEnginesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FlightInputPayload.TYPE, FlightInputPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleGearPayload.TYPE, ToggleGearPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StartEnginesPayload.TYPE, StartEnginesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TogglePushbackPayload.TYPE, TogglePushbackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SelectPlanePayload.TYPE, SelectPlanePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FlightStatePayload.TYPE, FlightStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FlightInstrumentPayload.TYPE, FlightInstrumentPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FlightRadarPayload.TYPE, FlightRadarPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AtcRadioPayload.TYPE, AtcRadioPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenAtcPayload.TYPE, OpenAtcPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SelectPlanePayload.TYPE, SelectPlanePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(FlightInputPayload.TYPE, (payload, context) -> {
            FlightController.updateInput(context.player(), payload.controls());
        });

        ServerPlayNetworking.registerGlobalReceiver(SelectPlanePayload.TYPE, (payload, context) -> {
            AircraftType type = AircraftType.fromId(payload.planeId());
            context.server().execute(() -> FlightController.setAircraftType(context.player(), type));
        });

        ServerPlayNetworking.registerGlobalReceiver(ToggleGearPayload.TYPE, (payload, context) ->
                FlightController.toggleGear(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(StartEnginesPayload.TYPE, (payload, context) ->
                FlightController.startEngines(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(TogglePushbackPayload.TYPE, (payload, context) ->
                FlightController.togglePushback(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(AdjustFlapsPayload.TYPE, (payload, context) ->
                FlightController.adjustFlaps(context.player(), payload.delta()));
        ServerPlayNetworking.registerGlobalReceiver(ToggleSpoilersPayload.TYPE, (payload, context) ->
                FlightController.setSpoilers(context.player(), payload.deployed()));
        ServerPlayNetworking.registerGlobalReceiver(ToggleAirbrakePayload.TYPE, (payload, context) ->
                FlightController.setAirbrake(context.player(), payload.deployed()));
        ServerPlayNetworking.registerGlobalReceiver(ShutdownEnginesPayload.TYPE, (payload, context) ->
                FlightController.shutdownEngines(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ToggleAutopilotPayload.TYPE, (payload, context) ->
                FlightController.toggleAutopilot(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ConfigureAutopilotPayload.TYPE, (payload, context) ->
                FlightController.configureAutopilot(context.player(), payload.engaged(), payload.targetHeading(),
                        payload.targetAltitudeFeet(), payload.targetVerticalSpeedFpm(), payload.targetAirspeedKnots()));
        ServerPlayNetworking.registerGlobalReceiver(ConfigureIlsPayload.TYPE, (payload, context) -> {
            if (payload.active()) {
                boolean configured = FlightController.setIlsReference(context.player(), payload.thresholdX(),
                        payload.thresholdZ(), payload.runwayHeadingDegrees(),
                        payload.glideSlopeDegrees());
                context.player().sendSystemMessage(Component.literal(configured
                        ? "ILS runway reference set for this dimension."
                        : "ILS setup rejected: check flight mode, coordinates, heading, and glide slope."));
            } else {
                boolean cleared = FlightController.clearIlsReference(context.player());
                context.player().sendSystemMessage(Component.literal(cleared
                        ? "ILS runway reference cleared."
                        : "ILS is unavailable until your flight session is active."));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ToggleAutolandPayload.TYPE, (payload, context) -> {
            boolean wasArmed = FlightController.isAutolandArmed(context.player());
            boolean toggled = FlightController.toggleAutoland(context.player());
            context.player().sendSystemMessage(Component.literal(!toggled
                    ? "Autoland unavailable: arm while airborne with valid ILS, engines, gear, and airspeed."
                    : wasArmed ? "Autoland disengaged." : "Autoland armed. It tracks the runway and manages approach configuration."));
        });
        ServerPlayNetworking.registerGlobalReceiver(ToggleAntiIcePayload.TYPE, (payload, context) ->
                context.server().execute(() -> FlightController.setAntiIce(context.player(), payload.enabled())));
        ServerPlayNetworking.registerGlobalReceiver(AtcOrderPayload.TYPE, (payload, context) ->
                context.server().execute(() -> issueAtcOrder(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(AtcAckPayload.TYPE, (payload, context) ->
                context.server().execute(() -> acknowledgeAtc(context.player(), payload)));

        ServerTickEvents.END_SERVER_TICK.register(FlightController::tick);
        ServerTickEvents.END_SERVER_TICK.register(RunwayGenerator::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            giveFlightGuide(handler.player);
            notifyAircraftSelection(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            FlightController.remove(handler.player.getUUID(), handler.player);
            PENDING_ATC_ACKS.remove(handler.player.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            FlightController.resetAfterRespawn(oldPlayer, newPlayer);
            giveFlightGuide(newPlayer);
            notifyAircraftSelection(newPlayer);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));

        LOGGER.info("Flight Line initialized for Minecraft 26.3");
    }

    private static boolean isOperator(CommandSourceStack source) {
        return source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && source.getServer().getPlayerList().isOp(player.nameAndId());
    }

    private static void issueAtcOrder(ServerPlayer sender, AtcOrderPayload payload) {
        if (!sender.level().getServer().getPlayerList().isOp(sender.nameAndId())) {
            sender.sendSystemMessage(Component.literal("ATC orders require operator permission."));
            return;
        }
        Entity target = sender.level().getEntity(payload.entityId());
        if (target == null || !target.isAlive() || target == sender
                || target.position().distanceToSqr(sender.position()) > 2048.0 * 2048.0) {
            sender.sendSystemMessage(Component.literal("ATC target unavailable or out of range."));
            return;
        }
        if (payload.order() == AtcOrderPayload.FOLLOW) {
            Entity lead = sender.level().getEntity(payload.referenceEntityId());
            if (lead == null || !lead.isAlive() || lead == target
                    || lead.position().distanceToSqr(sender.position()) > 2048.0 * 2048.0) {
                sender.sendSystemMessage(Component.literal("Follow aircraft unavailable or out of range."));
                return;
            }
        }
        String order = atcOrderText(payload);
        if (order == null) {
            sender.sendSystemMessage(Component.literal("Invalid ATC order."));
            return;
        }
        if (target instanceof ServerPlayer pilot) {
            PENDING_ATC_ACKS.put(pilot.getUUID(), new PendingAtcAcknowledgement(sender.getId(),
                    sender.level().getGameTime() + 200));
            ServerPlayNetworking.send(pilot, new AtcRadioPayload("ATC " + atcCallsign(sender)
                    + ": " + order, sender.getId()));
            pilot.sendSystemMessage(Component.literal("[ATC] " + atcCallsign(sender) + ": " + order));
            sendAtcLog(sender, "CALL TO " + atcCallsign(pilot) + ": " + order);
            return;
        }
        if (target instanceof Mob mob && MobFlightController.issueAtcOrder(sender, mob,
                payload.order(), payload.value(), payload.referenceEntityId())) {
            sendAtcLog(sender, "ACK FROM " + atcCallsign(mob) + ": order accepted.");
            return;
        }
        sender.sendSystemMessage(Component.literal("Target is not an active AI aircraft."));
    }

    private static void acknowledgeAtc(ServerPlayer pilot, AtcAckPayload payload) {
        PendingAtcAcknowledgement pending = PENDING_ATC_ACKS.remove(pilot.getUUID());
        if (pending == null || pending.controllerEntityId() != payload.controllerEntityId()
                || pilot.level().getGameTime() > pending.expiresAtTick()) {
            pilot.sendSystemMessage(Component.literal("No active ATC message to acknowledge."));
            return;
        }
        ServerPlayer controller = pilot.level().getServer().getPlayerList().getPlayers().stream()
                .filter(candidate -> candidate.getId() == payload.controllerEntityId()
                        && candidate.level() == pilot.level())
                .findFirst().orElse(null);
        if (controller == null || !pilot.level().getServer().getPlayerList().isOp(controller.nameAndId())) {
            pilot.sendSystemMessage(Component.literal("ATC controller is no longer available."));
            return;
        }
        String message = "ACK FROM " + atcCallsign(pilot) + ": message received.";
        ServerPlayNetworking.send(controller, new AtcRadioPayload(message));
        controller.sendSystemMessage(Component.literal("[ATC RADIO] " + message));
        pilot.sendSystemMessage(Component.literal("ATC message acknowledged."));
    }

    private static void sendAtcLog(ServerPlayer operator, String message) {
        ServerPlayNetworking.send(operator, new AtcRadioPayload(message));
        operator.sendSystemMessage(Component.literal("[ATC RADIO] " + message));
    }

    private static String atcCallsign(Entity entity) {
        if (entity instanceof ServerPlayer player) return player.getGameProfile().name();
        return String.format(java.util.Locale.ROOT, "AI-%03d", Math.floorMod(entity.getId(), 1000));
    }

    private record PendingAtcAcknowledgement(int controllerEntityId, long expiresAtTick) {
    }

    private static @org.jspecify.annotations.Nullable String atcOrderText(AtcOrderPayload payload) {
        return switch (payload.order()) {
            case AtcOrderPayload.HOLD -> "hold present heading and altitude";
            case AtcOrderPayload.TURN -> payload.value() >= 0 && payload.value() < 360
                    ? "turn heading " + String.format(java.util.Locale.ROOT, "%03d", payload.value()) : null;
            case AtcOrderPayload.CLIMB -> payload.value() >= 0 && payload.value() <= 40000
                    ? "climb and maintain " + payload.value() + " feet" : null;
            case AtcOrderPayload.DESCEND -> payload.value() >= 0 && payload.value() <= 40000
                    ? "descend and maintain " + payload.value() + " feet" : null;
            case AtcOrderPayload.SPEED -> payload.value() >= 40 && payload.value() <= 300
                    ? "maintain " + payload.value() + " knots" : null;
            case AtcOrderPayload.ORBIT -> "orbit my position";
            case AtcOrderPayload.RETURN -> "return to my position";
            case AtcOrderPayload.FOLLOW -> payload.referenceEntityId() >= 0
                    ? "follow aircraft " + payload.referenceEntityId() : null;
            default -> null;
        };
    }

    private static void giveFlightGuide(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            WrittenBookContent content = item.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content != null && "Flight Line Flight Guide".equals(content.title().raw())) return;
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<String> pages = List.of(
                "FLIGHT LINE\nPilot's field guide\n\nFlight is server-authoritative. Keep an eye on airspeed, pitch, bank, and radio altitude.",
                "FIRST FLIGHT · 1\nOn the ground, press the displayed START ENGINES key and wait for both engines to spool. Hold the brake key to stop; the BRAKES ON popup confirms they are applied. Release the brake, then add throttle with the forward control.",
                "FIRST FLIGHT · 2\nUse W / S (or your rebound controls) to increase / decrease throttle. Look up or down to control pitch, and use A / D to bank left / right. Build speed on a clear runway. Gently look up to rotate and take off. Avoid sharp turns; steep banks cost lift and speed.",
                "FIRST FLIGHT · 3\nAfter takeoff, climb with a modest nose-up attitude. Retract flaps gradually with FLAPS (-), retract spoilers, and raise the landing gear. Watch airspeed, altitude, EICAS, weather, and the warning stack. Press H for the autopilot panel.",
                "FIRST FLIGHT · 4\nTo set an ILS with /flightline ils set, stand at the runway threshold facing down the runway; or configure its threshold and heading in the flight radar. Align with the runway, descend on the glide path, lower gear and add flaps. Keep spoilers retracted until touchdown; use brakes during rollout.",
                "STARTUP & TAXI\nUse pushback if needed, then release brakes and taxi carefully. Flight Line creates 400-block concrete runways on safe flat Overworld sites as you explore; nearby pilots are told when one is built. Build speed for takeoff only on a clear runway. Avoid obstacles.",
                "FLIGHT CONTROLS\nForward/reverse increases/decreases throttle. Look up/down to change pitch; left/right banks the aircraft. Shallow banks preserve lift and speed. High angle of attack or low airspeed can cause a stall. Recover by easing the nose down and adding power.",
                "FLAPS & SPOILERS\nFlaps increase lift and drag, helping takeoff and approach at lower speeds. Retract them gradually after climb. Spoilers reduce lift and add drag; use them to descend or slow after touchdown, then retract for a go-around. Airbrakes add drag with only a small lift penalty, so they are the better choice for slowing in cruise or on a steep approach.",
                "GEAR & LANDING\nLower the landing gear before approach. Aim for a shallow, stable descent with modest bank and a safe airspeed. Flaps help approach; spoilers should be retracted before landing. Avoid high sink rate and excessive touchdown speed.",
                "ILS & AUTOLAND\nSet an ILS runway threshold and heading from the flight radar or autopilot panel. Intercept the localizer and glide slope. Arm autoland only when airborne, engines are running, airspeed is safe, and ILS guidance is valid. Monitor the approach; it is not a substitute for a safe runway.",
                "WEATHER & ANTI-ICE\nRain or storms in cold biomes can build airframe ice while airborne. Ice reduces lift and raises stall speed. Toggle anti-ice with its listed key. It clears ice only while at least one engine is running and engine spool is above 35%. It cannot prevent all ice buildup instantly; start it early and keep airspeed margin.",
                "WARNINGS & RECOVERY\nTreat windshear, terrain, stall, and TCAS resolution alerts as urgent. Reduce bank in turbulence, maintain airspeed, and avoid abrupt control changes. Overspeed is structural stress: reduce throttle; prolonged overspeed reduces lift and damages health. For an engine fire, follow the flashing EICAS shutdown prompt; for engine failure, maintain control and plan a safe landing.",
                "KEYS & ATC\nBottom-right prompts show relevant keys. F10 opens controls, where bindings can be checked and changed; F9 toggles prompts. Operators use /flightline atc to select and highlight traffic, optionally follow it with the camera, and send ATC orders. Player instructions are advisory; pilots stay in control. Press the displayed ACK key to acknowledge a radio call."
        );
        List<Filterable<Component>> bookPages = pages.stream()
                .map(page -> Filterable.<Component>passThrough(Component.literal(page))).toList();
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("Flight Line Flight Guide"), "Flight Line", 0, bookPages, false));
        if (!player.getInventory().add(book)) player.drop(book, false, Prediction.SERVER_ONLY);
        player.sendSystemMessage(Component.literal("A Flight Line pilot guide was added to your inventory."));
    }

    private static void notifyAircraftSelection(ServerPlayer player) {
        AircraftType type = FlightController.getAircraftType(player);
        ServerPlayNetworking.send(player, new SelectPlanePayload(type.id()));
        player.sendSystemMessage(Component.literal("§6[Flight Line] §fCurrent aircraft: §e" + type.displayName()
                + "§f. Change aircraft with §b/plane §for click: ")
                .append(Component.literal("§a[Kasa A380] ").withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/plane kasa"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Select Kasa A380 (Twin-engine widebody)")))))
                .append(Component.literal("§b[The Gina 787]").withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/plane gina"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Select The Gina 787 (6 Giant Engines · 200-210 kt · Damped)"))))));
    }

    @SuppressWarnings("null")
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("plane")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    AircraftType current = FlightController.getAircraftType(player);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "§6[Flight Line] §fCurrent aircraft: §e" + current.displayName() + "§7 (" + current.description() + ")\n"
                            + "§fClick to select: ")
                            .append(Component.literal("§a[Kasa A380] ")
                                    .withStyle(style -> style
                                            .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/plane kasa"))
                                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Switch to Kasa A380")))))
                            .append(Component.literal("§b[The Gina 787]")
                                    .withStyle(style -> style
                                            .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/plane gina"))
                                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Switch to The Gina 787 (6 Giant Engines · 200-210 kt · Damped)"))))), false);
                    return 1;
                })
                .then(Commands.argument("type", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            builder.suggest("kasa");
                            builder.suggest("gina");
                            builder.suggest("a380");
                            builder.suggest("787");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String arg = StringArgumentType.getString(context, "type");
                            AircraftType selected = AircraftType.fromId(arg);
                            FlightController.setAircraftType(player, selected);
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "§a[Flight Line] §fAircraft switched to §e" + selected.displayName()
                                    + "§7 (" + selected.engineCount() + " engines, " + selected.cruiseAirspeedKnots() + " kt cruise)."), false);
                            return 1;
                        }))
        );
        dispatcher.register(Commands.literal("disableflight").requires(
                        (@NonNull CommandSourceStack source) -> isOperator(source))
                .executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    FlightController.disableFlight(player);
                    context.getSource().sendSuccess(() -> Component.literal("Flight mode disabled."), false);
                    return 1;
                }));
        dispatcher.register(Commands.literal("enableflight").requires(
                        (@NonNull CommandSourceStack source) -> isOperator(source))
                .executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    FlightController.enableFlight(player);
                    if (!FlightController.isFlightActive(player)) {
                        context.getSource().sendFailure(Component.literal(
                                "Flight mode could not be enabled while dead, spectating, or riding."));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Flight mode enabled."), false);
                    return 1;
                }));
        dispatcher.register(Commands.literal("flightline")
                .then(Commands.literal("atc").requires(
                                (@NonNull CommandSourceStack source) -> isOperator(source))
                        .executes(context -> {
                            ServerPlayer operator = context.getSource().getPlayerOrException();
                            ServerPlayNetworking.send(operator, new OpenAtcPayload());
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Opening operator ATC."), false);
                            return 1;
                        }))
                .then(Commands.literal("ils")
                        .then(Commands.literal("set")
                                .executes(context -> setIlsReference(context.getSource().getPlayerOrException(),
                                        3.0f, context.getSource()))
                                .then(Commands.argument("glideSlope", FloatArgumentType.floatArg(1.0f, 6.0f))
                                        .executes(context -> setIlsReference(
                                                context.getSource().getPlayerOrException(),
                                                FloatArgumentType.getFloat(context, "glideSlope"),
                                                context.getSource()))))
                        .then(Commands.literal("clear")
                                .executes(context -> {
                                    var player = context.getSource().getPlayerOrException();
                                    if (!FlightController.clearIlsReference(player)) {
                                        context.getSource().sendFailure(Component.literal(
                                                "ILS is unavailable until your flight session is active."));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("ILS reference cleared."), false);
                                    return 1;
                                }))));
    }

    @SuppressWarnings("null")
    private static int setIlsReference(net.minecraft.server.level.ServerPlayer player, float glideSlopeDegrees,
                                       CommandSourceStack source) {
        if (!FlightController.setIlsReference(player, glideSlopeDegrees)) {
            source.sendFailure(Component.literal("ILS is unavailable until your flight session is active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(java.util.Objects.requireNonNull(String.format(
                java.util.Locale.ROOT,
                "ILS set at this position, runway heading %.0f°, glide slope %.1f°. Face down the runway at its threshold.",
                player.getYRot(), glideSlopeDegrees))), false);
        return 1;
    }
}
