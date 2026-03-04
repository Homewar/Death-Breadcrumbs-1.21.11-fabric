package com.deathbreadcrumbs;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Result of GraphRoute.pathFrom(): a breadcrumb sequence towards death.
 * Package-private on purpose (only used by DeathBreadcrumbsClient).
 */
final class BreadcrumbPath {
    final int startNodeIndex;
    final List<Vec3> points;

    BreadcrumbPath(int startNodeIndex, List<Vec3> points) {
        this.startNodeIndex = startNodeIndex;
        this.points = points;
    }
}
