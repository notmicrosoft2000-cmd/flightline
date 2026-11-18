package dev.flightline.flight;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.flightline.FlightLineIds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent per-player flight plans and ILS state. */
public final class FlightPlanStorage extends SavedData {
    public record IlsState(boolean active, float glideSlopeDegrees, int thresholdX, int thresholdZ,
                           float runwayHeadingDegrees, boolean autolandArmed, String dimension) {
        public IlsState(boolean active, float glideSlopeDegrees, int thresholdX, int thresholdZ,
                        float runwayHeadingDegrees, boolean autolandArmed) {
            this(active, glideSlopeDegrees, thresholdX, thresholdZ, runwayHeadingDegrees, autolandArmed, "");
        }

        public static final IlsState DEFAULT = new IlsState(false, 3.0f, 0, 0, 0.0f, false, "");

        static final Codec<IlsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("ils_active").forGetter(IlsState::active),
                Codec.FLOAT.fieldOf("glide_slope").forGetter(IlsState::glideSlopeDegrees),
                Codec.INT.fieldOf("threshold_x").forGetter(IlsState::thresholdX),
                Codec.INT.fieldOf("threshold_z").forGetter(IlsState::thresholdZ),
                Codec.FLOAT.fieldOf("runway_heading").forGetter(IlsState::runwayHeadingDegrees),
                Codec.BOOL.fieldOf("autoland").forGetter(IlsState::autolandArmed),
                Codec.STRING.optionalFieldOf("dimension", "").forGetter(IlsState::dimension)
        ).apply(instance, IlsState::new));
    }

    private static final Codec<FlightPlanStorage> CODEC = State.CODEC.comapFlatMap(
            state -> DataResult.success(new FlightPlanStorage(state)), FlightPlanStorage::state);

    public static final SavedDataType<FlightPlanStorage> TYPE = new SavedDataType<>(
            FlightLineIds.id("flight_plans"), FlightPlanStorage::new, CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private State state = new State(List.of());

    private FlightPlanStorage() {
    }

    private FlightPlanStorage(State state) {
        this.state = state;
    }

    private State state() {
        return state;
    }

    private record Entry(String uuid, int originId, int destinationId, int alternateId,
                         int cruiseAltitudeFeet, int statusId, int activeLeg,
                         int departureRunwayId, int arrivalRunwayId, int revision,
                         List<Integer> waypointIds, IlsState ils) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
                Codec.INT.fieldOf("origin").forGetter(Entry::originId),
                Codec.INT.fieldOf("destination").forGetter(Entry::destinationId),
                Codec.INT.fieldOf("alternate").forGetter(Entry::alternateId),
                Codec.INT.fieldOf("altitude").forGetter(Entry::cruiseAltitudeFeet),
                Codec.INT.fieldOf("status").forGetter(Entry::statusId),
                Codec.INT.fieldOf("leg").forGetter(Entry::activeLeg),
                Codec.INT.fieldOf("departure_runway").forGetter(Entry::departureRunwayId),
                Codec.INT.fieldOf("arrival_runway").forGetter(Entry::arrivalRunwayId),
                Codec.INT.fieldOf("revision").forGetter(Entry::revision),
                Codec.list(Codec.INT).fieldOf("waypoints").forGetter(Entry::waypointIds),
                IlsState.CODEC.optionalFieldOf("ils", IlsState.DEFAULT).forGetter(Entry::ils)
        ).apply(instance, Entry::new));
    }

    private record State(List<Entry> plans, List<Long> failedRunwaySites) {
        State(List<Entry> plans) {
            this(plans, List.of());
        }

        static final Codec<State> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.list(Entry.CODEC).fieldOf("plans").forGetter(State::plans),
                Codec.list(Codec.LONG).optionalFieldOf("failed_sites", List.of())
                        .forGetter(State::failedRunwaySites)
        ).apply(instance, State::new));
    }

    public static FlightPlanStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public @Nullable FlightPlan getPlan(UUID playerId) {
        Entry entry = entry(playerId);
        return entry == null || entry.originId() <= 0 ? null : fromEntry(entry);
    }

    public void setPlan(UUID playerId, @Nullable FlightPlan plan) {
        Map<String, Entry> entries = entries();
        Entry previous = entries.get(playerId.toString());
        IlsState ils = previous == null ? IlsState.DEFAULT : previous.ils();
        entries.put(playerId.toString(), toEntry(playerId, plan, ils));
        commit(entries);
    }

    public IlsState getIls(UUID playerId) {
        Entry entry = entry(playerId);
        return entry == null ? IlsState.DEFAULT : entry.ils();
    }

    public void setIls(UUID playerId, IlsState ils) {
        Map<String, Entry> entries = entries();
        Entry previous = entries.get(playerId.toString());
        FlightPlan plan = previous == null ? null : fromEntry(previous);
        entries.put(playerId.toString(), toEntry(playerId, plan, ils));
        commit(entries);
    }

    public void setAutolandArmed(UUID playerId, boolean armed) {
        Entry previous = entry(playerId);
        if (previous == null || previous.ils().autolandArmed() == armed) return;
        IlsState current = previous.ils();
        IlsState updated = new IlsState(current.active(), current.glideSlopeDegrees(),
                current.thresholdX(), current.thresholdZ(), current.runwayHeadingDegrees(),
                armed, current.dimension());
        Map<String, Entry> entries = entries();
        FlightPlan plan = previous.originId() > 0 ? fromEntry(previous) : null;
        entries.put(playerId.toString(), toEntry(playerId, plan, updated));
        commit(entries);
    }

    private @Nullable Entry entry(UUID playerId) {
        String key = playerId.toString();
        for (Entry candidate : state.plans()) {
            if (candidate.uuid().equals(key)) return candidate;
        }
        return null;
    }

    private Map<String, Entry> entries() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Entry entry : state.plans()) entries.put(entry.uuid(), entry);
        return entries;
    }

    private void commit(Map<String, Entry> entries) {
        state = new State(List.copyOf(entries.values()), state.failedRunwaySites());
        setDirty();
    }

    public boolean isRunwaySiteFailed(long siteKey) {
        return state.failedRunwaySites().contains(siteKey);
    }

    public void markRunwaySiteFailed(long siteKey) {
        if (state.failedRunwaySites().contains(siteKey)) return;
        List<Long> failed = new ArrayList<>(state.failedRunwaySites());
        failed.add(siteKey);
        state = new State(state.plans(), List.copyOf(failed));
        setDirty();
    }

    private static Entry toEntry(UUID playerId, @Nullable FlightPlan plan, IlsState ils) {
        if (plan == null) {
            return new Entry(playerId.toString(), 0, 0, 0, 0, FlightPlan.Status.DRAFT.id(), 0,
                    0, 0, 0, List.of(), ils);
        }
        return new Entry(playerId.toString(), plan.originId(), plan.destinationId(), plan.alternateId(),
                plan.cruiseAltitudeFeet(), plan.status().id(), plan.activeLeg(),
                plan.departureRunwayId(), plan.arrivalRunwayId(), plan.revision(), plan.waypointIds(), ils);
    }

    private static FlightPlan fromEntry(Entry entry) {
        FlightPlan plan = new FlightPlan(entry.originId(), entry.destinationId(), entry.waypointIds(),
                entry.cruiseAltitudeFeet(), entry.alternateId());
        plan.restore(entry.statusId(), entry.activeLeg(), entry.departureRunwayId(),
                entry.arrivalRunwayId(), entry.revision());
        return plan;
    }
}
