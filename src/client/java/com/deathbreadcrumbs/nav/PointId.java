package com.deathbreadcrumbs.nav;

/**
 * Monotonically increasing point id.
 *
 * <p>Using a dedicated type avoids mixing ids with other longs.</p>
 */
public record PointId(long value) {
    @Override
    public String toString() {
        return Long.toString(value);
    }
}
