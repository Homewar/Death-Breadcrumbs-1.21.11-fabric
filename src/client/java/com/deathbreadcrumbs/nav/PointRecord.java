package com.deathbreadcrumbs.nav;

import net.minecraft.world.phys.Vec3;

/**
 * One stored support point in the {@link PointDatabase}.
 *
 * <p>Fields are intentionally minimal; mutation is limited to the point position (smoothing),
 * link pointers, and lightweight metadata.</p>
 */
public final class PointRecord {
    private final PointId id;
    private final String dimKey;

    private Vec3 pos;

    /** Directed chain inside one segment (optional). */
    private PointId prevId;
    private PointId nextId;

    /** Logical segment id (increment on death/teleport/etc.). */
    private final long segmentId;

    /** Optional metadata. */
    private long lastSeenTick;
    private int visits;

    PointRecord(PointId id, String dimKey, Vec3 pos, long segmentId, long tickNow) {
        this.id = id;
        this.dimKey = dimKey;
        this.pos = pos;
        this.segmentId = segmentId;
        this.lastSeenTick = tickNow;
        this.visits = 1;
    }

    public PointId id() {
        return id;
    }

    public String dimKey() {
        return dimKey;
    }

    public Vec3 pos() {
        return pos;
    }

    public long segmentId() {
        return segmentId;
    }

    public long lastSeenTick() {
        return lastSeenTick;
    }

    public int visits() {
        return visits;
    }

    PointId prevId() {
        return prevId;
    }

    PointId nextId() {
        return nextId;
    }

    void setPrevId(PointId prevId) {
        this.prevId = prevId;
    }

    void setNextId(PointId nextId) {
        this.nextId = nextId;
    }

    /**
     * Updates metadata and optionally applies light smoothing towards {@code newPos}.
     */
    void touch(long tickNow, Vec3 newPos, int smoothWindow) {
        this.lastSeenTick = tickNow;
        this.visits++;

        if (smoothWindow > 0 && this.visits <= smoothWindow) {
            // Simple EMA (75/25) to dampen jitter.
            this.pos = new Vec3(
                    (this.pos.x * 0.75) + (newPos.x * 0.25),
                    (this.pos.y * 0.75) + (newPos.y * 0.25),
                    (this.pos.z * 0.75) + (newPos.z * 0.25)
            );
        } else {
            this.pos = newPos;
        }
    }
}
