package dev.flightline.flight;

import dev.flightline.network.FileFlightPlanPayload;
import dev.flightline.network.FlightPlanPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Server-owned flight plan with ATC status and active-leg tracking. */
public final class FlightPlan {
    public enum Status {
        DRAFT, FILED, CLEARED, ACTIVE, ARRIVAL;

        public static Status fromId(int id) {
            Status[] values = values();
            return id >= 0 && id < values.length ? values[id] : DRAFT;
        }

        public int id() {
            return ordinal();
        }
    }

    public record Leg(int x, int z, int id, String name) {
    }

    public record Validation(boolean valid, String message) {
        static Validation ok() {
            return new Validation(true, "PLAN ACCEPTED");
        }

        static Validation error(String message) {
            return new Validation(false, message);
        }
    }

    private final int originId;
    private final int destinationId;
    private final int alternateId;
    private final int cruiseAltitudeFeet;
    private final List<Integer> waypointIds;
    private Status status;
    private int activeLeg;
    private int departureRunwayId;
    private int arrivalRunwayId;
    private int revision;

    public FlightPlan(int originId, int destinationId, List<Integer> waypointIds,
                      int cruiseAltitudeFeet, int alternateId) {
        this.originId = originId;
        this.destinationId = destinationId;
        this.waypointIds = List.copyOf(waypointIds);
        this.cruiseAltitudeFeet = cruiseAltitudeFeet;
        this.alternateId = alternateId;
        this.status = Status.DRAFT;
    }

    public static Validation validate(NavDatabase database, int originId, int destinationId,
                                      List<Integer> waypointIds, int cruiseAltitudeFeet,
                                      int alternateId) {
        if (database == null) return Validation.error("NAV DATABASE UNAVAILABLE");
        if (database.airport(originId) == null) return Validation.error("UNKNOWN ORIGIN");
        if (database.airport(destinationId) == null) return Validation.error("UNKNOWN DESTINATION");
        if (originId == destinationId) return Validation.error("ORIGIN AND DESTINATION MATCH");
        if (database.airport(alternateId) == null) return Validation.error("UNKNOWN ALTERNATE");
        if (alternateId == originId || alternateId == destinationId) {
            return Validation.error("ALTERNATE MUST BE A THIRD AIRPORT");
        }
        if (cruiseAltitudeFeet < 100 || cruiseAltitudeFeet > 40_000) {
            return Validation.error("CRUISE ALTITUDE OUT OF RANGE");
        }
        if (waypointIds.size() > FileFlightPlanPayload.MAX_WAYPOINTS) {
            return Validation.error("TOO MANY WAYPOINTS");
        }
        Integer previous = null;
        for (int id : waypointIds) {
            if (database.fix(id) == null) return Validation.error("UNKNOWN WAYPOINT " + id);
            if (previous != null && id == previous) return Validation.error("DUPLICATE WAYPOINT " + id);
            previous = id;
        }
        return Validation.ok();
    }

    public int originId() {
        return originId;
    }

    public int destinationId() {
        return destinationId;
    }

    public int alternateId() {
        return alternateId;
    }

    public int cruiseAltitudeFeet() {
        return cruiseAltitudeFeet;
    }

    public List<Integer> waypointIds() {
        return waypointIds;
    }

    public Status status() {
        return status;
    }

    public int activeLeg() {
        return activeLeg;
    }

    public int departureRunwayId() {
        return departureRunwayId;
    }

    public int arrivalRunwayId() {
        return arrivalRunwayId;
    }

    public int revision() {
        return revision;
    }

    public void setStatus(Status status) {
        if (this.status != status) {
            this.status = status;
            revision++;
        }
    }

    public void setRunways(int departureRunwayId, int arrivalRunwayId) {
        if (this.departureRunwayId != departureRunwayId || this.arrivalRunwayId != arrivalRunwayId) {
            this.departureRunwayId = departureRunwayId;
            this.arrivalRunwayId = arrivalRunwayId;
            revision++;
        }
    }

    void restore(int statusId, int leg, int departureRunway, int arrivalRunway, int savedRevision) {
        this.status = Status.fromId(statusId);
        this.activeLeg = Math.max(0, leg);
        this.departureRunwayId = departureRunway;
        this.arrivalRunwayId = arrivalRunway;
        this.revision = savedRevision;
    }

    public boolean advanceLeg() {
        if (activeLeg >= legCount()) return false;
        activeLeg++;
        revision++;
        return true;
    }

    public void setActiveLeg(int leg) {
        int clamped = Math.max(0, Math.min(legCount(), leg));
        if (activeLeg != clamped) {
            activeLeg = clamped;
            revision++;
        }
    }

    public int legCount() {
        return waypointIds.size() + 1;
    }

    public List<Leg> legs(NavDatabase database) {
        List<Leg> legs = new ArrayList<>();
        for (int id : waypointIds) {
            var fix = database.fix(id);
            if (fix != null) legs.add(new Leg(fix.x(), fix.z(), fix.id(), fix.name()));
        }
        var destination = database.airport(destinationId);
        if (destination != null) {
            legs.add(new Leg(destination.centerX(), destination.centerZ(), destination.id(),
                    destination.name()));
        }
        return List.copyOf(legs);
    }

    public Leg activeLeg(NavDatabase database) {
        List<Leg> legs = legs(database);
        return activeLeg < legs.size() ? legs.get(activeLeg) : null;
    }

    public FlightPlanPayload toPayload() {
        return new FlightPlanPayload(revision, status.id(), activeLeg, originId, destinationId,
                alternateId, cruiseAltitudeFeet, departureRunwayId, arrivalRunwayId, waypointIds);
    }

    public static FlightPlan fromPayload(FlightPlanPayload payload) {
        FlightPlan plan = new FlightPlan(payload.originId(), payload.destinationId(),
                payload.waypointIds(), payload.cruiseAltitudeFeet(), payload.alternateId());
        plan.status = Status.fromId(payload.statusId());
        plan.activeLeg = Math.max(0, payload.activeLeg());
        plan.departureRunwayId = payload.departureRunwayId();
        plan.arrivalRunwayId = payload.arrivalRunwayId();
        plan.revision = payload.revision();
        return plan;
    }

    public String summary(NavDatabase database) {
        var origin = database.airport(originId);
        var destination = database.airport(destinationId);
        String from = origin == null ? "----" : origin.name();
        String to = destination == null ? "----" : destination.name();
        String runway = departureRunwayId > 0 ? " RWY " + runwayLabel(database, departureRunwayId) : "";
        return String.format(Locale.ROOT, "%s-%s FL%03d%s", from, to, cruiseAltitudeFeet / 100, runway);
    }

    private static String runwayLabel(NavDatabase database, int runwayId) {
        var runway = database.runway(runwayId);
        return runway == null ? "----" : runway.name();
    }
}
