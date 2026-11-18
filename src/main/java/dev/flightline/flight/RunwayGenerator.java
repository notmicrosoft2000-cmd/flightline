package dev.flightline.flight;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Builds surveyed 400-block runways on safe terrain as players explore the Overworld. */
public final class RunwayGenerator {
    public static final int RUNWAY_LENGTH = 400;
    public static final int RUNWAY_WIDTH = 28;
    public static final int GRID_SPACING = 8192;
    private static final int TRIGGER_DISTANCE = 900;
    private static final int MAX_TERRAIN_VARIANCE = 2;
    private static final int WORK_PER_TICK = 256;
    private static final Set<Long> DISCOVERED_SITES = new HashSet<>();
    private static final Queue<SiteTask> QUEUED_SITES = new ArrayDeque<>();
    private static SiteTask activeSite;
    private static MinecraftServer currentServer;

    private RunwayGenerator() {
    }

    public static void tick(MinecraftServer server) {
        if (currentServer != server) {
            currentServer = server;
            DISCOVERED_SITES.clear();
            QUEUED_SITES.clear();
            activeSite = null;
        }
        ServerLevel level = server.overworld();
        if (server.getTickCount() % 100 == 0) discoverNearbySites(server, level);
        if (activeSite == null) activeSite = QUEUED_SITES.poll();
        if (activeSite != null && activeSite.advance()) activeSite = null;
    }

    private static void discoverNearbySites(MinecraftServer server, ServerLevel level) {
        BlockPos spawn = server.overworld().getRespawnData().pos();
        int trackingRadius = Math.max(server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance()) * 16;
        int triggerRadius = Math.max(TRIGGER_DISTANCE, trackingRadius);
        double triggerSquared = (double) triggerRadius * triggerRadius;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level || !player.isAlive()) continue;
            int cellX = Math.floorDiv(player.getBlockX() - spawn.getX(), GRID_SPACING);
            int cellZ = Math.floorDiv(player.getBlockZ() - spawn.getZ(), GRID_SPACING);
            for (int x = cellX - 1; x <= cellX + 1; x++) {
                for (int z = cellZ - 1; z <= cellZ + 1; z++) {
                    Site site = siteFor(spawn, x, z);
                    if (site.distanceSquared(player.getBlockX(), player.getBlockZ()) > triggerSquared) continue;
                    long key = site.key();
                    if (!DISCOVERED_SITES.add(key)) continue;
                    if (!isSiteAvailable(level, site)) continue;
                    if (runwayAlreadyExists(level, site)) continue;
                    QUEUED_SITES.add(new SiteTask(level, site));
                }
            }
        }
    }

    public static Site siteFor(BlockPos spawn, int cellX, int cellZ) {
        long seed = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        seed = mix(seed);
        int offsetX = Math.floorMod((int) seed, 1025) - 512;
        seed = mix(seed);
        int offsetZ = Math.floorMod((int) seed, 1025) - 512;
        boolean alongX = (mix(seed) & 1L) == 0L;
        int centerX = spawn.getX() + cellX * GRID_SPACING + offsetX;
        int centerZ = spawn.getZ() + cellZ * GRID_SPACING + offsetZ;
        return new Site(centerX, centerZ, alongX);
    }

    /** Cheap validity check used by the navigation database before advertising a site. */
    public static boolean isSiteAvailable(ServerLevel level, Site site) {
        return insideWorldBorder(level, site)
                && !FlightPlanStorage.get(level.getServer()).isRunwaySiteFailed(site.key());
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static boolean runwayAlreadyExists(ServerLevel level, Site site) {
        int chunkX = site.centerX >> 4;
        int chunkZ = site.centerZ >> 4;
        if (!level.hasChunk(chunkX, chunkZ)) return false;
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.centerX, site.centerZ);
        return groundY > level.getMinY()
                && level.getBlockState(new BlockPos(site.centerX, groundY - 1, site.centerZ))
                .is(Blocks.CONCRETE.white());
    }

    private static boolean insideWorldBorder(ServerLevel level, Site site) {
        int alongHalf = RUNWAY_LENGTH / 2;
        int acrossHalf = RUNWAY_WIDTH / 2;
        int minX = site.centerX - (site.alongX ? alongHalf : acrossHalf);
        int maxX = site.centerX + (site.alongX ? alongHalf : acrossHalf);
        int minZ = site.centerZ - (site.alongX ? acrossHalf : alongHalf);
        int maxZ = site.centerZ + (site.alongX ? acrossHalf : alongHalf);
        return level.getWorldBorder().isWithinBounds(minX, minZ)
                && level.getWorldBorder().isWithinBounds(maxX, maxZ);
    }

    public record Site(int centerX, int centerZ, boolean alongX) {
        public long key() {
            return ((long) centerX << 32) ^ (centerZ & 0xFFFFFFFFL);
        }

        private double distanceSquared(int x, int z) {
            double dx = centerX - (double) x;
            double dz = centerZ - (double) z;
            return dx * dx + dz * dz;
        }

        private int xAt(int along, int across) {
            return alongX ? centerX + along : centerX + across;
        }

        private int zAt(int along, int across) {
            return alongX ? centerZ + across : centerZ + along;
        }
    }

    private static final class SiteTask {
        private final ServerLevel level;
        private final Site site;
        private final List<ChunkPos> chunks;
        private final int[] terrainHeights = new int[RUNWAY_LENGTH * RUNWAY_WIDTH];
        private int chunkIndex;
        private int scanIndex;
        private int buildIndex;
        private int minimumY = Integer.MAX_VALUE;
        private int maximumY = Integer.MIN_VALUE;
        private long heightTotal;
        private int surfaceY;
        private Stage stage = Stage.LOAD;

        private SiteTask(ServerLevel level, Site site) {
            this.level = level;
            this.site = site;
            this.chunks = runwayChunks(site);
        }

        private boolean advance() {
            return switch (stage) {
                case LOAD -> loadChunks();
                case SURVEY -> surveyTerrain();
                case BUILD -> buildRunway();
                case DONE -> true;
            };
        }

        private boolean loadChunks() {
            int end = Math.min(chunks.size(), chunkIndex + 2);
            while (chunkIndex < end) {
                ChunkPos chunk = chunks.get(chunkIndex++);
                level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, true);
            }
            if (chunkIndex >= chunks.size()) {
                if (runwayAlreadyExists(level, site)) return complete();
                stage = Stage.SURVEY;
            }
            return false;
        }

        private boolean surveyTerrain() {
            int end = Math.min(terrainHeights.length, scanIndex + WORK_PER_TICK);
            while (scanIndex < end) {
                int index = scanIndex++;
                int alongIndex = index / RUNWAY_WIDTH;
                int acrossIndex = index % RUNWAY_WIDTH;
                int along = alongIndex - RUNWAY_LENGTH / 2;
                int across = acrossIndex - RUNWAY_WIDTH / 2;
                int x = site.xAt(along, across);
                int z = site.zAt(along, across);
                int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (!insideWorldBorder(x, z) || !naturalFlatColumn(x, z, groundY)) return fail();
                terrainHeights[index] = groundY;
                minimumY = Math.min(minimumY, groundY);
                maximumY = Math.max(maximumY, groundY);
                heightTotal += groundY;
                if (maximumY - minimumY > MAX_TERRAIN_VARIANCE) return fail();
            }
            if (scanIndex >= terrainHeights.length) {
                surfaceY = Math.toIntExact(Math.round(heightTotal / (double) terrainHeights.length)) - 1;
                if (surfaceY <= level.getMinY()) return fail();
                if (hasEntityOnRunway()) return false;
                stage = Stage.BUILD;
            }
            return false;
        }

        private boolean naturalFlatColumn(int x, int z, int groundY) {
            if (groundY <= level.getMinY() || groundY + 5 >= level.getMaxY()) return false;
            BlockState surface = level.getBlockState(new BlockPos(x, groundY - 1, z));
            if (!isNaturalSurface(surface) || !surface.getFluidState().isEmpty()) return false;
            for (int y = groundY; y < groundY + 5; y++) {
                BlockState above = level.getBlockState(new BlockPos(x, y, z));
                if (!above.isAir() && !above.canBeReplaced()) return false;
            }
            return true;
        }

        private boolean insideWorldBorder(int x, int z) {
            return level.getWorldBorder().isWithinBounds(x, z);
        }

        private boolean hasEntityOnRunway() {
            int halfLength = RUNWAY_LENGTH / 2;
            int halfWidth = RUNWAY_WIDTH / 2;
            int minX = site.centerX - (site.alongX ? halfLength : halfWidth);
            int maxX = site.centerX + (site.alongX ? halfLength : halfWidth);
            int minZ = site.centerZ - (site.alongX ? halfWidth : halfLength);
            int maxZ = site.centerZ + (site.alongX ? halfWidth : halfLength);
            AABB area = new AABB(minX, surfaceY, minZ, maxX, surfaceY + 5, maxZ);
            return !level.getEntitiesOfClass(Entity.class, area, Entity::isAlive).isEmpty();
        }

        private boolean buildRunway() {
            int end = Math.min(terrainHeights.length, buildIndex + WORK_PER_TICK);
            while (buildIndex < end) {
                int index = buildIndex++;
                int alongIndex = index / RUNWAY_WIDTH;
                int acrossIndex = index % RUNWAY_WIDTH;
                int along = alongIndex - RUNWAY_LENGTH / 2;
                int across = acrossIndex - RUNWAY_WIDTH / 2;
                int x = site.xAt(along, across);
                int z = site.zAt(along, across);
                int groundY = terrainHeights[index];
                for (int y = groundY; y <= surfaceY; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.CONCRETE.gray().defaultBlockState(), 2);
                }
                for (int y = surfaceY + 1; y < groundY; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(new BlockPos(x, surfaceY, z), marking(alongIndex, acrossIndex),
                        2);
            }
            if (buildIndex >= terrainHeights.length) {
                stage = Stage.DONE;
                Component message = Component.literal("Flight Line runway built: 400 × 28 blocks at "
                        + site.centerX + ", " + site.centerZ + ".");
                for (ServerPlayer player : level.players()) {
                    if (player.distanceToSqr(site.centerX, surfaceY, site.centerZ)
                            <= (double) TRIGGER_DISTANCE * TRIGGER_DISTANCE) {
                        player.sendSystemMessage(message);
                    }
                }
            }
            return stage == Stage.DONE;
        }

        private BlockState marking(int alongIndex, int acrossIndex) {
            if (acrossIndex == 0 || acrossIndex == RUNWAY_WIDTH - 1) {
                return Blocks.CONCRETE.white().defaultBlockState();
            }
            if (acrossIndex == RUNWAY_WIDTH / 2 && alongIndex % 12 < 8) {
                return Blocks.CONCRETE.white().defaultBlockState();
            }
            if ((alongIndex < 8 || alongIndex >= RUNWAY_LENGTH - 8)
                    && acrossIndex % 4 < 2) {
                return Blocks.CONCRETE.white().defaultBlockState();
            }
            if (alongIndex == RUNWAY_LENGTH / 2 && acrossIndex == RUNWAY_WIDTH / 2) {
                return Blocks.CONCRETE.white().defaultBlockState();
            }
            return Blocks.CONCRETE.gray().defaultBlockState();
        }

        private boolean complete() {
            stage = Stage.DONE;
            return true;
        }

        private boolean fail() {
            // Nav snapshots are immutable for a server session by design; the persisted failure
            // takes effect the next time the database is rebuilt (join, respawn, or restart).
            FlightPlanStorage.get(level.getServer()).markRunwaySiteFailed(site.key());
            return complete();
        }
    }

    private static List<ChunkPos> runwayChunks(Site site) {
        int alongHalf = RUNWAY_LENGTH / 2;
        int acrossHalf = RUNWAY_WIDTH / 2;
        int minX = (site.centerX - (site.alongX ? alongHalf : acrossHalf)) >> 4;
        int maxX = (site.centerX + (site.alongX ? alongHalf : acrossHalf)) >> 4;
        int minZ = (site.centerZ - (site.alongX ? acrossHalf : alongHalf)) >> 4;
        int maxZ = (site.centerZ + (site.alongX ? acrossHalf : alongHalf)) >> 4;
        List<ChunkPos> chunks = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        }
        return chunks;
    }

    private static boolean isNaturalSurface(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY);
    }

    private enum Stage {
        LOAD,
        SURVEY,
        BUILD,
        DONE
    }
}
