package com.deathbreadcrumbs;

/**
 * JSON-serializable save structure for client-side breadcrumb state.
 * Package-private on purpose (only used by DeathBreadcrumbsClient).
 */
final class SaveData {
    String dimension;
    double[][] checkpoints;
    double[] lastCheckpointPos;
    long lastCheckpointTick;
    int checkpointSegmentStart;
}
