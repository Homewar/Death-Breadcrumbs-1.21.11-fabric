package com.deathbreadcrumbs;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.List;
import java.util.Optional;

public class DeathBreadcrumbsClient implements ClientModInitializer {

    // --- Checkpoints (while alive) ---
    private static final double CHECKPOINT_MIN_DIST = 4.0;      // blocks
    private static final int CHECKPOINT_MAX_INTERVAL_TICKS = 60; // 3 sec
    // If a new checkpoint is too close to the previous one, "collapse" them to avoid spam.
    private static final double CHECKPOINT_MERGE_DIST = 2.0;     // blocks
    // Keep a rolling buffer so the mod can build a route even if you die again shortly after picking up loot.
    private static final int CHECKPOINT_MAX_COUNT = 2500;
    // Soft-reset: when you reach the death point we keep only a small tail of points so
    // old trails don't create weird branches/loops for the next death.
    private static final int CHECKPOINT_TAIL_ON_RESET = 200;

    private static final ArrayList<Vec3> checkpoints = new ArrayList<>();
    private static ResourceKey<Level> checkpointsDim = null;
    private static Vec3 lastCheckpointPos = null;
    private static long lastCheckpointTick = 0;
    // Only points from this index are considered "current". Older points are kept in the buffer,
    // but are ignored when capturing a death route (prevents "crooked" routes from ancient trails).
    private static int checkpointSegmentStart = 0;

    // --- Persistence ---
    private static final String SAVE_DIR_NAME = "deathpath";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loadedFromDiskThisSession = false;
    private static boolean saveDirty = false;
    private static long lastSaveTick = 0;


    // --- Route (after death) ---
    private static List<Vec3> routePoints = null; // kept for /status and debug
    private static ResourceKey<Level> routeDim = null;

    // Graph-based routing: from current position -> nearest node -> ... -> death
    private static GraphRoute graphRoute = null;

    // Legacy index (kept for status; not used when graphRoute != null)
    private static int routeIndex = 0;

    // Helps capture route once per death
    private static GlobalPos lastCapturedDeath = null;

    private static long lastCapturedDeathTick = -1;

    // Debug: render all checkpoints (including history)
    private static boolean debugRenderAllPoints = false;
    // Detect the exact moment of death so we don't pollute the route with checkpoints
    // from the new life (respawn).
    private static boolean wasAliveLastTick = true;
    private static boolean pendingDeathCapture = false;
    private static ArrayList<Vec3> checkpointsSnapshot = null;
    private static ResourceKey<Level> checkpointsSnapshotDim = null;

    // --- Breadcrumbs rendering ---
    private static final int CRUMBS_COUNT = 18;        // how many crumbs to show
    private static final double CRUMB_Y_OFF = 0.15;    // lift above ground
    private static final double ADVANCE_DIST = 2.2;    // when "reached" a waypoint

    // Don't render crumbs too close to the player camera (avoids "in your face" particles)
    private static final double CRUMB_MIN_RENDER_DIST = 2.0; // blocks

    // If the player cuts corners / goes off-route, the closest graph node may be far away and crumbs "disappear".
    // When that happens, we inject temporary "bridge" nodes near the player so the graph stays connected.
    private static final double OFF_ROUTE_DIST = 12.0;      // XZ distance from nearest graph node to consider "lost"
    private static final double BRIDGE_STEP_DIST = 5.0;     // minimum XZ distance between bridge nodes
    private static final int BRIDGE_MIN_TICKS = 10;         // minimum ticks between bridge nodes

    private static Vec3 lastBridgePos = null;
    private static long lastBridgeTick = 0;

    // When close enough to the death point, hide breadcrumbs.
    private static final double DEATH_HIDE_RADIUS = 6.0; // blocks
    // When the player reaches the death point, clear the route and resume recording checkpoints.
    private static final double DEATH_REACHED_RADIUS = 3.0; // blocks

    // Graph params ("опорные точки")
    private static final int GRAPH_K_NEIGHBORS = 6;          // connect each node to K nearest
    private static final double GRAPH_MAX_EDGE_DIST = 40.0;  // don't connect very far nodes (blocks)


    // While alive we keep recording checkpoints, but we don't render them.
    // (Particles are shown only for the death route.)

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("deathbreadcrumbs")
                    .then(ClientCommandManager.literal("clear")
                            .executes(DeathBreadcrumbsClient::cmdClear))
                    .then(ClientCommandManager.literal("debug").executes(DeathBreadcrumbsClient::cmdDebug))
                    .then(ClientCommandManager.literal("status")
                            .executes(DeathBreadcrumbsClient::cmdStatus))
            );
            // Backwards-compatible alias
            dispatcher.register(ClientCommandManager.literal("deathpath")
                    .then(ClientCommandManager.literal("clear")
                            .executes(DeathBreadcrumbsClient::cmdClear))
                    .then(ClientCommandManager.literal("debug").executes(DeathBreadcrumbsClient::cmdDebug))
                    .then(ClientCommandManager.literal("status")
                            .executes(DeathBreadcrumbsClient::cmdStatus))
            );
        });

ClientTickEvents.END_CLIENT_TICK.register(DeathBreadcrumbsClient::onClientTick);
    }

    private static void onClientTick(Minecraft mc) {
        if (mc == null) return;
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        // Lazy-load checkpoints once per session when we have a world loaded.
        if (!loadedFromDiskThisSession) {
            loadFromDisk(mc, level);
            loadedFromDiskThisSession = true;
        }

        // Periodic autosave (throttled)
        if (saveDirty) {
            long tickNow = level.getGameTime();
            if (tickNow - lastSaveTick >= 200) { // every ~10s
                saveToDisk(mc);
                lastSaveTick = tickNow;
            }
        }

        boolean alive = player.isAlive();

        // 1) Detect transition alive -> dead and snapshot checkpoints from the life that ended.
        if (!alive && wasAliveLastTick) {
            pendingDeathCapture = true;
            // Snapshot only the current segment (ignore ancient trails).
            int start = Math.max(0, Math.min(checkpointSegmentStart, checkpoints.size()));
            checkpointsSnapshot = new ArrayList<>(checkpoints.subList(start, checkpoints.size()));
            checkpointsSnapshotDim = checkpointsDim;
        }

        // 2) Capture death route using vanilla lastDeathLocation.
        //    Uses the snapshot so points from the new life are never included.
        tryCaptureDeathRoute(mc, player);

        // 3) While alive: place checkpoints ONLY if we are NOT returning to a death point.
        //    Important: right after respawn the route may not be captured yet (pendingDeathCapture),
        //    but we still must NOT record new checkpoints because it confuses the death route.
        if (alive && !isReturningToDeath()) {
            // Record support points silently.
            maybeAddCheckpoint(level, player);
        }

        // 4) Draw breadcrumbs (short trail ahead)
        renderBreadcrumbs(level, player);

        // 5) Debug: render all stored checkpoints
        if (debugRenderAllPoints) {
            renderAllCheckpoints(level, player);
        }

        wasAliveLastTick = alive;
    }

    private static void maybeAddCheckpoint(Level level, LocalPlayer player) {
        // If dimension changed, start a new path for this dimension
        if (checkpointsDim != null && !checkpointsDim.equals(level.dimension())) {
            checkpoints.clear();
            lastCheckpointPos = null;
            lastCheckpointTick = 0;
            checkpointSegmentStart = 0;
        }

        checkpointsDim = level.dimension();

        Vec3 pos = player.position();
        long tick = level.getGameTime();

        if (lastCheckpointPos == null) {
            checkpoints.add(pos);
                saveDirty = true;
            lastCheckpointPos = pos;
            lastCheckpointTick = tick;
            return;
        }

        double dist = pos.distanceTo(lastCheckpointPos);
        long dt = tick - lastCheckpointTick;

        if (dist >= CHECKPOINT_MIN_DIST || dt >= CHECKPOINT_MAX_INTERVAL_TICKS) {
            // Collapse close-by points instead of adding a new one.
            if (!checkpoints.isEmpty() && dist <= CHECKPOINT_MERGE_DIST) {
                checkpoints.set(checkpoints.size() - 1, pos);
                saveDirty = true;
            } else {
                checkpoints.add(pos);
                saveDirty = true;
            }

            // Rolling buffer: prevent unbounded growth.
            if (checkpoints.size() > CHECKPOINT_MAX_COUNT) {
                int overflow = checkpoints.size() - CHECKPOINT_MAX_COUNT;
                for (int i = 0; i < overflow; i++) {
                    checkpoints.remove(0);
                }
                // Keep segment start consistent with removed prefix.
                checkpointSegmentStart = Math.max(0, checkpointSegmentStart - overflow);
                saveDirty = true;
            }

            lastCheckpointPos = pos;
            lastCheckpointTick = tick;
        }
    }

    private static void tryCaptureDeathRoute(Minecraft mc, LocalPlayer player) {
        if (!pendingDeathCapture) return;

        Optional<GlobalPos> opt = player.getLastDeathLocation();
        if (opt.isEmpty()) return;

        GlobalPos gp = opt.get();

        // Same death already captured very recently: ignore to avoid re-capture loops.
        // But allow capturing again if you die again at the same spot later.
        boolean sameDeath = lastCapturedDeath != null
                && lastCapturedDeath.pos().equals(gp.pos())
                && lastCapturedDeath.dimension().equals(gp.dimension());

        if (sameDeath) {
            long tickNow = (mc.level != null) ? mc.level.getGameTime() : -1;
            if (tickNow >= 0 && lastCapturedDeathTick >= 0 && (tickNow - lastCapturedDeathTick) < 40) { // ~2s
                pendingDeathCapture = false;
                checkpointsSnapshot = null;
                checkpointsSnapshotDim = null;
                return;
            }
        }

        // Capture route: checkpoints (from the life that ended) + exact death position (center of block)
        BlockPos dp = gp.pos();
        Vec3 deathPos = new Vec3(dp.getX() + 0.5, dp.getY() + 0.1, dp.getZ() + 0.5);

        int snapSize = (checkpointsSnapshot == null) ? 0 : checkpointsSnapshot.size();
        ArrayList<Vec3> rp = new ArrayList<>((checkpointsSnapshotDim != null && checkpointsSnapshotDim.equals(gp.dimension())) ? (snapSize + 1) : 2);

        // Only use checkpoints from the same dimension as the death, and only from the snapshot.
        if (checkpointsSnapshotDim != null && checkpointsSnapshotDim.equals(gp.dimension()) && checkpointsSnapshot != null) {
            rp.addAll(checkpointsSnapshot);
        }

        // Ensure direction is RESPAWN -> ... -> DEATH.
        // If the checkpoints we collected happen to be ordered the other way around,
        // flip them so the last checkpoint is closest to the death position.
        if (rp.size() >= 2) {
            int lastCpIdx = rp.size() - 1;
            Vec3 firstCp = rp.get(0);
            Vec3 lastCp = rp.get(lastCpIdx);
            double dFirst = firstCp.distanceTo(deathPos);
            double dLast = lastCp.distanceTo(deathPos);
            if (dFirst < dLast) {
                java.util.Collections.reverse(rp);
            }
        }

        rp.add(deathPos);

        // Collapse close-by support points to avoid spam.
        rp = simplifyClosePoints(rp, CHECKPOINT_MERGE_DIST);

        routePoints = rp;
        routeDim = gp.dimension();
        routeIndex = 0;

        // Build a graph from the captured "support points" and precompute shortest paths to death.
        graphRoute = GraphRoute.build(rp);

        lastCapturedDeath = gp;
        lastCapturedDeathTick = (mc.level != null) ? mc.level.getGameTime() : lastCapturedDeathTick;

        pendingDeathCapture = false;
        checkpointsSnapshot = null;
        checkpointsSnapshotDim = null;

        // IMPORTANT: do NOT clear checkpoints here.
        // Reason: if the player reaches the death point, picks up loot, and dies again quickly,
        // we still need historical support points to build a new route.

        // Inform
        player.displayClientMessage(
                Component.literal("[Death Breadcrumbs] Route captured: " + (rp.size() - 1) + " checkpoints, death at "
                        + dp.getX() + " " + dp.getY() + " " + dp.getZ()),
                false
        );
    }

    private static void renderBreadcrumbs(Level level, LocalPlayer player) {
        if (routePoints == null || routePoints.isEmpty() || routeDim == null) return;
        if (!level.dimension().equals(routeDim)) return;

        Vec3 me = player.position();

        Vec3 deathPos = routePoints.get(routePoints.size() - 1);

        // If we reached the death point, clear the route.
        // Use horizontal distance (X/Z) so different Y (stairs, cliffs, etc.) doesn't prevent clearing.
        if (distXZ(me, deathPos) <= DEATH_REACHED_RADIUS) {
            clearRoute();
            return;
        }

        // If we are already close to the death point, hide breadcrumbs.
        if (distXZ(me, deathPos) <= DEATH_HIDE_RADIUS) return;

        // If the player deviated far from the recorded trail (cut corners, teleported locally, etc.),
        // add a "bridge" node near the player and rebuild the graph so breadcrumbs remain visible.
        if (graphRoute != null && routePoints != null && routePoints.size() >= 2) {
            Vec3 nearest = graphRoute.nodes.get(findClosestIndex(graphRoute.nodes, me));
            double dNearest = distXZ(me, nearest);
            long tickNow = level.getGameTime();

            boolean far = dNearest > OFF_ROUTE_DIST;
            boolean stepped = (lastBridgePos == null) || (distXZ(me, lastBridgePos) >= BRIDGE_STEP_DIST);
            boolean cooldown = (tickNow - lastBridgeTick) >= BRIDGE_MIN_TICKS;

            if (far && stepped && cooldown) {
                Vec3 bridge = new Vec3(me.x, me.y, me.z);

                // Insert right before death node (death is always the last node).
                int insertAt = Math.max(0, routePoints.size() - 1);
                routePoints.add(insertAt, bridge);

                // Locally collapse duplicates near the insertion to avoid spam.
                routePoints = simplifyClosePoints(routePoints, CHECKPOINT_MERGE_DIST);

                // Rebuild graph so pathFrom() starts near the player again.
                graphRoute = GraphRoute.build(routePoints);

                // Also keep the checkpoint history for future deaths (same dimension only).
                if (routeDim != null) {
                    if (checkpointsDim == null) checkpointsDim = routeDim;
                    if (checkpointsDim.equals(routeDim)) {
                    if (!checkpoints.isEmpty() && distXZ(checkpoints.get(checkpoints.size() - 1), bridge) <= CHECKPOINT_MERGE_DIST) {
                        checkpoints.set(checkpoints.size() - 1, bridge);
                    } else {
                        checkpoints.add(bridge);
                    }
                    // cap
                    while (checkpoints.size() > CHECKPOINT_MAX_COUNT) {
                        checkpoints.remove(0);
                        checkpointSegmentStart = Math.max(0, checkpointSegmentStart - 1);
                    }
                    saveDirty = true;
                    }
                }

                lastBridgePos = bridge;
                lastBridgeTick = tickNow;
            }
        }


        // Preferred: graph-based shortest path over "support points".
        if (graphRoute != null) {
            GraphRoute.Path path = graphRoute.pathFrom(me, CRUMBS_COUNT);
            if (path != null) {
                // Draw particles only at support points (graph nodes).
                for (int i = 0; i < path.points.size(); i++) {
                    Vec3 p = path.points.get(i);
                    // Skip points too close to the player to avoid particles clipping into the camera.
                    if (me.distanceTo(p) < CRUMB_MIN_RENDER_DIST) continue;
                    spawnCrumb(level, p);
                }
                // Keep routeIndex mostly meaningful for /status.
                routeIndex = path.startNodeIndex;
                return;
            }
        }

        // Fallback: old linear waypoints logic.
        int closest = findClosestIndex(routePoints, me);
        if (closest > routeIndex) routeIndex = closest;
        while (routeIndex < routePoints.size() - 1) {
            Vec3 next = routePoints.get(routeIndex);
            if (me.distanceTo(next) <= ADVANCE_DIST) routeIndex++;
            else break;
        }
        int start = Math.max(0, Math.min(routeIndex, routePoints.size() - 1));
        int end = Math.min(routePoints.size(), start + CRUMBS_COUNT);
        for (int i = start; i < end; i++) {
            Vec3 p = routePoints.get(i);
            spawnCrumb(level, p);
        }
    }

    /**
     * Particles like END_ROD are distance-culled quite aggressively and may "pop in" only when you get close.
     * Some mappings/versions expose this as ClientLevel#addAlwaysVisibleParticle, others as
     * ClientLevel#addParticle(ParticleOptions, boolean force, ...). We try the "force" overload first.
     */
    private static void spawnCrumb(Level level, Vec3 p) {
        double x = p.x;
        double y = p.y + CRUMB_Y_OFF;
        double z = p.z;
        if (level instanceof ClientLevel cl) {
            // Prefer: addParticle(type, force=true, x,y,z, dx,dy,dz)
            try {
                java.lang.reflect.Method m = cl.getClass().getMethod(
                        "addParticle",
                        net.minecraft.core.particles.ParticleOptions.class,
                        boolean.class,
                        double.class, double.class, double.class,
                        double.class, double.class, double.class
                );
                m.invoke(cl, ParticleTypes.END_ROD, true, x, y, z, 0.0, 0.0, 0.0);
                return;
            } catch (Throwable ignored) {
            }

            // Fallback: addAlwaysVisibleParticle(type, x,y,z, dx,dy,dz)
            try {
                java.lang.reflect.Method m = cl.getClass().getMethod(
                        "addAlwaysVisibleParticle",
                        net.minecraft.core.particles.ParticleOptions.class,
                        double.class, double.class, double.class,
                        double.class, double.class, double.class
                );
                m.invoke(cl, ParticleTypes.END_ROD, x, y, z, 0.0, 0.0, 0.0);
                return;
            } catch (Throwable ignored) {
            }
        }

        // Server/world fallback.
        level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
    }


    private static void renderAllCheckpoints(Level level, LocalPlayer player) {
        if (checkpointsDim == null) return;
        if (!level.dimension().equals(checkpointsDim)) return;
        int n = checkpoints.size();
        if (n <= 0) return;

        // Avoid spawning an extreme amount of particles each tick.
        final int maxPerTick = 600;
        int stride = Math.max(1, n / maxPerTick);

        for (int i = 0; i < n; i += stride) {
            Vec3 p = checkpoints.get(i);
            spawnCrumb(level, p);
        }
    }

    // Intentionally no alive checkpoint rendering.


    private static boolean isRouteActive() {
        return routePoints != null && !routePoints.isEmpty() && routeDim != null;
    }

    private static boolean isReturningToDeath() {
        return isRouteActive() || pendingDeathCapture;
    }

    private static void clearRoute() {
        routePoints = null;
        routeDim = null;
        routeIndex = 0;
        graphRoute = null;
        lastBridgePos = null;
        lastBridgeTick = 0;
        // We keep lastCapturedDeath so we don't re-capture the same death over and over.

        // Soft-reset the checkpoint segment so very old trails don't interfere with the next death route.
        // Keep a small tail to allow an immediate second death right after picking up loot.
        checkpointSegmentStart = Math.max(0, checkpoints.size() - CHECKPOINT_TAIL_ON_RESET);
        saveDirty = true;
    }

    private static ArrayList<Vec3> simplifyClosePoints(List<Vec3> pts, double mergeDist) {
        if (pts == null || pts.isEmpty()) return new ArrayList<>();
        double md2 = mergeDist * mergeDist;
        ArrayList<Vec3> out = new ArrayList<>(pts.size());
        Vec3 last = null;
        for (int i = 0; i < pts.size(); i++) {
            Vec3 p = pts.get(i);
            if (last == null) {
                out.add(p);
                last = p;
                continue;
            }
            double dx = p.x - last.x;
            double dy = p.y - last.y;
            double dz = p.z - last.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= md2) {
                // Replace the previous point with the new one (keeps path up-to-date).
                out.set(out.size() - 1, p);
                last = p;
            } else {
                out.add(p);
                last = p;
            }
        }
        return out;
    }

    private static int findClosestIndex(List<Vec3> pts, Vec3 target) {
        int bestIdx = 0;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pts.size(); i++) {
            Vec3 p = pts.get(i);
            double dx = p.x - target.x;
            double dy = p.y - target.y;
            double dz = p.z - target.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static double distXZ(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String keyId(Object key) {
        if (key == null) return null;
        try {
            // Mojang mappings: ResourceKey#location()
            java.lang.reflect.Method m = key.getClass().getMethod("location");
            Object id = m.invoke(key);
            return (id == null) ? null : id.toString();
        } catch (Throwable ignored) {
        }
        try {
            // Yarn mappings: RegistryKey#getValue()
            java.lang.reflect.Method m = key.getClass().getMethod("getValue");
            Object id = m.invoke(key);
            return (id == null) ? null : id.toString();
        } catch (Throwable ignored) {
        }
        return key.toString();
    }

    

    // --- Persistence helpers ---
    private static Path getSavePath(Minecraft mc) {
        String serverKey = "singleplayer";
        try {
            var sd = mc.getCurrentServer();
            if (sd != null && sd.ip != null && !sd.ip.isBlank()) {
                serverKey = sd.ip;
            }
        } catch (Throwable ignored) {
        }

        // Sanitize for filesystem
        serverKey = serverKey.replaceAll("[^a-zA-Z0-9._-]+", "_");

        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SAVE_DIR_NAME);
        return dir.resolve(serverKey + ".json");
    }

    private static void saveToDisk(Minecraft mc) {
        try {
            Path file = getSavePath(mc);
            Files.createDirectories(file.getParent());

            SaveData data = new SaveData();
            data.dimension = (checkpointsDim == null) ? null : keyId(checkpointsDim);
            data.checkpointSegmentStart = checkpointSegmentStart;
            data.lastCheckpointTick = lastCheckpointTick;
            data.lastCheckpointPos = (lastCheckpointPos == null) ? null : new double[]{lastCheckpointPos.x, lastCheckpointPos.y, lastCheckpointPos.z};
            data.checkpoints = new double[checkpoints.size()][3];
            for (int i = 0; i < checkpoints.size(); i++) {
                Vec3 v = checkpoints.get(i);
                data.checkpoints[i][0] = v.x;
                data.checkpoints[i][1] = v.y;
                data.checkpoints[i][2] = v.z;
            }

            String json = GSON.toJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8);
            saveDirty = false;
        } catch (IOException e) {
            // ignore: config dir may be read-only in some setups
        }
    }

    private static void loadFromDisk(Minecraft mc, Level level) {
        try {
            Path file = getSavePath(mc);
            if (!Files.exists(file)) return;

            String json = Files.readString(file, StandardCharsets.UTF_8);
            SaveData data = GSON.fromJson(json, SaveData.class);
            if (data == null) return;

            // Only restore if the saved dimension matches the current one.
            String curDim = keyId(level.dimension());
            if (data.dimension == null || !data.dimension.equals(curDim)) {
                // Don't load foreign-dimension data into current list.
                return;
            }

            checkpoints.clear();
            if (data.checkpoints != null) {
                for (double[] a : data.checkpoints) {
                    if (a == null || a.length < 3) continue;
                    checkpoints.add(new Vec3(a[0], a[1], a[2]));
                }
            }
            checkpointsDim = level.dimension();
            checkpointSegmentStart = Math.max(0, Math.min(data.checkpointSegmentStart, checkpoints.size()));
            lastCheckpointTick = data.lastCheckpointTick;
            if (data.lastCheckpointPos != null && data.lastCheckpointPos.length >= 3) {
                lastCheckpointPos = new Vec3(data.lastCheckpointPos[0], data.lastCheckpointPos[1], data.lastCheckpointPos[2]);
            } else {
                lastCheckpointPos = checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1);
            }

            // Keep within max buffer.
            if (checkpoints.size() > CHECKPOINT_MAX_COUNT) {
                int overflow = checkpoints.size() - CHECKPOINT_MAX_COUNT;
                checkpoints.subList(0, overflow).clear();
                checkpointSegmentStart = Math.max(0, checkpointSegmentStart - overflow);
            }

            saveDirty = false;
        } catch (Throwable ignored) {
        }
    }

    private static final class SaveData {
        String dimension;
        double[][] checkpoints;
        double[] lastCheckpointPos;
        long lastCheckpointTick;
        int checkpointSegmentStart;
    }

// --- Commands ---
    private static int cmdClear(CommandContext<?> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 1;

        checkpoints.clear();
        checkpointsDim = null;
        lastCheckpointPos = null;
        lastCheckpointTick = 0;

        clearRoute();

        lastCapturedDeath = null;

        saveDirty = true;
        saveToDisk(mc);
        mc.player.displayClientMessage(Component.literal("[Death Breadcrumbs] Cleared."), false);
        return 1;
    }

    private static int cmdDebug(CommandContext<?> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 1;
        debugRenderAllPoints = !debugRenderAllPoints;
        mc.player.displayClientMessage(Component.literal("[Death Breadcrumbs] Debug render all points: " + debugRenderAllPoints), false);
        return 1;
    }

    private static int cmdStatus(CommandContext<?> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 1;

        int cp = checkpoints.size();
        int rp = (routePoints == null) ? 0 : routePoints.size();
        String dim = (routeDim == null) ? "none" : String.valueOf(routeDim);

        mc.player.displayClientMessage(
			Component.literal("[Death Breadcrumbs] checkpoints=" + cp + ", routePoints=" + rp + ", routeDim=" + dim + ", routeIndex=" + routeIndex + ", graph=" + (graphRoute != null)),
			false
		);
        return 1;
    }

    /**
     * Builds a sparse undirected graph from points by connecting each point to K nearest neighbors
     * (plus sequential edges), then runs Dijkstra from the death node (last point) to all nodes.
     * This gives a stable "follow the shortest path" behavior even if the player deviates.
     */
    private static final class GraphRoute {
        private final List<Vec3> nodes;
        private final int deathIdx;
        private final int[][] neighbors;
        private final double[][] weights;

        // For each node i: next hop towards death (or -1)
        private final int[] nextTowardDeath;

        private GraphRoute(List<Vec3> nodes, int[][] neighbors, double[][] weights, int[] nextTowardDeath) {
            this.nodes = nodes;
            this.deathIdx = nodes.size() - 1;
            this.neighbors = neighbors;
            this.weights = weights;
            this.nextTowardDeath = nextTowardDeath;
        }

        static GraphRoute build(List<Vec3> points) {
            if (points == null || points.size() < 2) return null;

            int n = points.size();
            double maxEdge2 = GRAPH_MAX_EDGE_DIST * GRAPH_MAX_EDGE_DIST;

            // 1) For each node, pick K nearest neighbors (within max distance).
            int[][] neigh = new int[n][];
            double[][] w = new double[n][];

            for (int i = 0; i < n; i++) {
                Integer[] idx = new Integer[n - 1];
                int t = 0;
                Vec3 a = points.get(i);
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    idx[t++] = j;
                }
                Arrays.sort(idx, Comparator.comparingDouble(j -> dist2(a, points.get(j))));

                int cap = Math.min(GRAPH_K_NEIGHBORS, idx.length);
                int[] tmpN = new int[cap + 2];
                double[] tmpW = new double[cap + 2];
                int m = 0;
                for (int k = 0; k < cap; k++) {
                    int j = idx[k];
                    double d2 = dist2(a, points.get(j));
                    if (d2 > maxEdge2) continue;
                    tmpN[m] = j;
                    tmpW[m] = Math.sqrt(d2);
                    m++;
                }

                // 2) Always connect to sequential neighbors if present (keeps the recorded trail usable)
                if (i - 1 >= 0) {
                    tmpN[m] = i - 1;
                    tmpW[m] = a.distanceTo(points.get(i - 1));
                    m++;
                }
                if (i + 1 < n) {
                    tmpN[m] = i + 1;
                    tmpW[m] = a.distanceTo(points.get(i + 1));
                    m++;
                }

                neigh[i] = Arrays.copyOf(tmpN, m);
                w[i] = Arrays.copyOf(tmpW, m);
            }

            // 3) Dijkstra from death node (last point): compute next hop towards death for each node.
            int death = n - 1;
            double[] dist = new double[n];
            int[] next = new int[n];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(next, -1);
            dist[death] = 0.0;

            PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));
            pq.add(new long[]{death, Double.doubleToRawLongBits(0.0)});

            while (!pq.isEmpty()) {
                long[] cur = pq.poll();
                int u = (int) cur[0];
                double du = Double.longBitsToDouble(cur[1]);
                if (du != dist[u]) continue;

                int[] nu = neigh[u];
                double[] wu = w[u];
                for (int ei = 0; ei < nu.length; ei++) {
                    int v = nu[ei];
                    double nd = du + wu[ei];
                    if (nd < dist[v]) {
                        dist[v] = nd;
                        // From v, the next step towards death is u (because u is closer to death).
                        next[v] = u;
                        pq.add(new long[]{v, Double.doubleToRawLongBits(nd)});
                    }
                }
            }

            return new GraphRoute(points, neigh, w, next);
        }

        Path pathFrom(Vec3 position, int maxCrumbs) {
            if (nodes == null || nodes.isEmpty()) return null;
            int start = findClosestIndex(nodes, position);

            ArrayList<Vec3> crumbs = new ArrayList<>(maxCrumbs);
            int cur = start;
            int safety = nodes.size() + 5;

            while (crumbs.size() < maxCrumbs && safety-- > 0) {
                // Only show particles at support points (graph nodes), not along segments.
                Vec3 here = nodes.get(cur);
                crumbs.add(new Vec3(here.x, here.y, here.z));

                if (cur == deathIdx) break;

                int nxt = nextTowardDeath[cur];
                if (nxt < 0 || nxt == cur) break;
                cur = nxt;
            }

            if (crumbs.isEmpty()) return null;
            return new Path(start, crumbs);
        }


        private static double dist2(Vec3 a, Vec3 b) {
            double dx = a.x - b.x;
            double dy = a.y - b.y;
            double dz = a.z - b.z;
            return dx * dx + dy * dy + dz * dz;
        }

        private static final class Path {
            final int startNodeIndex;
            final List<Vec3> points;

            Path(int startNodeIndex, List<Vec3> points) {
                this.startNodeIndex = startNodeIndex;
                this.points = points;
            }
        }
    }
}