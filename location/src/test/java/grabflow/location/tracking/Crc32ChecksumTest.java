package grabflow.location.tracking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Crc32Checksum}.
 *
 * <p>The "123456789" check value ({@code 0xCBF43926}) is the universally
 * recognised self-test vector defined for CRC-32b / IEEE 802.3.
 */
class Crc32ChecksumTest {

    private Crc32Checksum crc;

    @BeforeEach
    void setUp() {
        crc = new Crc32Checksum();
    }

    /**
     * Standard check-value: CRC-32 of ASCII "123456789" must equal 0xCBF43926.
     * This vector is mandated by the CRC-32b specification and serves as a
     * definitive correctness guard.
     */
    @Test
    void computeKnownVector() {
        byte[] data = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int result = crc.compute(data);
        assertThat(result).isEqualTo(0xCBF43926);
    }

    /**
     * For bytes 0, 127, and 255 the table entry must equal the result of running
     * the 8-bit shift-register loop manually with the same polynomial.  This
     * confirms that {@code buildTable} matches the bit-by-bit algorithm.
     */
    @Test
    void lookupTableMatchesBitwiseComputation() {
        int polynomial = 0xEDB88320;
        int[] table = Crc32Checksum.buildTable(polynomial);

        for (int b : new int[]{0, 127, 255}) {
            int expected = bitwiseCrc(b, polynomial);
            assertThat(table[b])
                    .as("table[%d]", b)
                    .isEqualTo(expected);
        }
    }

    /**
     * CRC-32 of an empty byte array (after the 0xFFFFFFFF initial value and the
     * final XOR complement) must equal 0x00000000.
     */
    @Test
    void emptyInputReturnsZero() {
        int result = crc.compute(new byte[0]);
        assertThat(result).isEqualTo(0x00000000);
    }

    /**
     * Known CRC-32 values for single-byte inputs verified against reference
     * implementations.
     */
    @Test
    void singleByteValues() {
        // CRC-32 of a single 0x00 byte
        assertThat(crc.compute(new byte[]{(byte) 0x00})).isEqualTo(0xD202EF8D);
        // CRC-32 of a single 0xFF byte
        assertThat(crc.compute(new byte[]{(byte) 0xFF})).isEqualTo(0xFF000000);
    }

    /**
     * Flipping a single bit in a byte array must produce a different CRC, which
     * is the core property that makes CRC-32 useful for corruption detection.
     */
    @Test
    void corruptedDataDetected() {
        byte[] original = "GrabFlow GPS".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int originalCrc = crc.compute(original);

        byte[] corrupted = original.clone();
        corrupted[3] ^= 0x01; // flip one bit

        int corruptedCrc = crc.compute(corrupted);
        assertThat(corruptedCrc).isNotEqualTo(originalCrc);
    }

    /**
     * {@code compute(data, offset, length)} must return the same result as
     * {@code compute} on the equivalent sub-array slice.
     */
    @Test
    void rangeComputeMatchesFull() {
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09};
        int offset = 2;
        int length = 5;

        byte[] slice = new byte[length];
        System.arraycopy(data, offset, slice, 0, length);

        assertThat(crc.compute(data, offset, length)).isEqualTo(crc.compute(slice));
    }

    /** {@code verify} must return {@code true} when the expected CRC is correct. */
    @Test
    void verifyReturnsTrueForValidData() {
        byte[] data = "valid payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int checksum = crc.compute(data);
        assertThat(crc.verify(data, checksum)).isTrue();
    }

    /** {@code verify} must return {@code false} when the expected CRC is wrong. */
    @Test
    void verifyReturnsFalseForCorruptData() {
        byte[] data = "valid payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int checksum = crc.compute(data);
        assertThat(crc.verify(data, checksum ^ 0x1)).isFalse();
    }

    /**
     * The first entry of the lookup table built with the IEEE 802.3 polynomial
     * ({@code 0xEDB88320}) is deterministic and equals the value obtained by
     * processing byte value 0 through 8 rounds of the reflected shift register.
     * For polynomial 0xEDB88320 and input byte 0, all 8 iterations shift right
     * without XOR, so table[0] == 0.
     */
    @Test
    void polynomialIsIeee802_3() {
        int[] table = Crc32Checksum.buildTable(0xEDB88320);
        // byte 0: all 8 bits are 0, LSB never set → crc never XOR'd → table[0] == 0
        assertThat(table[0]).isEqualTo(0x00000000);
        // byte 1: first iteration has LSB=1 → XOR with polynomial
        assertThat(table[1]).isEqualTo(0x77073096);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Manual 8-bit shift-register for a single byte — used to cross-check the table. */
    private static int bitwiseCrc(int byteValue, int polynomial) {
        int crc = byteValue;
        for (int i = 0; i < 8; i++) {
            if ((crc & 1) != 0) {
                crc = (crc >>> 1) ^ polynomial;
            } else {
                crc = crc >>> 1;
            }
        }
        return crc;
    }
}
