package dev.flightline.flight;

import dev.flightline.network.NavDataPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Server-owned navigation data: airports, runways and fixes derived from the runway grid. */
public final class NavDatabase {
    private static NavDatabase instance;
    private static MinecraftServer owner;

    private final int spawnX;
    private final int spawnZ;
    private final List<NavDataPayload.Airport> airports;
    private final List<NavDataPayload.Runway> runways;
    private final List<NavDataPayload.Fix> fixes;
    private final Map<Integer, NavDataPayload.Airport> airportsById;
    private final Map<Integer, NavDataPayload.Runway> runwaysById;
    private final Map<Integer, NavDataPayload.Fix> fixesById;

    @FunctionalInterface
    interface HeightSampler {
        int height(int x, int z);
    }

    @FunctionalInterface
    interface SiteFilter {
        boolean accepts(RunwayGenerator.Site site);
    }

    private NavDatabase(MinecraftServer server) {
        this(spawnOf(server), minYOf(server), heightOf(server),
                site -> RunwayGenerator.isSiteAvailable(server.overworld(), site));
    }

    private static BlockPos spawnOf(MinecraftServer server) {
        return server.overworld().getRespawnData().pos();
    }

    private static int minYOf(MinecraftServer server) {
        return server.overworld().getMinY();
    }

    private static HeightSampler heightOf(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    static NavDatabase create(BlockPos spawn, int minY, HeightSampler height) {
        return new NavDatabase(spawn, minY, height, site -> true);
    }

    private NavDatabase(BlockPos spawn, int minY, HeightSampler height, SiteFilter filter) {
        this.spawnX = spawn.getX();
        this.spawnZ = spawn.getZ();

        List<NavDataPayload.Airport> airportList = new ArrayList<>();
        List<NavDataPayload.Runway> runwayList = new ArrayList<>();
        List<NavDataPayload.Fix> fixList = new ArrayList<>();

        for (int cellX = -1; cellX <= 1; cellX++) {
            for (int cellZ = -1; cellZ <= 1; cellZ++) {
                RunwayGenerator.Site site = RunwayGenerator.siteFor(spawn, cellX, cellZ);
                if (!filter.accepts(site)) continue;
                int airportId = (cellX + 2) * 10 + (cellZ + 2);
                int elevation = Math.max(minY, height.height(site.centerX(), site.centerZ()));

                float primaryHeading = site.alongX() ? 270.0f : 0.0f;
                float secondaryHeading = primaryHeading + 180.0f;
                int primaryId = airportId * 10 + 1;
                int secondaryId = airportId * 10 + 2;
                runwayList.add(runway(primaryId, airportId, primaryHeading, site));
                runwayList.add(runway(secondaryId, airportId, secondaryHeading, site));

                airportList.add(new NavDataPayload.Airport(airportId, "FL" + airportId,
                        site.centerX(), site.centerZ(), elevation, List.of(primaryId, secondaryId)));

                fixList.add(new NavDataPayload.Fix(airportId * 10 + 5, "F" + airportId,
                        site.centerX(), site.centerZ(), NavDataPayload.Fix.WAYPOINT));
                fixList.add(new NavDataPayload.Fix(airportId * 10 + 6, "V" + airportId,
                        site.centerX() + 512, site.centerZ(), NavDataPayload.Fix.VOR));
            }
        }

        this.airports = List.copyOf(airportList);
        this.runways = List.copyOf(runwayList);
        this.fixes = List.copyOf(fixList);
        this.airportsById = index(this.airports, NavDataPayload.Airport::id);
        this.runwaysById = index(this.runways, NavDataPayload.Runway::id);
        this.fixesById = index(this.fixes, NavDataPayload.Fix::id);
    }

    private static NavDataPayload.Runway runway(int id, int airportId, float heading,
                                                RunwayGenerator.Site site) {
        double[] vector = headingVector(heading);
        int thresholdX = (int) Math.round(site.centerX() - vector[0] * RunwayGenerator.RUNWAY_LENGTH / 2.0);
        int thresholdZ = (int) Math.round(site.centerZ() - vector[1] * RunwayGenerator.RUNWAY_LENGTH / 2.0);
        return new NavDataPayload.Runway(id, airportId, runwayName(heading), thresholdX, thresholdZ,
                wrapDegrees(heading), 3.0f);
    }

    private static String runwayName(float heading) {
        int compass = Math.round(wrapDegrees(heading + 180.0f) / 10.0f);
        if (compass == 0) compass = 36;
        return String.format(Locale.ROOT, "%02d", compass);
    }

    static double[] headingVector(double heading) {
        double radians = Math.toRadians(heading);
        return new double[]{-Math.sin(radians), Math.cos(radians)};
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        return wrapped < 0.0f ? wrapped + 360.0f : wrapped;
    }

    private static <T> Map<Integer, T> index(List<T> values, java.util.function.ToIntFunction<T> key) {
        Map<Integer, T> map = new LinkedHashMap<>();
        for (T value : values) map.put(key.applyAsInt(value), value);
        return Map.copyOf(map);
    }

    public static NavDatabase get(MinecraftServer server) {
        if (instance == null || owner != server) {
            owner = server;
            instance = new NavDatabase(server);
        }
        return instance;
    }

    public static NavDatabase current() {
        return instance;
    }

    public static void clear() {
        instance = null;
        owner = null;
    }

    public int spawnX() {
        return spawnX;
    }

    public int spawnZ() {
        return spawnZ;
    }

    public List<NavDataPayload.Airport> airports() {
        return airports;
    }

    public List<NavDataPayload.Runway> runways() {
        return runways;
    }

    public List<NavDataPayload.Fix> fixes() {
        return fixes;
    }

    public NavDataPayload.Airport airport(int id) {
        return airportsById.get(id);
    }

    public NavDataPayload.Runway runway(int id) {
        return runwaysById.get(id);
    }

    public NavDataPayload.Fix fix(int id) {
        return fixesById.get(id);
    }

    public NavDataPayload.Airport nearestAirport(int x, int z) {
        return airports.stream().min(Comparator.comparingDouble(airport -> {
            double dx = airport.centerX() - x;
            double dz = airport.centerZ() - z;
            return dx * dx + dz * dz;
        })).orElse(null);
    }

    /** Selects the runway end with the strongest headwind component. */
    public NavDataPayload.Runway bestRunway(int airportId, boolean raining, boolean thundering,
                                            long gameTime) {
        NavDataPayload.Airport airport = airport(airportId);
        if (airport == null) return null;
        WeatherField.Sample weather = WeatherField.sample(airport.centerX(), airport.centerZ(),
                airport.elevationY(), raining, thundering, 0.8f, gameTime);
        double[] flow = WeatherField.windVector(weather.surfaceWindDirectionDegrees(), 1.0);
        NavDataPayload.Runway best = null;
        double bestComponent = Double.POSITIVE_INFINITY;
        for (int id : airport.runwayIds()) {
            NavDataPayload.Runway runway = runway(id);
            if (runway == null) continue;
            double[] vector = headingVector(runway.headingDegrees());
            double component = flow[0] * vector[0] + flow[1] * vector[1];
            if (component < bestComponent) {
                bestComponent = component;
                best = runway;
            }
        }
        return best;
    }

    public NavDataPayload snapshot() {
        return new NavDataPayload(NavDataPayload.VERSION, spawnX, spawnZ, airports, runways, fixes);
    }
}
