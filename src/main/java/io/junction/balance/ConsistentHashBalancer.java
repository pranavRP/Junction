package io.junction.balance;

import io.junction.backend.BackendRuntime;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Consistent hashing with bounded loads (design.md §2.3).
 *
 * <p>A ring of {@value #VNODES_PER_BACKEND} virtual nodes per backend. The
 * request's key is hashed onto the ring and the walk goes forward to the first
 * acceptable owner. Virtual nodes are what stop a three-backend ring from
 * splitting the keyspace into three wildly uneven arcs.
 *
 * <p><b>Bounded load is the part that matters.</b> Plain consistent hashing sends
 * a hot key to one backend and keeps sending it there while that backend melts.
 * Here a backend is skipped when its in-flight count exceeds
 * {@code average × 1.25}, so affinity yields to load — Google's
 * consistent-hashing-with-bounded-loads. Affinity is an optimisation; staying up
 * is not.
 *
 * <p><b>Requests with no key get spread, not stacked.</b> If the configured
 * header or cookie is absent, hashing the empty string would map every such
 * request to one backend and manufacture a hotspot out of anonymous traffic.
 * They are randomised onto the ring instead.
 */
final class ConsistentHashBalancer implements Balancer {

    private static final int VNODES_PER_BACKEND = 160;
    /** Skip a backend above this multiple of the mean in-flight. */
    private static final double LOAD_FACTOR = 1.25;

    private final List<BackendRuntime> backends;
    /** Ring positions, ascending; parallel to {@link #ringOwner}. */
    private final long[] ringHash;
    private final int[] ringOwner;

    ConsistentHashBalancer(List<BackendRuntime> backends) {
        this.backends = List.copyOf(backends);

        int n = this.backends.size();
        long[] hashes = new long[n * VNODES_PER_BACKEND];
        int[] owners = new int[hashes.length];

        Integer[] order = new Integer[hashes.length];
        long[] raw = new long[hashes.length];
        for (int i = 0; i < n; i++) {
            String id = this.backends.get(i).id();
            for (int v = 0; v < VNODES_PER_BACKEND; v++) {
                int slot = i * VNODES_PER_BACKEND + v;
                raw[slot] = Hashing.hash64(id + "#" + v);
                order[slot] = slot;
            }
        }
        Arrays.sort(order, (x, y) -> Long.compare(raw[x], raw[y]));
        for (int k = 0; k < order.length; k++) {
            hashes[k] = raw[order[k]];
            owners[k] = order[k] / VNODES_PER_BACKEND;
        }
        this.ringHash = hashes;
        this.ringOwner = owners;
    }

    @Override
    public PickResult pick(String hashKey) {
        if (ringHash.length == 0 || !Balancers.anySelectable(backends)) {
            return Balancers.noneAvailable(backends);
        }

        long position = (hashKey == null || hashKey.isBlank())
                ? ThreadLocalRandom.current().nextLong()
                : Hashing.hash64(hashKey);

        int start = ringIndexFor(position);
        int limit = inflightLimit();

        // First pass honours the load bound; second ignores it. Without the
        // second pass a pool that is uniformly busy would return "no backend"
        // while every backend was perfectly healthy.
        BackendRuntime chosen = walk(start, limit);
        if (chosen == null) {
            chosen = walk(start, Integer.MAX_VALUE);
        }
        return chosen == null ? Balancers.noneAvailable(backends) : new PickResult.Chosen(chosen);
    }

    /**
     * Walks the ring from {@code start}, returning the first selectable owner
     * within {@code maxInflight}.
     *
     * <p>Owners already rejected are tracked in a bitmask so a 480-entry ring
     * costs at most one check per distinct backend, with no allocation. Pools
     * larger than 64 fall back to re-checking, which is correct but slower —
     * and a 64-backend pool behind one proxy is not a shape this targets.
     */
    private BackendRuntime walk(int start, int maxInflight) {
        long tried = 0L;
        boolean trackable = backends.size() <= 64;

        for (int step = 0; step < ringHash.length; step++) {
            int owner = ringOwner[(start + step) % ringHash.length];
            if (trackable) {
                long bit = 1L << owner;
                if ((tried & bit) != 0) {
                    continue;
                }
                tried |= bit;
            }
            BackendRuntime b = backends.get(owner);
            if (b.selectable() && b.inflight() <= maxInflight) {
                return b;
            }
            if (trackable && Long.bitCount(tried) == backends.size()) {
                return null;
            }
        }
        return null;
    }

    private int inflightLimit() {
        long total = 0;
        int live = 0;
        for (int i = 0; i < backends.size(); i++) {
            BackendRuntime b = backends.get(i);
            if (b.selectable()) {
                total += b.inflight();
                live++;
            }
        }
        if (live == 0) {
            return Integer.MAX_VALUE;
        }
        // Ceiling, and never below 1: with an idle pool the mean is 0, and a
        // limit of 0 would reject every backend on the first request.
        return Math.max(1, (int) Math.ceil((double) total / live * LOAD_FACTOR));
    }

    /** Index of the first ring entry at or after {@code position}, wrapping. */
    private int ringIndexFor(long position) {
        int idx = Arrays.binarySearch(ringHash, position);
        if (idx >= 0) {
            return idx;
        }
        int insertion = -(idx + 1);
        return insertion == ringHash.length ? 0 : insertion;
    }

    @Override
    public String name() {
        return "consistent_hash";
    }
}
