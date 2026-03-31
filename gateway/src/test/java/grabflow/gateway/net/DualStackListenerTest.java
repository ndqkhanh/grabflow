package grabflow.gateway.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.Selector;

import static org.assertj.core.api.Assertions.*;

class DualStackListenerTest {

    // ── CIDR Matching: IPv4 ──

    @ParameterizedTest
    @CsvSource({
            "192.168.1.100, 192.168.1.0/24, true",
            "192.168.1.0,   192.168.1.0/24, true",
            "192.168.1.255, 192.168.1.0/24, true",
            "192.168.2.1,   192.168.1.0/24, false",
            "10.0.0.1,      10.0.0.0/8,     true",
            "10.255.255.255,10.0.0.0/8,     true",
            "11.0.0.1,      10.0.0.0/8,     false",
            "172.16.5.4,    172.16.0.0/12,  true",
            "172.32.0.1,    172.16.0.0/12,  false",
            "1.2.3.4,       1.2.3.4/32,     true",
            "1.2.3.5,       1.2.3.4/32,     false",
            "0.0.0.0,       0.0.0.0/0,      true",
            "255.255.255.255,0.0.0.0/0,     true",
    })
    void cidrMatchingIpv4(String ip, String cidr, boolean expected) throws Exception {
        InetAddress addr = InetAddress.getByName(ip);
        assertThat(DualStackListener.matchesCidr(addr, cidr)).isEqualTo(expected);
    }

    // ── CIDR Matching: IPv6 ──

    @ParameterizedTest
    @CsvSource({
            "2001:db8::1,          2001:db8::/32,    true",
            "2001:db8:ffff::1,     2001:db8::/32,    true",
            "2001:db9::1,          2001:db8::/32,    false",
            "::1,                  ::1/128,          true",
            "::2,                  ::1/128,          false",
            "fe80::1,              fe80::/10,        true",
            "fe80::abcd:1234,      fe80::/10,        true",
    })
    void cidrMatchingIpv6(String ip, String cidr, boolean expected) throws Exception {
        InetAddress addr = InetAddress.getByName(ip);
        assertThat(DualStackListener.matchesCidr(addr, cidr)).isEqualTo(expected);
    }

    // ── Bit Matching ──

    @Test
    void matchBitsFullByteComparison() {
        byte[] a = {(byte) 0xC0, (byte) 0xA8, 0x01, 0x64}; // 192.168.1.100
        byte[] b = {(byte) 0xC0, (byte) 0xA8, 0x01, 0x00}; // 192.168.1.0
        // /24 = first 3 bytes must match
        assertThat(DualStackListener.matchBits(a, b, 24)).isTrue();
        // /32 = all 4 bytes must match
        assertThat(DualStackListener.matchBits(a, b, 32)).isFalse();
    }

    @Test
    void matchBitsPartialByte() {
        // 10.0.0.0/8: only first byte matters
        byte[] a = {10, 1, 2, 3};
        byte[] b = {10, 0, 0, 0};
        assertThat(DualStackListener.matchBits(a, b, 8)).isTrue();

        // /12: first byte + upper 4 bits of second byte
        // 172.16.x.x vs 172.31.x.x -- 172 = 0xAC, 16 = 0x10, 31 = 0x1F
        // Upper 4 bits of 0x10 = 0x1, upper 4 bits of 0x1F = 0x1 -> match
        byte[] c = {(byte) 0xAC, 0x10, 0x05, 0x04}; // 172.16.5.4
        byte[] d = {(byte) 0xAC, 0x1F, (byte) 0xFF, (byte) 0xFF}; // 172.31.255.255
        assertThat(DualStackListener.matchBits(c, d, 12)).isTrue();

        // 172.32.0.1 -- 0x20 upper 4 bits = 0x2, doesn't match 0x1
        byte[] e = {(byte) 0xAC, 0x20, 0x00, 0x01};
        assertThat(DualStackListener.matchBits(e, d, 12)).isFalse();
    }

    @Test
    void matchBitsZeroPrefixMatchesEverything() {
        byte[] a = {1, 2, 3, 4};
        byte[] b = {5, 6, 7, 8};
        assertThat(DualStackListener.matchBits(a, b, 0)).isTrue();
    }

    // ── IPv4-Mapped IPv6 ──

    @Test
    void extractIpv4FromMappedAddress() {
        // ::ffff:192.168.1.1 = 10 zero bytes + 0xFF 0xFF + 192 168 1 1
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) 192;
        mapped[13] = (byte) 168;
        mapped[14] = 1;
        mapped[15] = 1;

        byte[] ipv4 = DualStackListener.extractIpv4FromMapped(mapped);
        assertThat(ipv4).isNotNull();
        assertThat(ipv4).containsExactly((byte) 192, (byte) 168, 1, 1);
    }

    @Test
    void extractIpv4FromNonMappedReturnsNull() {
        // Regular IPv6 address (not a mapped one)
        byte[] regular = new byte[16];
        regular[0] = 0x20;
        regular[1] = 0x01;
        assertThat(DualStackListener.extractIpv4FromMapped(regular)).isNull();
    }

    @Test
    void extractIpv4FromWrongLengthReturnsNull() {
        assertThat(DualStackListener.extractIpv4FromMapped(new byte[4])).isNull();
    }

    @Test
    void isIpv4MappedDetectsCorrectly() throws Exception {
        // ::ffff:127.0.0.1 is IPv4-mapped
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        if (mapped instanceof Inet6Address) {
            assertThat(DualStackListener.isIpv4Mapped(mapped)).isTrue();
        }

        // Regular IPv4 is not IPv4-mapped
        InetAddress ipv4 = InetAddress.getByName("127.0.0.1");
        assertThat(DualStackListener.isIpv4Mapped(ipv4)).isFalse();
    }

    // ── IPv6 Normalization ──

    @Test
    void normalizeIpv6ExpandsAbbreviatedAddress() throws Exception {
        InetAddress addr = Inet6Address.getByName("2001:db8::1");
        String normalized = DualStackListener.normalizeIpv6(addr);
        assertThat(normalized).isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
    }

    @Test
    void normalizeIpv6LoopbackAddress() throws Exception {
        InetAddress addr = Inet6Address.getByName("::1");
        String normalized = DualStackListener.normalizeIpv6(addr);
        assertThat(normalized).isEqualTo("0000:0000:0000:0000:0000:0000:0000:0001");
    }

    @Test
    void normalizeIpv6RejectsIpv4() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.1.1");
        assertThatThrownBy(() -> DualStackListener.normalizeIpv6(addr))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Protocol Family Detection ──

    @Test
    void protocolFamilyDetectsIpv4() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        assertThat(DualStackListener.protocolFamily(addr)).isEqualTo(StandardProtocolFamily.INET);
    }

    @Test
    void protocolFamilyDetectsIpv6() throws Exception {
        InetAddress addr = InetAddress.getByName("::1");
        assertThat(DualStackListener.protocolFamily(addr)).isEqualTo(StandardProtocolFamily.INET6);
    }

    // ── Invalid CIDR ──

    @Test
    void invalidCidrThrowsException() {
        assertThatThrownBy(() ->
                DualStackListener.matchesCidr(InetAddress.getByName("1.2.3.4"), "not-a-cidr"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidPrefixLengthThrowsException() {
        assertThatThrownBy(() ->
                DualStackListener.matchesCidr(InetAddress.getByName("1.2.3.4"), "1.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Bind and Close ──

    @Test
    void bindAndCloseLifecycle() throws Exception {
        var listener = new DualStackListener(0); // port 0 = ephemeral
        try (var selector = Selector.open()) {
            listener.bind(selector);

            assertThat(listener.getIpv4Channel()).isNotNull();
            assertThat(listener.getIpv4Channel().isOpen()).isTrue();
            assertThat(listener.getIpv6Channel()).isNotNull();
            assertThat(listener.getIpv6Channel().isOpen()).isTrue();

            // Both channels registered with the selector
            assertThat(selector.keys()).hasSize(2);

            listener.close();
            assertThat(listener.getIpv4Channel().isOpen()).isFalse();
            assertThat(listener.getIpv6Channel().isOpen()).isFalse();
        }
    }
}
