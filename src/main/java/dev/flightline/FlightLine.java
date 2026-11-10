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
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlightLine implements ModInitializer {
    public static final String MOD_ID = "flightline";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(AdjustFlapsPayload.TYPE, AdjustFlapsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleSpoilersPayload.TYPE, ToggleSpoilersPayload.CODEC);
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
                FlightController.toggleSpoilers(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ShutdownEnginesPayload.TYPE, (payload, context) ->
                FlightController.shutdownEngines(context.player()));

        ServerTickEvents.END_SERVER_TICK.register(FlightController::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                FlightController.remove(handler.player.getUUID(), handler.player));

        LOGGER.info("Flight Line initialized for Minecraft 26.3");
    }
}
