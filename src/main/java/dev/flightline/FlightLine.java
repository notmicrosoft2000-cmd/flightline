package dev.flightline;

import dev.flightline.flight.FlightController;
import dev.flightline.network.FlightInputPayload;
import dev.flightline.network.FlightInstrumentPayload;
import dev.flightline.network.FlightStatePayload;
import dev.flightline.network.StartEnginesPayload;
import dev.flightline.network.TogglePushbackPayload;
import dev.flightline.network.ToggleGearPayload;
import dev.flightline.network.AdjustFlapsPayload;
import dev.flightline.network.ToggleSpoilersPayload;
import dev.flightline.network.ShutdownEnginesPayload;
import dev.flightline.network.ToggleAutopilotPayload;
import dev.flightline.network.ConfigureAutopilotPayload;
import dev.flightline.network.ConfigureIlsPayload;
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
import net.minecraft.sounds.SoundEvent;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlightLine implements ModInitializer {
    public static final String MOD_ID = "flightline";
    public static final Logger LOGGER = java.util.Objects.requireNonNull(LoggerFactory.getLogger(MOD_ID));
    @SuppressWarnings("null")
    public static final SoundEvent ENGINE_LOOP = Registry.register(BuiltInRegistries.SOUND_EVENT,
            id("engine_loop"), SoundEvent.createVariableRangeEvent(id("engine_loop")));
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
        PayloadTypeRegistry.serverboundPlay().register(ToggleAutopilotPayload.TYPE, ToggleAutopilotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigureAutopilotPayload.TYPE, ConfigureAutopilotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigureIlsPayload.TYPE, ConfigureIlsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShutdownEnginesPayload.TYPE, ShutdownEnginesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FlightInputPayload.TYPE, FlightInputPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleGearPayload.TYPE, ToggleGearPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StartEnginesPayload.TYPE, StartEnginesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TogglePushbackPayload.TYPE, TogglePushbackPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FlightStatePayload.TYPE, FlightStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FlightInstrumentPayload.TYPE, FlightInstrumentPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(FlightInputPayload.TYPE, (payload, context) -> {
            FlightController.updateInput(context.player(), payload.controls());
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
        ServerPlayNetworking.registerGlobalReceiver(ShutdownEnginesPayload.TYPE, (payload, context) ->
                FlightController.shutdownEngines(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ToggleAutopilotPayload.TYPE, (payload, context) ->
                FlightController.toggleAutopilot(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ConfigureAutopilotPayload.TYPE, (payload, context) ->
                FlightController.configureAutopilot(context.player(), payload.engaged(), payload.targetHeading(),
                        payload.targetAltitudeFeet(), payload.targetVerticalSpeedFpm(), payload.targetAirspeedKnots()));
        ServerPlayNetworking.registerGlobalReceiver(ConfigureIlsPayload.TYPE, (payload, context) -> {
            if (payload.active()) {
                FlightController.setIlsReference(context.player(), payload.glideSlopeDegrees());
            } else {
                FlightController.clearIlsReference(context.player());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(FlightController::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                FlightController.remove(handler.player.getUUID(), handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                FlightController.resetAfterRespawn(oldPlayer, newPlayer));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));

        LOGGER.info("Flight Line initialized for Minecraft 26.3");
    }

    private static boolean isOperator(CommandSourceStack source) {
        return source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && source.getServer().getPlayerList().isOp(player.nameAndId());
    }

    @SuppressWarnings("null")
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
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
