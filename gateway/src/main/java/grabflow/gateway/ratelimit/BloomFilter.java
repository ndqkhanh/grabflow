package grabflow.gateway.ratelimit;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A space-efficient probabilistic data structure for IP-based rate limiting.
 *
 * <p>A Bloom filter answers the question "has this element been seen before?" with:
 * <ul>
 *   <li><b>Definite NO</b> — if the filter returns false, the element was never added.</li>
 *   <li><b>Probable YES</b> — if the filter returns true, the element was probably added,
 *       with a bounded false positive rate.</li>
 * </ul>
 *
 * <h2>Memory layout</h2>
 * The filter is backed by a {@code long[]} bit array accessed through
 * {@link AtomicLongArray} for lock-free thread safety. Each {@code long} stores
 * 64 bits, so an {@code m}-bit filter requires {@code ceil(m/64)} longs —
 * dramatically smaller than a {@code boolean[]} or {@code Set<String>}.
 *
 * <h2>Optimal parameters</h2>
 * Given expected insertions {@code n} and desired false-positive rate {@code p}:
 * <pre>
 *   m = -n * ln(p) / (ln 2)^2   (number of bits)
 *   k = (m / n) * ln 2           (number of hash functions)
 * </pre>
 * These are derived by minimising the FPR expression {@code (1 - e^(-kn/m))^k}.
 *
 * <h2>Double hashing</h2>
 * Rather than maintaining {@code k} independent hash functions, we use the
 * <em>Kirsch–Mitzenmacher</em> technique:
 * <pre>
 *   h_i(x) = (h1(x) + i * h2(x)) mod m
 * </pre>
 * where {@code h1} and {@code h2} are the upper/lower 32 bits of a single
 * 64-bit MurmurHash3 invocation. This gives effectively independent hash
 * positions with only two hash computations.
 *
 * <h2>Thread safety</h2>
 * {@link AtomicLongArray#get} / {@link AtomicLongArray#compareAndSet} ensure
 * that concurrent {@link #add} calls never corrupt the bit array. The element
 * count is tracked with an {@link AtomicInteger}.
 */
public class BloomFilter {

    // ln(2) constant, used in parameter formulas
    private static final double LN2 = Math.log(2.0);
    private static final double LN2_SQUARED = LN2 * LN2;

    private final int m;           // total number of bits
    private final int k;           // number of hash positions per element
    private final AtomicLongArray bits;    // bit array, each long holds 64 bits
    private final AtomicInteger count;     // number of distinct elements added

    /**
     * Constructs a Bloom filter optimised for the given capacity and error rate.
     *
     * @param expectedInsertions expected number of elements to be inserted ({@code n})
     * @param falsePositiveRate  desired false positive probability, e.g. {@code 0.01} for 1%
     * @throws IllegalArgumentException if either parameter is out of range
     */
    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive, got " + expectedInsertions);
        }
        if (falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0,1), got " + falsePositiveRate);
        }

        // m = -n * ln(p) / (ln2)^2
        this.m = optimalBitCount(expectedInsertions, falsePositiveRate);
        // k = (m/n) * ln2
        this.k = optimalHashCount(m, expectedInsertions);

        int words = (m + 63) / 64;  // ceil(m / 64)
        this.bits = new AtomicLongArray(words);
        this.count = new AtomicInteger(0);
    }

    // package-private for test verification
    int bitCount() { return m; }
    int hashCount() { return k; }

    /**
     * Adds {@code element} to the filter.
     *
     * <p>Sets {@code k} bit positions derived from the double-hash of the element.
     * This operation is thread-safe and lock-free.
     *
     * @param element the string to add (typically an IPv4/IPv6 address)
     */
    public void add(String element) {
        long combined = murmur3Hash64(element);
        int h1 = (int) (combined >>> 32);
        int h2 = (int) combined;

        for (int i = 0; i < k; i++) {
            // Kirsch-Mitzenmacher double-hashing: position = (h1 + i*h2) mod m
            // Math.floorMod ensures non-negative result even with negative hashes
            int position = Math.floorMod(h1 + i * h2, m);
            setBit(position);
        }
        count.incrementAndGet();
    }

    /**
     * Tests whether {@code element} has been added to the filter.
     *
     * <p>Returns {@code false} with certainty if the element was never added.
     * Returns {@code true} if the element was probably added, with a false positive
     * probability bounded by the configured rate at the current fill level.
     *
     * @param element the string to test
     * @return {@code false} if definitely absent; {@code true} if probably present
     */
    public boolean mightContain(String element) {
        long combined = murmur3Hash64(element);
        int h1 = (int) (combined >>> 32);
        int h2 = (int) combined;

        for (int i = 0; i < k; i++) {
            int position = Math.floorMod(h1 + i * h2, m);
            if (!getBit(position)) {
                return false;  // definite negative
            }
        }
        return true;  // probable positive
    }

    /**
     * Estimates the current false positive rate based on the number of inserted elements.
     *
     * <p>The formula is: {@code FPR = (1 - e^(-k*n/m))^k} where {@code n} is
     * the current element count. This approaches the configured rate as the filter
     * fills toward {@code expectedInsertions}.
     *
     * @return current estimated false positive probability in [0, 1]
     */
    public double expectedFalsePositiveRate() {
        int n = count.get();
        if (n == 0) {
            return 0.0;
        }
        // probability a single bit is still 0 after n insertions with k hashes over m bits
        double exponent = -(double) k * n / m;
        double probBitSet = 1.0 - Math.exp(exponent);
        return Math.pow(probBitSet, k);
    }

    /**
     * Returns the number of elements that have been added to the filter.
     *
     * <p>Note: this counts every call to {@link #add}, including duplicates.
     *
     * @return number of add() invocations
     */
    public int size() {
        return count.get();
    }

    /**
     * Factory method for IP-based rate limiting with a 1% false positive rate.
     *
     * <p>A 1% FPR means at most 1 in 100 legitimate IPs will be incorrectly
     * flagged as "bad". For blocking malicious IPs, this is a safe default —
     * the occasional false block is recoverable, while missed blocks are not.
     *
     * @param expectedBadIps estimated number of distinct malicious IPs to track
     * @return a Bloom filter sized for the given capacity at 1% FPR
     */
    public static BloomFilter forRateLimiting(int expectedBadIps) {
        return new BloomFilter(expectedBadIps, 0.01);
    }

    // -------------------------------------------------------------------------
    // Bit array operations (lock-free via CAS loop)
    // -------------------------------------------------------------------------

    private void setBit(int position) {
        int word = position >>> 6;       // position / 64
        long mask = 1L << (position & 63); // bit within the word
        long prev, next;
        do {
            prev = bits.get(word);
            next = prev | mask;
            if (next == prev) return;    // already set, no-op
        } while (!bits.compareAndSet(word, prev, next));
    }

    private boolean getBit(int position) {
        int word = position >>> 6;
        long mask = 1L << (position & 63);
        return (bits.get(word) & mask) != 0;
    }

    // -------------------------------------------------------------------------
    // MurmurHash3 — 32-bit finalised, applied twice with different seeds
    // to produce a 64-bit result for double-hashing (h1 in upper 32 bits, h2 lower)
    //
    // MurmurHash3 was created by Austin Appleby. The algorithm is in the public domain.
    // Reference: https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp
    // -------------------------------------------------------------------------

    /**
     * Computes a 64-bit hash of the given string using two independent MurmurHash3
     * (32-bit variant) invocations with different seeds.
     *
     * <p>The upper 32 bits hold {@code murmur3_32(bytes, seed=0)} and the lower
     * 32 bits hold {@code murmur3_32(bytes, seed=0x9747b28c)} (a recommended
     * second seed from the Kirsch-Mitzenmacher paper).
     *
     * @param input the string to hash (UTF-8 encoded)
     * @return 64-bit combined hash value
     */
    static long murmur3Hash64(String input) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        long h1 = murmur3_32(data, 0) & 0xFFFFFFFFL;
        long h2 = murmur3_32(data, 0x9747b28c) & 0xFFFFFFFFL;
        return (h1 << 32) | h2;
    }

    /**
     * MurmurHash3 32-bit implementation — from scratch, no external libraries.
     *
     * <p>The algorithm processes input in 4-byte blocks using carefully chosen
     * multiplication constants ({@code c1=0xcc9e2d51}, {@code c2=0x1b873593})
     * that maximise avalanche — small changes in input cause large, unpredictable
     * changes in output. This is critical for Bloom filter bit distribution.
     *
     * <p>Algorithm steps:
     * <ol>
     *   <li>Process each 4-byte block: rotate, multiply by c1/c2, XOR into hash</li>
     *   <li>Handle the remaining 1-3 byte "tail" with a partial block</li>
     *   <li>Apply finalisation mix (fmix32) to eliminate bias from length encoding</li>
     * </ol>
     *
     * @param data  input bytes
     * @param seed  hash seed (different seeds produce independent hash functions)
     * @return 32-bit hash value as an int (sign bit is data, not a sign)
     */
    static int murmur3_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        int h = seed;
        int len = data.length;
        int numBlocks = len / 4;

        // --- body: process 4-byte blocks ---
        for (int i = 0; i < numBlocks; i++) {
            int k = readLittleEndianInt(data, i * 4);

            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;

            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }

        // --- tail: handle remaining 1-3 bytes ---
        int tail = numBlocks * 4;
        int k1 = 0;
        switch (len & 3) {           // len % 4, branchless byte accumulation
            case 3: k1 ^= (data[tail + 2] & 0xFF) << 16; // fall through
            case 2: k1 ^= (data[tail + 1] & 0xFF) << 8;  // fall through
            case 1: k1 ^= (data[tail] & 0xFF);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h ^= k1;
        }

        // --- finalisation ---
        h ^= len;
        h = fmix32(h);
        return h;
    }

    /**
     * MurmurHash3 finalisation mix (fmix32).
     *
     * <p>This avalanche step ensures that all bits of {@code h} affect all output bits.
     * The XOR-shift pattern combined with the multiplication constants is designed
     * to pass the SMHasher strict avalanche criterion.
     *
     * @param h intermediate hash value
     * @return fully mixed hash value
     */
    private static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /**
     * Reads a 32-bit little-endian integer from {@code data} at {@code offset}.
     */
    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    // -------------------------------------------------------------------------
    // Parameter calculation
    // -------------------------------------------------------------------------

    /**
     * Calculates the optimal number of bits {@code m} for a Bloom filter.
     *
     * <p>Formula: {@code m = -n * ln(p) / (ln 2)^2}
     *
     * @param n expected number of insertions
     * @param p desired false positive rate
     * @return optimal bit count (minimum 1)
     */
    static int optimalBitCount(int n, double p) {
        return Math.max(1, (int) Math.ceil(-n * Math.log(p) / LN2_SQUARED));
    }

    /**
     * Calculates the optimal number of hash functions {@code k}.
     *
     * <p>Formula: {@code k = (m/n) * ln 2}
     *
     * @param m bit count
     * @param n expected insertions
     * @return optimal hash function count (minimum 1)
     */
    static int optimalHashCount(int m, int n) {
        return Math.max(1, (int) Math.round((double) m / n * LN2));
    }
}
