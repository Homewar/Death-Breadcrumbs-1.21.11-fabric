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

import com.deathbreadcrumbs.nav.PointDatabase;
import com.deathbreadcrumbs.nav.PointId;

import java.util.ArrayList;
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

    // --- New: global point DB for future marker API ---
    private static final PointDatabase POINT_DB = new PointDatabase(
            32, // CELL_SIZE
            CHECKPOINT_MERGE_DIST,
            2048 // max backwalk for cycle checks
    );
    private static long checkpointSegmentId = 1;
    private static PointId lastDbPointId = null;

    // --- Persistence ---
    private static final String SAVE_DIR_NAME = "deathpath";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loadedFromDiskThisSession = false;
    private static boolean saveDirty = false;
    private static long lastSaveTick = 0;


    // --- Route (after death) ---
    private static final class DeathRoute {
        final List<Vec3> points; // last point is the death position
        final ResourceKey<Level> dim;
        final GraphRoute graph;
        final GlobalPos death;
        int routeIndex; // legacy/fallback status

        DeathRoute(List<Vec3> points, ResourceKey<Level> dim, GraphRoute graph, GlobalPos death) {
            this.points = points;
            this.dim = dim;
            this.graph = graph;
            this.death = death;
            this.routeIndex = 0;
        }
    }

    // Queue of outstanding deaths (first = highest priority).
    private static final java.util.ArrayDeque<DeathRoute> deathQueue = new java.util.ArrayDeque<>();
    private static DeathRoute activeRoute = null;

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

    // Track last known alive position to make sure the captured route always includes the final approach.
    private static Vec3 lastAlivePos = null;
    private static ResourceKey<Level> lastAliveDim = null;

    // --- Breadcrumbs rendering ---
    private static final int CRUMBS_COUNT = 18;        // how many crumbs to show
    private static final double CRUMB_Y_OFF = 0.25;    // lift above ground    // lift above ground
    private static final double ADVANCE_DIST = 2.2;    // when "reached" a waypoint

    // Don't render crumbs too close to the player camera (avoids "in your face" particles)
    private static final double CRUMB_MIN_RENDER_DIST = 2.0; // blocks

    // When close enough to the death point, hide breadcrumbs.
    private static final double DEATH_HIDE_RADIUS = 6.0; // blocks
    // When the player reaches the death point, clear the route and resume recording checkpoints.
    private static final double DEATH_REACHED_RADIUS = 3.0; // blocks

    // --- Goal marker (death point) ---
    // "Bad Omen"-like swirling particles so the final target is always visible.
    private static final int GOAL_PARTICLES_PER_TICK = 6;
    private static final double GOAL_RING_RADIUS = 0.75;
    // Roughly matches the Bad Omen tint (dark teal/green).
    private static final double GOAL_R = 0.05;
    private static final double GOAL_G = 0.22;
    private static final double GOAL_B = 0.20;

    // Graph params ("опорные точки")


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

        // Keep last alive position for better death capture.
        if (alive) {
            lastAlivePos = player.position();
            lastAliveDim = level.dimension();
        }

        // 1) Detect transition alive -> dead and snapshot checkpoints from the life that ended.
        if (!alive && wasAliveLastTick) {
            pendingDeathCapture = true;
            // Snapshot only the current segment (ignore ancient trails).
            int start = Math.max(0, Math.min(checkpointSegmentStart, checkpoints.size()));
            checkpointsSnapshot = new ArrayList<>(checkpoints.subList(start, checkpoints.size()));
            checkpointsSnapshotDim = checkpointsDim;

            // Ensure the last alive position is included even if checkpoint throttling skipped it.
            if (lastAlivePos != null && lastAliveDim != null && checkpointsSnapshotDim != null
                    && checkpointsSnapshotDim.equals(lastAliveDim)) {
                if (checkpointsSnapshot.isEmpty()) {
                    checkpointsSnapshot.add(lastAlivePos);
                } else {
                    Vec3 last = checkpointsSnapshot.get(checkpointsSnapshot.size() - 1);
                    if (lastAlivePos.distanceTo(last) > (CHECKPOINT_MERGE_DIST * 0.5)) {
                        checkpointsSnapshot.add(lastAlivePos);
                    }
                }
            }
        }

        // 2) Capture death route using vanilla lastDeathLocation.
        //    Uses the snapshot so points from the new life are never included.
        tryCaptureDeathRoute(mc, player);

        // 3) While alive: place checkpoints ONLY if we are NOT returning to a death point.
        //    Important: right after respawn the route may not be captured yet (pendingDeathCapture),
        //    but we still must NOT record new checkpoints because it confuses the death route.
        if (alive) {
            // Record support points silently (even while returning).
            // Route capture uses a snapshot taken at death, so recording now does not pollute the route.
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

            // New segment for the DB as well.
            checkpointSegmentId++;
            lastDbPointId = null;
        }

        checkpointsDim = level.dimension();

        Vec3 pos = player.position();
        long tick = level.getGameTime();

        if (lastCheckpointPos == null) {
            checkpoints.add(pos);
                saveDirty = true;
            lastCheckpointPos = pos;
            lastCheckpointTick = tick;

            // Record to the DB with merge + cycle-safe linking.
            lastDbPointId = POINT_DB.addOrMerge(
                    keyId(level.dimension()),
                    pos,
                    lastDbPointId,
                    checkpointSegmentId,
                    tick
            );
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

            // Record to the DB with merge + cycle-safe linking.
            lastDbPointId = POINT_DB.addOrMerge(
                    keyId(level.dimension()),
                    pos,
                    lastDbPointId,
                    checkpointSegmentId,
                    tick
            );

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

        // IMPORTANT:
        // On the client, player.getLastDeathLocation() may still point to the *previous* death
        // while the player is on the death screen. It reliably updates after respawn.
        // Capturing too early produces a death marker in the wrong place.
        if (!player.isAlive()) return;

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
        // Important: do NOT try to auto-reverse here.
        // The checkpoints list is already time-ordered (oldest -> newest). When we keep a buffer tail
        // across deaths, heuristic reversing can flip the route incorrectly and break multi-death routes.

        rp.add(deathPos);

        // Collapse close-by support points to avoid spam.
        rp = simplifyClosePoints(rp, CHECKPOINT_MERGE_DIST);

        // Build a graph from the captured "support points".
        GraphRoute gr = GraphRoute.build(rp);

        // Queue semantics: first death first.
        DeathRoute dr = new DeathRoute(rp, gp.dimension(), gr, gp);
        deathQueue.addLast(dr);
        if (activeRoute == null) activeRoute = dr;

        lastCapturedDeath = gp;
        lastCapturedDeathTick = (mc.level != null) ? mc.level.getGameTime() : lastCapturedDeathTick;

        pendingDeathCapture = false;
        checkpointsSnapshot = null;
        checkpointsSnapshotDim = null;

        // Start a new recording segment for the new life, but keep a short tail so
        // a quick re-death still has enough support points.
        checkpointSegmentStart = Math.max(0, checkpoints.size() - CHECKPOINT_TAIL_ON_RESET);
        checkpointSegmentId++;
        lastDbPointId = null;
        // Reset throttling so points after respawn are not artificially sparse.
        lastCheckpointPos = null;
        lastCheckpointTick = 0;

        // IMPORTANT: do NOT clear checkpoints here.
        // Reason: if the player reaches the death point, picks up loot, and dies again quickly,
        // we still need historical support points to build a new route.

        // Inform
        player.displayClientMessage(
                Component.literal("[Death Breadcrumbs] Route captured (#" + deathQueue.size() + "): "
                        + (rp.size() - 1) + " checkpoints, death at "
                        + dp.getX() + " " + dp.getY() + " " + dp.getZ()),
                false
        );
    }

    private static void renderBreadcrumbs(Level level, LocalPlayer player) {
        if (activeRoute == null) return;
        if (activeRoute.points == null || activeRoute.points.isEmpty() || activeRoute.dim == null) return;
        if (!level.dimension().equals(activeRoute.dim)) return;

        Vec3 me = player.position();

        Vec3 deathPos = activeRoute.points.get(activeRoute.points.size() - 1);

        // Always show a distinct marker at the final target.
        spawnGoalMarker(level, deathPos);

        // If we reached the death point, clear the route.
        // Use horizontal distance (X/Z) so different Y (stairs, cliffs, etc.) doesn't prevent clearing.
        if (distXZ(me, deathPos) <= DEATH_REACHED_RADIUS) {
            advanceToNextDeathOrClear();
            return;
        }

        // If we are already close to the death point, hide breadcrumbs (but keep the goal marker).
        if (distXZ(me, deathPos) <= DEATH_HIDE_RADIUS) return;

        // Preferred: graph-based shortest path over "support points".
        if (activeRoute.graph != null) {
            BreadcrumbPath path = activeRoute.graph.pathFrom(me, CRUMBS_COUNT);
            if (path != null) {
                // Draw particles only at support points (graph nodes).
                for (int i = 0; i < path.points.size(); i++) {
                    Vec3 p = path.points.get(i);
                    // Skip points too close to the player to avoid particles clipping into the camera.
                    if (me.distanceTo(p) < CRUMB_MIN_RENDER_DIST) continue;
                    spawnCrumb(level, p);
                }
                // Keep routeIndex mostly meaningful for /status.
                activeRoute.routeIndex = path.startNodeIndex;
                return;
            }
        }

        // Fallback: old linear waypoints logic.
        int closest = findClosestIndex(activeRoute.points, me);
        if (closest > activeRoute.routeIndex) activeRoute.routeIndex = closest;
        while (activeRoute.routeIndex < activeRoute.points.size() - 1) {
            Vec3 next = activeRoute.points.get(activeRoute.routeIndex);
            if (me.distanceTo(next) <= ADVANCE_DIST) activeRoute.routeIndex++;
            else break;
        }
        int start = Math.max(0, Math.min(activeRoute.routeIndex, activeRoute.points.size() - 1));
        int end = Math.min(activeRoute.points.size(), start + CRUMBS_COUNT);
        for (int i = start; i < end; i++) {
            Vec3 p = activeRoute.points.get(i);
            spawnCrumb(level, p);
        }
    }

    /**
     * Spawns "Bad Omen"-like particles around the death point so the player can always
     * see the final goal, even if the breadcrumb trail is temporarily hidden.
     */
    private static void spawnGoalMarker(Level level, Vec3 deathPos) {
        // Small vertical column + ring swirl.
        for (int i = 0; i < GOAL_PARTICLES_PER_TICK; i++) {
            double a = (Math.random() * Math.PI * 2.0);
            double r = GOAL_RING_RADIUS * (0.35 + (Math.random() * 0.65));
            double x = deathPos.x + Math.cos(a) * r;
            double z = deathPos.z + Math.sin(a) * r;
            double y = deathPos.y + 0.15 + (Math.random() * 1.6);

            // For ENTITY_EFFECT, the (dx,dy,dz) parameters act as RGB on the client.
            spawnEffectParticle(level, x, y, z, GOAL_R, GOAL_G, GOAL_B);
        }
    }

    private static void spawnEffectParticle(Level level, double x, double y, double z, double r, double g, double b) {
        net.minecraft.core.particles.ParticleOptions opts = createEntityEffectOptions(r, g, b);

        if (level instanceof ClientLevel cl && opts != null) {
            // Prefer: addParticle(ParticleOptions, force=true, x,y,z, dx,dy,dz)
            try {
                java.lang.reflect.Method m = cl.getClass().getMethod(
                        "addParticle",
                        net.minecraft.core.particles.ParticleOptions.class,
                        boolean.class,
                        double.class, double.class, double.class,
                        double.class, double.class, double.class
                );
                m.invoke(cl, opts, true, x, y, z, 0.0, 0.0, 0.0);
                return;
            } catch (Throwable ignored) {
            }

            // Fallback: addAlwaysVisibleParticle(ParticleOptions, x,y,z, dx,dy,dz)
            try {
                java.lang.reflect.Method m = cl.getClass().getMethod(
                        "addAlwaysVisibleParticle",
                        net.minecraft.core.particles.ParticleOptions.class,
                        double.class, double.class, double.class,
                        double.class, double.class, double.class
                );
                m.invoke(cl, opts, x, y, z, 0.0, 0.0, 0.0);
                return;
            } catch (Throwable ignored) {
            }

            // Fallback: regular addParticle(ParticleOptions, x,y,z, dx,dy,dz)
            try {
                cl.addParticle(opts, x, y, z, 0.0, 0.0, 0.0);
                return;
            } catch (Throwable ignored) {
            }
        }

        // Fallback if ENTITY_EFFECT is not available / mappings differ.
        level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
    }

    /**
     * Build ParticleOptions for ParticleTypes.ENTITY_EFFECT.
     * Different MC versions expose this either via ColorParticleOption.create(...) or a constructor.
     * We use reflection so the project compiles across mappings.
     */
    private static net.minecraft.core.particles.ParticleOptions createEntityEffectOptions(double r, double g, double b) {
        try {
            Class<?> c = Class.forName("net.minecraft.core.particles.ColorParticleOption");

            // Try static create(ParticleType, float,float,float)
            try {
                java.lang.reflect.Method m = c.getMethod(
                        "create",
                        net.minecraft.core.particles.ParticleType.class,
                        float.class, float.class, float.class
                );
                Object o = m.invoke(null, ParticleTypes.ENTITY_EFFECT, (float) r, (float) g, (float) b);
                return (net.minecraft.core.particles.ParticleOptions) o;
            } catch (Throwable ignored) {
            }

            // Try static create(ParticleType, float,float,float,float)
            try {
                java.lang.reflect.Method m = c.getMethod(
                        "create",
                        net.minecraft.core.particles.ParticleType.class,
                        float.class, float.class, float.class, float.class
                );
                Object o = m.invoke(null, ParticleTypes.ENTITY_EFFECT, (float) r, (float) g, (float) b, 1.0f);
                return (net.minecraft.core.particles.ParticleOptions) o;
            } catch (Throwable ignored) {
            }

            // Try constructor(float,float,float) or (float,float,float,float)
            for (java.lang.reflect.Constructor<?> ctor : c.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 3 && p[0] == float.class && p[1] == float.class && p[2] == float.class) {
                    Object o = ctor.newInstance((float) r, (float) g, (float) b);
                    return (net.minecraft.core.particles.ParticleOptions) o;
                }
                if (p.length == 4 && p[0] == float.class && p[1] == float.class && p[2] == float.class && p[3] == float.class) {
                    Object o = ctor.newInstance((float) r, (float) g, (float) b, 1.0f);
                    return (net.minecraft.core.particles.ParticleOptions) o;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        return activeRoute != null;
    }

    private static boolean isReturningToDeath() {
        return isRouteActive() || pendingDeathCapture;
    }

    /**
     * Called when the player reaches the current death point.
     * If there are more outstanding deaths, switch to the next one.
     */
    private static void advanceToNextDeathOrClear() {
        if (!deathQueue.isEmpty()) deathQueue.removeFirst();
        activeRoute = deathQueue.peekFirst();
        if (activeRoute == null) {
            // No more targets.
            // Soft-reset the checkpoint segment so very old trails don't interfere with the next death route.
            checkpointSegmentStart = Math.max(0, checkpoints.size() - CHECKPOINT_TAIL_ON_RESET);
            checkpointSegmentId++;
            lastDbPointId = null;
            saveDirty = true;
        }
    }

    private static void clearRoute() {
        deathQueue.clear();
        activeRoute = null;
        // We keep lastCapturedDeath so we don't re-capture the same death over and over.

        // Same cleanup as when finishing the last target.
        checkpointSegmentStart = Math.max(0, checkpoints.size() - CHECKPOINT_TAIL_ON_RESET);
        checkpointSegmentId++;
        lastDbPointId = null;
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


// --- Commands ---
    private static int cmdClear(CommandContext<?> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 1;

        checkpoints.clear();
        checkpointsDim = null;
        lastCheckpointPos = null;
        lastCheckpointTick = 0;
        checkpointSegmentStart = 0;

        checkpointSegmentId++;
        lastDbPointId = null;

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
        int pending = deathQueue.size();
        int rp = (activeRoute == null || activeRoute.points == null) ? 0 : activeRoute.points.size();
        String dim = (activeRoute == null || activeRoute.dim == null) ? "none" : String.valueOf(activeRoute.dim);
        int idx = (activeRoute == null) ? 0 : activeRoute.routeIndex;
        boolean hasGraph = activeRoute != null && activeRoute.graph != null;

        mc.player.displayClientMessage(
			Component.literal("[Death Breadcrumbs] checkpoints=" + cp + ", deathsQueued=" + pending + ", activeRoutePoints=" + rp + ", activeDim=" + dim + ", routeIndex=" + idx + ", graph=" + hasGraph),
			false
		);
        return 1;
    }

    /**
     * Builds a sparse undirected graph from points by connecting each point to K nearest neighbors
     * (plus sequential edges), then runs Dijkstra from the death node (last point) to all nodes.
     * This gives a stable "follow the shortest path" behavior even if the player deviates.
     */
}