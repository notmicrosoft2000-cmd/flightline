package dev.flightline.client;

import dev.flightline.flight.NavGuidance;
import dev.flightline.network.FlightPlanPayload;
import dev.flightline.network.NavDataPayload;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Client-side cache of the one-time navigation snapshot and authoritative flight plan. */
public final class NavigationState {
    private static volatile @Nullable NavDataPayload navData;
    private static volatile @Nullable FlightPlanPayload plan;

    private NavigationState() {
    }

    public record RouteLeg(int x, int z, int id, String name, boolean airport) {
    }

    public static void setNavData(NavDataPayload payload) {
        navData = payload;
    }

    public static void setPlan(FlightPlanPayload payload) {
        plan = payload.originId() > 0 && payload.destinationId() > 0 ? payload : null;
    }

    public static void reset() {
        navData = null;
        plan = null;
    }

    public static boolean hasNavData() {
        return navData != null;
    }

    public static List<NavDataPayload.Airport> airports() {
        return navData == null ? List.of() : navData.airports();
    }

    public static List<NavDataPayload.Fix> fixes() {
        return navData == null ? List.of() : navData.fixes();
    }

    public static NavDataPayload.@Nullable Airport airport(int id) {
        if (navData == null) return null;
        for (var airport : navData.airports()) {
            if (airport.id() == id) return airport;
        }
        return null;
    }

    public static NavDataPayload.@Nullable Runway runway(int id) {
        if (navData == null) return null;
        for (var runway : navData.runways()) {
            if (runway.id() == id) return runway;
        }
        return null;
    }

    public static NavDataPayload.@Nullable Fix fix(int id) {
        if (navData == null) return null;
        for (var fix : navData.fixes()) {
            if (fix.id() == id) return fix;
        }
        return null;
    }

    public static @Nullable FlightPlanPayload plan() {
        return plan;
    }

    public static boolean hasPlan() {
        return plan != null;
    }

    public static List<RouteLeg> legs() {
        FlightPlanPayload current = plan;
        if (current == null || navData == null) return List.of();
        List<RouteLeg> legs = new ArrayList<>();
        for (int id : current.waypointIds()) {
            var fix = fix(id);
            if (fix != null) legs.add(new RouteLeg(fix.x(), fix.z(), fix.id(), fix.name(), false));
        }
        var destination = airport(current.destinationId());
        if (destination != null) {
            legs.add(new RouteLeg(destination.centerX(), destination.centerZ(), destination.id(),
                    destination.name(), true));
        }
        return List.copyOf(legs);
    }

    public static @Nullable RouteLeg activeLeg() {
        FlightPlanPayload current = plan;
        List<RouteLeg> legs = legs();
        if (current == null || legs.isEmpty()) return null;
        int index = Math.max(0, Math.min(legs.size() - 1, current.activeLeg()));
        return legs.get(index);
    }

    /** Signed cross-track error for the active leg, using the origin airport as the first leg start. */
    public static double crossTrackBlocks(double ownX, double ownZ) {
        FlightPlanPayload current = plan;
        RouteLeg leg = activeLeg();
        if (current == null || leg == null) return 0.0;
        List<RouteLeg> legs = legs();
        int index = Math.max(0, Math.min(legs.size() - 1, current.activeLeg()));
        double startX = ownX;
        double startZ = ownZ;
        if (index > 0) {
            RouteLeg previous = legs.get(index - 1);
            startX = previous.x();
            startZ = previous.z();
        } else {
            var origin = airport(current.originId());
            if (origin != null) {
                startX = origin.centerX();
                startZ = origin.centerZ();
            }
        }
        return NavGuidance.crossTrackBlocks(ownX, ownZ, startX, startZ, leg.x(), leg.z());
    }

    public static int eteMinutes(double ownX, double ownZ, double speedKnots) {
        RouteLeg leg = activeLeg();
        if (leg == null || speedKnots <= 0.0) return 0;
        double distance = NavGuidance.distanceBlocks(ownX, ownZ, leg.x(), leg.z());
        return (int) Math.ceil(NavGuidance.travelMinutes(distance, speedKnots));
    }

    public static String statusLabel() {
        FlightPlanPayload current = plan;
        if (current == null) return "NO PLAN";
        return switch (current.statusId()) {
            case 1 -> "FILED";
            case 2 -> "CLEARED";
            case 3 -> "ACTIVE";
            case 4 -> "ARRIVAL";
            default -> "DRAFT";
        };
    }
}
