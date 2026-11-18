package dev.flightline.flight;

import dev.flightline.network.FileFlightPlanPayload;
import dev.flightline.network.FlightPlanPayload;
import dev.flightline.network.NavDataPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Server-side flight-plan filing, ATC clearance and authoritative state distribution. */
public final class FlightPlanService {
    private FlightPlanService() {
    }

    public static FlightPlan loadPlan(ServerPlayer player) {
        return FlightPlanStorage.get(player.level().getServer()).getPlan(player.getUUID());
    }

    /** Returns the session plan when flying, otherwise the persisted plan shown to a reconnected client. */
    private static FlightPlan authoritativePlan(ServerPlayer player) {
        FlightPlan plan = FlightController.getFlightPlan(player);
        return plan != null ? plan : loadPlan(player);
    }

    public static FlightPlanStorage.IlsState loadIls(ServerPlayer player) {
        return FlightPlanStorage.get(player.level().getServer()).getIls(player.getUUID());
    }

    public static void saveIls(ServerPlayer player, FlightPlanStorage.IlsState state) {
        FlightPlanStorage.get(player.level().getServer()).setIls(player.getUUID(), state);
    }

    public static void clearAutolandArming(ServerPlayer player) {
        FlightPlanStorage.get(player.level().getServer()).setAutolandArmed(player.getUUID(), false);
    }

    public static void handle(ServerPlayer player, FileFlightPlanPayload payload) {
        switch (payload.action()) {
            case FILE -> file(player, payload);
            case CLEARANCE -> clearance(player, payload);
            case CANCEL -> cancel(player);
            case LNAV -> engageLnav(player);
            case APPROACH -> engageApproach(player);
            case SYNC -> syncAltitude(player);
        }
    }

    private static void file(ServerPlayer player, FileFlightPlanPayload payload) {
        NavDatabase database = database(player);
        FlightPlan.Validation validation = FlightPlan.validate(database, payload.originId(),
                payload.destinationId(), payload.waypointIds(), payload.cruiseAltitudeFeet(),
                payload.alternateId());
        if (!validation.valid()) {
            reject(player, validation.message());
            return;
        }
        FlightPlan plan = new FlightPlan(payload.originId(), payload.destinationId(),
                payload.waypointIds(), payload.cruiseAltitudeFeet(), payload.alternateId());
        plan.setStatus(FlightPlan.Status.FILED);
        FlightController.setFlightPlan(player, plan);
        onPlanChanged(player, plan);
        radio(player, "Flight plan filed " + plan.summary(database) + ". Request clearance when ready.");
    }

    private static void clearance(ServerPlayer player, FileFlightPlanPayload payload) {
        NavDatabase database = database(player);
        FlightPlan plan = authoritativePlan(player);
        if (plan != null && (plan.status() == FlightPlan.Status.ACTIVE
                || plan.status() == FlightPlan.Status.ARRIVAL)) {
            reject(player, "PLAN ALREADY ACTIVE");
            return;
        }
        boolean matches = plan != null && plan.originId() == payload.originId()
                && plan.destinationId() == payload.destinationId()
                && plan.alternateId() == payload.alternateId()
                && plan.cruiseAltitudeFeet() == payload.cruiseAltitudeFeet()
                && plan.waypointIds().equals(payload.waypointIds());
        if (!matches) {
            FlightPlan.Validation validation = FlightPlan.validate(database, payload.originId(),
                    payload.destinationId(), payload.waypointIds(), payload.cruiseAltitudeFeet(),
                    payload.alternateId());
            if (!validation.valid()) {
                reject(player, validation.message());
                return;
            }
            plan = new FlightPlan(payload.originId(), payload.destinationId(), payload.waypointIds(),
                    payload.cruiseAltitudeFeet(), payload.alternateId());
        }
        RunwayAssignment assignment = assignRunways(player, plan);
        if (assignment == null) return;
        plan.setStatus(FlightPlan.Status.CLEARED);
        FlightController.setFlightPlan(player, plan);
        onPlanChanged(player, plan);
        radio(player, "Cleared to " + name(database, plan.destinationId()) + " via "
                + fixes(database, plan) + ", maintain FL" + String.format(Locale.ROOT, "%03d",
                        plan.cruiseAltitudeFeet() / 100) + ", departure runway "
                + assignment.departure().name() + ", arrival runway " + assignment.arrival().name() + ".");
    }

    private static void cancel(ServerPlayer player) {
        if (authoritativePlan(player) == null) {
            radio(player, "No active flight plan to cancel.");
            return;
        }
        FlightController.setFlightPlan(player, null);
        FlightController.setNavMode(player, NavMode.HDG);
        FlightPlanStorage.get(player.level().getServer()).setPlan(player.getUUID(), null);
        sendPlan(player, null);
        radio(player, "Flight plan cancelled. Proceed on present heading.");
    }

    private static void engageLnav(ServerPlayer player) {
        FlightPlan plan = authoritativePlan(player);
        if (plan == null) {
            reject(player, "NO FLIGHT PLAN");
            return;
        }
        if (!FlightController.isAirborne(player)) {
            reject(player, "AIRBORNE REQUIRED FOR LNAV");
            return;
        }
        if (plan.departureRunwayId() <= 0 || plan.arrivalRunwayId() <= 0) {
            if (assignRunways(player, plan) == null) return;
        }
        if (plan.status() == FlightPlan.Status.FILED) {
            plan.setStatus(FlightPlan.Status.CLEARED);
        }
        plan.setStatus(FlightPlan.Status.ACTIVE);
        FlightController.setNavMode(player, NavMode.LNAV);
        onPlanChanged(player, plan);
        radio(player, "LNAV engaged. Tracking " + activeLegName(player.level().getServer(), plan) + ".");
    }

    private static void engageApproach(ServerPlayer player) {
        FlightPlan plan = authoritativePlan(player);
        if (plan == null || plan.arrivalRunwayId() <= 0) {
            reject(player, "NO ASSIGNED APPROACH");
            return;
        }
        var runway = database(player).runway(plan.arrivalRunwayId());
        if (runway == null || !FlightController.tuneApproach(player, runway)) {
            reject(player, "ILS TUNING REJECTED");
            return;
        }
        plan.setStatus(FlightPlan.Status.ARRIVAL);
        onPlanChanged(player, plan);
        radio(player, "ILS runway " + runway.name() + " tuned. Approach mode active.");
    }

    private static void syncAltitude(ServerPlayer player) {
        FlightPlan plan = authoritativePlan(player);
        if (plan == null) {
            reject(player, "NO FLIGHT PLAN");
            return;
        }
        if (!FlightController.syncCruiseAltitude(player, plan.cruiseAltitudeFeet())) {
            reject(player, "ALTITUDE SYNC REJECTED");
            return;
        }
        radio(player, "Cruise altitude FL" + String.format(Locale.ROOT, "%03d",
                plan.cruiseAltitudeFeet() / 100) + " loaded into the autopilot.");
    }

    public static void onPlanChanged(ServerPlayer player, FlightPlan plan) {
        FlightPlanStorage.get(player.level().getServer()).setPlan(player.getUUID(), plan);
        sendPlan(player, plan);
    }

    public static void sendPlan(ServerPlayer player, FlightPlan plan) {
        FlightPlanPayload payload = plan == null
                ? new FlightPlanPayload(0, FlightPlan.Status.DRAFT.id(), 0, 0, 0, 0, 0, 0, 0, List.of())
                : plan.toPayload();
        ServerPlayNetworking.send(player, payload);
    }

    public static void refresh(ServerPlayer player, FlightPlan plan) {
        if (plan != null) sendPlan(player, plan);
    }

    private static void reject(ServerPlayer player, String reason) {
        radio(player, "Flight plan rejected: " + reason + ".");
    }

    private static void radio(ServerPlayer player, String message) {
        ServerPlayNetworking.send(player,
                new dev.flightline.network.AtcRadioPayload(message, -1));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[ATC] " + message));
    }

    private record RunwayAssignment(NavDataPayload.Runway departure,
                                    NavDataPayload.Runway arrival) {
    }

    private static RunwayAssignment assignRunways(ServerPlayer player, FlightPlan plan) {
        NavDatabase database = database(player);
        long gameTime = player.level().getGameTime();
        boolean raining = player.level().isRaining();
        boolean thundering = player.level().isThundering();
        var departure = database.bestRunway(plan.originId(), raining, thundering, gameTime);
        var arrival = database.bestRunway(plan.destinationId(), raining, thundering, gameTime);
        if (departure == null || arrival == null) {
            reject(player, "RUNWAY ASSIGNMENT UNAVAILABLE");
            return null;
        }
        plan.setRunways(departure.id(), arrival.id());
        return new RunwayAssignment(departure, arrival);
    }

    private static NavDatabase database(ServerPlayer player) {
        return NavDatabase.get(player.level().getServer());
    }

    private static String name(NavDatabase database, int airportId) {
        var airport = database.airport(airportId);
        return airport == null ? "----" : airport.name();
    }

    private static String fixes(NavDatabase database, FlightPlan plan) {
        if (plan.waypointIds().isEmpty()) return "DIRECT";
        List<String> names = new ArrayList<>();
        for (int id : plan.waypointIds()) {
            var fix = database.fix(id);
            names.add(fix == null ? String.valueOf(id) : fix.name());
        }
        return String.join(" ", names);
    }

    private static String activeLegName(net.minecraft.server.MinecraftServer server, FlightPlan plan) {
        var leg = plan.activeLeg(NavDatabase.get(server));
        return leg == null ? "destination" : leg.name();
    }
}
