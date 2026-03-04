package com.deathbreadcrumbs;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Route helper over recorded points.
 *
 * <p>The input list is a set of "support points" recorded during the life that ended. The route shown after
 * death must represent a real path through those points, not just a naive iteration in the insertion order.
 *
 * <p>We therefore build a small navigation graph:
 * <ul>
 *   <li>Always connect sequential points (the exact recorded trace).</li>
 *   <li>Additionally connect spatially close points (within a small radius), so the solver can avoid loops
 *       and is resilient to buffer tails / multiple deaths.</li>
 * </ul>
 *
 * <p>Then we run Dijkstra from the death node and store a "next hop" pointer for each node.
 */
final class GraphRoute {

    private final List<Vec3> nodes;
    private final int deathIdx;
    private final int[][] neighbors;
    private final double[][] weights;

    /** For each node i: next hop towards death (or -1 if unreachable). */
    private final int[] nextTowardDeath;
    /** For each node i: shortest distance to death (or +inf if unreachable). */
    private final double[] distToDeath;

    private GraphRoute(List<Vec3> nodes, int[][] neighbors, double[][] weights, int[] nextTowardDeath, double[] distToDeath) {
        this.nodes = nodes;
        this.deathIdx = nodes.size() - 1;
        this.neighbors = neighbors;
        this.weights = weights;
        this.nextTowardDeath = nextTowardDeath;
        this.distToDeath = distToDeath;
    }

    Vec3 nearestNode(Vec3 position) {
        if (nodes == null || nodes.isEmpty() || position == null) return null;
        return nodes.get(findClosestIndex(nodes, position));
    }

    static GraphRoute build(List<Vec3> points) {
        if (points == null || points.size() < 2) return null;

        final int n = points.size();

        // Conservative extra connectivity radius.
        final double CONNECT_DIST = 8.0; // blocks
        final double CONNECT_DIST2 = CONNECT_DIST * CONNECT_DIST;
        final int CELL = 8; // ~CONNECT_DIST
        final int MAX_EXTRA_NEIGHBORS = 8;

        // Spatial hash: (cx,cz) -> indices.
        HashMap<Long, ArrayList<Integer>> cells = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Vec3 p = points.get(i);
            int cx = (int) Math.floor(p.x / CELL);
            int cz = (int) Math.floor(p.z / CELL);
            long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        int[][] neigh = new int[n][];
        double[][] w = new double[n][];

        for (int i = 0; i < n; i++) {
            Vec3 a = points.get(i);

            IntList neighIdx = new IntList();
            DoubleList neighW = new DoubleList();

            // Mandatory sequential edges (undirected).
            if (i > 0) {
                neighIdx.add(i - 1);
                neighW.add(a.distanceTo(points.get(i - 1)));
            }
            if (i < n - 1) {
                neighIdx.add(i + 1);
                neighW.add(a.distanceTo(points.get(i + 1)));
            }

            // Extra edges to nearby points.
            int cx = (int) Math.floor(a.x / CELL);
            int cz = (int) Math.floor(a.z / CELL);

            ArrayList<Candidate> candidates = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long key = (((long) (cx + dx)) << 32) ^ ((cz + dz) & 0xffffffffL);
                    ArrayList<Integer> ids = cells.get(key);
                    if (ids == null) continue;
                    for (int k = 0; k < ids.size(); k++) {
                        int j = ids.get(k);
                        if (j == i) continue;
                        // Avoid duplicating sequential edges.
                        if (j == i - 1 || j == i + 1) continue;
                        Vec3 b = points.get(j);
                        double d2 = dist2(a, b);
                        if (d2 <= CONNECT_DIST2) {
                            candidates.add(new Candidate(j, Math.sqrt(d2)));
                        }
                    }
                }
            }

            candidates.sort((u, v) -> Double.compare(u.w, v.w));
            int added = 0;
            for (int c = 0; c < candidates.size() && added < MAX_EXTRA_NEIGHBORS; c++) {
                Candidate cand = candidates.get(c);
                int j = cand.j;
                if (contains(neighIdx, j)) continue;
                neighIdx.add(j);
                neighW.add(cand.w);
                added++;
            }

            neigh[i] = neighIdx.toArray();
            w[i] = neighW.toArray();
        }

        // Dijkstra from death node to compute shortest-path tree.
        int[] next = new int[n];
        Arrays.fill(next, -1);

        double[] dist = new double[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[n - 1] = 0.0;

        PriorityQueue<State> pq = new PriorityQueue<>((a, b) -> Double.compare(a.d, b.d));
        pq.add(new State(n - 1, 0.0));

        boolean[] done = new boolean[n];
        while (!pq.isEmpty()) {
            State s = pq.poll();
            int u = s.i;
            if (done[u]) continue;
            done[u] = true;
            if (s.d != dist[u]) continue;

            int[] nu = neigh[u];
            double[] wu = w[u];
            for (int k = 0; k < nu.length; k++) {
                int v = nu[k];
                double nd = s.d + wu[k];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    // From v, the best next hop towards death is u.
                    next[v] = u;
                    pq.add(new State(v, nd));
                }
            }
        }

        return new GraphRoute(points, neigh, w, next, dist);
    }

    BreadcrumbPath pathFrom(Vec3 position, int maxCrumbs) {
        if (nodes == null || nodes.isEmpty() || position == null) return null;

        int start = findClosestIndex(nodes, position);
        if (!Double.isFinite(distToDeath[start])) {
            // Closest node is disconnected from death; find closest reachable node.
            double bestD2 = Double.POSITIVE_INFINITY;
            int bestIdx = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (!Double.isFinite(distToDeath[i])) continue;
                double d2 = dist2(nodes.get(i), position);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) return null;
            start = bestIdx;
        }

        ArrayList<Vec3> crumbs = new ArrayList<>(Math.max(4, maxCrumbs));
        int cur = start;
        int safety = nodes.size() + 8;

        while (crumbs.size() < maxCrumbs && safety-- > 0) {
            Vec3 here = nodes.get(cur);
            crumbs.add(new Vec3(here.x, here.y, here.z));

            if (cur == deathIdx) break;
            int nxt = nextTowardDeath[cur];
            if (nxt < 0 || nxt == cur) break;
            cur = nxt;
        }

        if (crumbs.isEmpty()) return null;
        return new BreadcrumbPath(start, crumbs);
    }

    private static boolean contains(IntList l, int v) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == v) return true;
        }
        return false;
    }

    private static double dist2(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int findClosestIndex(List<Vec3> pts, Vec3 target) {
        int bestIdx = 0;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pts.size(); i++) {
            Vec3 p = pts.get(i);
            double d2 = dist2(p, target);
            if (d2 < bestD2) {
                bestD2 = d2;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private record Candidate(int j, double w) {}

    private record State(int i, double d) {}

    /** Minimal int list (no fastutil). */
    private static final class IntList {
        private int[] a = new int[8];
        private int size = 0;

        void add(int v) {
            if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = v;
        }

        int size() {
            return size;
        }

        int get(int i) {
            return a[i];
        }

        int[] toArray() {
            return Arrays.copyOf(a, size);
        }
    }

    /** Minimal double list (no fastutil). */
    private static final class DoubleList {
        private double[] a = new double[8];
        private int size = 0;

        void add(double v) {
            if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = v;
        }

        double[] toArray() {
            return Arrays.copyOf(a, size);
        }

        int size() {
            return size;
        }
    }
}
