package grabflow.location.tracking;

/**
 * From-scratch CRC-32 implementation using lookup-table optimization.
 *
 * <h2>What is CRC-32?</h2>
 * CRC-32 (Cyclic Redundancy Check, 32-bit) is an error-detection algorithm that
 * treats a byte stream as a large binary polynomial and divides it by a fixed
 * generator polynomial over GF(2) — the Galois Field with two elements {0, 1}.
 * Arithmetic in GF(2) uses XOR for addition and AND for multiplication; there is
 * no carry or borrow. The remainder of the division is the checksum.
 *
 * <h2>GF(2) Polynomial Arithmetic</h2>
 * Every byte sequence can be viewed as the coefficients of a polynomial whose
 * terms have coefficients in {0,1}:
 * <pre>
 *   data = b_{n-1} x^{n-1} + ... + b_1 x + b_0
 * </pre>
 * Division of {@code data(x)} by the generator {@code G(x)} yields:
 * <pre>
 *   data(x) = Q(x) * G(x) + R(x)
 * </pre>
 * where {@code R(x)} is the CRC. Because addition in GF(2) is XOR, subtraction
 * equals addition, and long-division reduces to a sequence of XOR and shift
 * operations.
 *
 * <h2>Bit-by-bit vs. Lookup-Table</h2>
 * A naive implementation processes 8 bits per input byte → O(8n) XOR+shift ops.
 * The lookup-table approach precomputes the CRC contribution for all 256 possible
 * byte values once at construction time and then processes each input byte in
 * O(1) with a single table lookup + XOR + right-shift → O(n) total.
 *
 * <h2>The IEEE 802.3 Polynomial</h2>
 * The standard polynomial is {@code x^32 + x^26 + x^23 + x^22 + x^16 + x^12 +
 * x^11 + x^10 + x^8 + x^7 + x^5 + x^4 + x^2 + x + 1}, expressed in hexadecimal
 * as {@code 0x04C11DB7} in normal (MSB-first) form. The CRC-32b / IEEE 802.3
 * variant processes bits LSB-first, which is equivalent to using the bit-reversed
 * ("reflected") polynomial {@code 0xEDB88320}. This reflected form is used
 * throughout this implementation.
 *
 * <p>Usage:
 * <pre>{@code
 *   Crc32Checksum crc = new Crc32Checksum();
 *   int checksum = crc.compute(data);
 *   boolean ok    = crc.verify(data, checksum);
 * }</pre>
 */
public class Crc32Checksum {

    /** Bit-reflected IEEE 802.3 generator polynomial. */
    private static final int POLYNOMIAL = 0xEDB88320;

    /** 256-entry lookup table, one entry per possible byte value. */
    private final int[] table;

    /**
     * Constructs a {@code Crc32Checksum} using the standard IEEE 802.3 reflected
     * polynomial {@code 0xEDB88320}.
     */
    public Crc32Checksum() {
        this.table = buildTable(POLYNOMIAL);
    }

    /**
     * Builds a 256-entry CRC lookup table for the given reflected polynomial.
     *
     * <p>For each byte value {@code b} in [0, 255] the table entry is computed by
     * running 8 rounds of the reflected shift-register:
     * <pre>
     *   crc = b
     *   repeat 8 times:
     *     if (crc &amp; 1) != 0: crc = (crc >>> 1) ^ polynomial
     *     else:               crc = (crc >>> 1)
     * </pre>
     * This is package-private to allow direct inspection in unit tests.
     *
     * @param polynomial the bit-reflected generator polynomial
     * @return a 256-element array of pre-computed CRC remainders
     */
    static int[] buildTable(int polynomial) {
        int[] tbl = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ polynomial;
                } else {
                    crc = crc >>> 1;
                }
            }
            tbl[i] = crc;
        }
        return tbl;
    }

    /**
     * Computes the CRC-32 of the entire byte array.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Start with {@code crc = 0xFFFFFFFF} (all-ones initial value).</li>
     *   <li>For each byte {@code b}: {@code crc = table[(crc ^ b) & 0xFF] ^ (crc >>> 8)}</li>
     *   <li>Return {@code crc ^ 0xFFFFFFFF} (final XOR complement).</li>
     * </ol>
     *
     * @param data input bytes (must not be {@code null})
     * @return the 32-bit CRC as a signed {@code int}
     */
    public int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    /**
     * Computes the CRC-32 over a sub-range of a byte array.
     *
     * @param data   input bytes (must not be {@code null})
     * @param offset starting index (inclusive)
     * @param length number of bytes to process
     * @return the 32-bit CRC as a signed {@code int}
     * @throws ArrayIndexOutOfBoundsException if offset/length are out of range
     */
    public int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    /**
     * Verifies that the CRC-32 of {@code data} matches {@code expectedCrc}.
     *
     * @param data        input bytes
     * @param expectedCrc the CRC value to check against
     * @return {@code true} if the computed CRC equals {@code expectedCrc}
     */
    public boolean verify(byte[] data, int expectedCrc) {
        return compute(data) == expectedCrc;
    }
}
