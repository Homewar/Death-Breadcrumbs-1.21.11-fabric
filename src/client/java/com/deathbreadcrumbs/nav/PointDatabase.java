package com.deathbreadcrumbs.nav;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory spatial index (cell hash) that supports:
 * <ul>
 *     <li>merging near points</li>
 *     <li>safe linking (prevents cycles inside a segment)</li>
 * </ul>
 *
 * <p>Dependency-free (no fastutil).</p>
 */
public final class PointDatabase {

    private final int cellSize;
    private final double mergeDist;
    private final double mergeDist2;
    private final int maxBackwalkForCycleCheck;

    private long nextId = 1;

    private final Map<Long, ArrayList<PointId>> cellToIds = new HashMap<>();
    private final Map<Long, PointRecord> idToRecord = new HashMap<>();

    public PointDatabase(int cellSize, double mergeDist, int maxBackwalkForCycleCheck) {
        this.cellSize = Math.max(1, cellSize);
        this.mergeDist = Math.max(0.0, mergeDist);
        this.mergeDist2 = this.mergeDist * this.mergeDist;
        this.maxBackwalkForCycleCheck = Math.max(16, maxBackwalkForCycleCheck);
    }

    public PointRecord get(PointId id) {
        if (id == null) return null;
        return idToRecord.get(id.value());
    }

    /**
     * Add a point, or merge into an existing point within {@code mergeDist}.
     * If {@code prevId} is provided, attempts to link {@code prev -> returnedId} safely.
     */
    public PointId addOrMerge(String dimKey, Vec3 pos, PointId prevId, long segmentId, long tickNow) {
        if (dimKey == null || pos == null) return null;

        PointRecord nearest = findNearestWithin(dimKey, pos);
        if (nearest != null) {
            nearest.touch(tickNow, pos, /*smoothWindow*/ 8);
            safeLink(prevId, nearest.id(), segmentId);
            return nearest.id();
        }

        PointId id = new PointId(nextId++);
        PointRecord rec = new PointRecord(id, dimKey, pos, segmentId, tickNow);
        idToRecord.put(id.value(), rec);
        addToCell(rec);

        safeLink(prevId, id, segmentId);
        return id;
    }

    private void addToCell(PointRecord rec) {
        long ck = cellKey(rec.dimKey(), cellX(rec.pos().x), cellZ(rec.pos().z));
        cellToIds.computeIfAbsent(ck, k -> new ArrayList<>()).add(rec.id());
    }

    private PointRecord findNearestWithin(String dimKey, Vec3 pos) {
        if (mergeDist <= 0) return null;

        int cx = cellX(pos.x);
        int cz = cellZ(pos.z);

        PointRecord best = null;
        double bestD2 = Double.POSITIVE_INFINITY;

        // Check 3x3 neighboring cells.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long ck = cellKey(dimKey, cx + dx, cz + dz);
                ArrayList<PointId> ids = cellToIds.get(ck);
                if (ids == null) continue;

                for (int i = 0; i < ids.size(); i++) {
                    PointRecord r = get(ids.get(i));
                    if (r == null) continue;

                    // The cell key includes dimKey, but keep this guard in case of hash collision.
                    if (!dimKey.equals(r.dimKey())) continue;

                    double d2 = dist2(pos, r.pos());
                    if (d2 <= mergeDist2 && d2 < bestD2) {
                        bestD2 = d2;
                        best = r;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Prevents accidental cycles inside a segment when merging back into an older point.
     */
    private void safeLink(PointId prev, PointId next, long segmentId) {
        if (prev == null || next == null) return;
        if (prev.equals(next)) return;

        PointRecord a = get(prev);
        PointRecord b = get(next);
        if (a == null || b == null) return;
        if (!a.dimKey().equals(b.dimKey())) return;

        if (a.segmentId() != segmentId) return;
        if (b.segmentId() != segmentId) return;

        if (next.equals(a.nextId())) return;
        if (prev.equals(b.prevId())) return;

        if (wouldCreateCycle(prev, next, segmentId)) return;

        // Do not overwrite existing links.
        if (a.nextId() == null) a.setNextId(next);
        if (b.prevId() == null) b.setPrevId(prev);
    }

    private boolean wouldCreateCycle(PointId prev, PointId next, long segmentId) {
        PointId cur = prev;
        for (int i = 0; i < maxBackwalkForCycleCheck; i++) {
            PointRecord r = get(cur);
            if (r == null) return false;
            if (r.segmentId() != segmentId) return false;

            PointId p = r.prevId();
            if (p == null) return false;
            if (p.equals(next)) return true;

            cur = p;
        }
        return true; // too deep => conservative
    }

    private int cellX(double x) {
        return (int) Math.floor(x / cellSize);
    }

    private int cellZ(double z) {
        return (int) Math.floor(z / cellSize);
    }

    /**
     * Hashes a dimension key and 2D cell coordinates into a single long.
     *
     * <p>This does not need to be stable across runs; it is only used as a map key.</p>
     */
    private static long cellKey(String dimKey, int cx, int cz) {
        long dimHash = (long) dimKey.hashCode();

        // Mix dimHash into both halves to reduce clustering.
        long k = (dimHash << 32) ^ (dimHash >>> 32);

        // Add cell coords.
        k ^= ((long) cx << 32) ^ (cz & 0xffffffffL);

        // Finalize (Murmur-like avalanche).
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= (k >>> 33);
        return k;
    }

    private static double dist2(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
