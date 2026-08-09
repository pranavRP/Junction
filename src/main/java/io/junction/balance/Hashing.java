package io.junction.balance;

import java.nio.charset.StandardCharsets;

/**
 * 64-bit string hashing for ring placement.
 *
 * <p>design.md names xxHash. This is FNV-1a followed by the SplitMix64
 * finaliser instead, which avoids a dependency for ~15 lines of code. The only
 * property the ring actually needs is uniform, avalanching placement — FNV-1a
 * alone is too regular in its low bits for that, which is what the finaliser
 * fixes. Distribution is asserted in {@code ConsistentHashBalancerTest} rather
 * than assumed (R-1).
 *
 * <p>Not a cryptographic hash and not used as one.
 */
final class Hashing {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Hashing() {}

    static long hash64(String s) {
        long h = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return fmix64(h);
    }

    /** SplitMix64 finaliser — cheap, strong avalanche. */
    private static long fmix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
