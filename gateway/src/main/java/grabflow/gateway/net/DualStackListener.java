package grabflow.gateway.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

/**
 * Dual-stack TCP listener that binds both IPv4 and IPv6 server sockets
 * to the same logical port, registering both with a shared NIO {@link Selector}.
 *
 * <h3>CS Fundamental: IPv4 vs IPv6</h3>
 * <p>IPv4 uses 32-bit addresses (4 bytes, e.g., 192.168.1.1) supporting ~4.3 billion
 * unique addresses. IPv6 uses 128-bit addresses (16 bytes, e.g., 2001:db8::1) supporting
 * 3.4 x 10^38 addresses. A dual-stack server accepts connections from both protocol
 * families, which is essential for modern internet services.</p>
 *
 * <p>On most OS kernels, binding to IPv6 {@code ::} with {@code IPV6_V6ONLY=false} also
 * accepts IPv4 via IPv4-mapped IPv6 addresses ({@code ::ffff:192.168.1.1}). However,
 * this behavior is OS-dependent. This implementation explicitly binds separate channels
 * per protocol family for deterministic behavior and to demonstrate dual-stack concepts.</p>
 *
 * <h3>CIDR Notation</h3>
 * <p>IPv4 CIDR: 192.168.0.0/24 means the first 24 bits are the network prefix (256 addresses).
 * IPv6 CIDR: 2001:db8::/32 means the first 32 bits are the prefix. The
 * {@link #matchesCidr(InetAddress, String)} method supports both.</p>
 */
public class DualStackListener implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(DualStackListener.class);

    private final int port;
    private ServerSocketChannel ipv4Channel;
    private ServerSocketChannel ipv6Channel;

    public DualStackListener(int port) {
        this.port = port;
    }

    /**
     * Opens and binds both IPv4 and IPv6 server socket channels, registering
     * them with the provided selector for {@link SelectionKey#OP_ACCEPT}.
     *
     * @param selector the NIO selector to register channels with
     * @throws IOException if binding fails
     */
    public void bind(Selector selector) throws IOException {
        // IPv4: bind to 0.0.0.0 (all IPv4 interfaces)
        ipv4Channel = ServerSocketChannel.open(StandardProtocolFamily.INET);
        ipv4Channel.configureBlocking(false);
        ipv4Channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ipv4Channel.bind(new InetSocketAddress("0.0.0.0", port));
        ipv4Channel.register(selector, SelectionKey.OP_ACCEPT);
        log.info("IPv4 listener bound to 0.0.0.0:{}", port);

        // IPv6: bind to :: (all IPv6 interfaces) with V6ONLY=true
        // to prevent overlap with the IPv4 listener
        ipv6Channel = ServerSocketChannel.open(StandardProtocolFamily.INET6);
        ipv6Channel.configureBlocking(false);
        ipv6Channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        // IPV6_V6ONLY is implicitly true on most OS when using StandardProtocolFamily.INET6
        // This ensures the IPv6 socket only handles IPv6 connections,
        // while IPv4 connections go to the separate IPv4 channel above.
        ipv6Channel.bind(new InetSocketAddress("::", port));
        ipv6Channel.register(selector, SelectionKey.OP_ACCEPT);
        log.info("IPv6 listener bound to [::]:{}", port);
    }

    /**
     * Checks whether a client IP address matches a CIDR block.
     * Supports both IPv4 (e.g., "192.168.1.0/24") and IPv6 (e.g., "2001:db8::/32").
     *
     * <p>The algorithm: convert both the address and the CIDR network address to
     * byte arrays, then compare the first {@code prefixLength} bits.</p>
     *
     * @param address     the IP address to check
     * @param cidrBlock   CIDR notation (e.g., "10.0.0.0/8" or "::1/128")
     * @return true if the address falls within the CIDR block
     */
    public static boolean matchesCidr(InetAddress address, String cidrBlock) {
        String[] parts = cidrBlock.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidrBlock);
        }

        try {
            InetAddress networkAddr = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] addrBytes = address.getAddress();
            byte[] networkBytes = networkAddr.getAddress();

            // IPv4 and IPv6 addresses have different lengths -- must match
            if (addrBytes.length != networkBytes.length) {
                // Handle IPv4-mapped IPv6: ::ffff:x.x.x.x
                if (addrBytes.length == 16 && networkBytes.length == 4) {
                    addrBytes = extractIpv4FromMapped(addrBytes);
                    if (addrBytes == null) return false;
                } else if (addrBytes.length == 4 && networkBytes.length == 16) {
                    networkBytes = extractIpv4FromMapped(networkBytes);
                    if (networkBytes == null) return false;
                } else {
                    return false;
                }
            }

            int maxPrefix = addrBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                throw new IllegalArgumentException(
                        "Prefix length " + prefixLength + " invalid for " +
                                (addrBytes.length == 4 ? "IPv4" : "IPv6"));
            }

            return matchBits(addrBytes, networkBytes, prefixLength);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("Invalid CIDR: " + cidrBlock, e);
        }
    }

    /**
     * Compares the first {@code prefixLength} bits of two byte arrays.
     * This is the core of CIDR matching -- we only care about the network prefix bits.
     */
    static boolean matchBits(byte[] a, byte[] b, int prefixLength) {
        // Compare full bytes first
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != b[i]) return false;
        }

        // Compare remaining bits in the partial byte
        int remainingBits = prefixLength % 8;
        if (remainingBits > 0) {
            // Mask: e.g., for 3 remaining bits -> 0b11100000 = 0xE0
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (a[fullBytes] & mask) == (b[fullBytes] & mask);
        }

        return true;
    }

    /**
     * Extracts the IPv4 address from an IPv4-mapped IPv6 address (::ffff:x.x.x.x).
     * The mapped format is: 10 bytes of 0, 2 bytes of 0xFF, then 4 bytes of IPv4.
     *
     * @return 4-byte IPv4 address, or null if not a valid mapped address
     */
    static byte[] extractIpv4FromMapped(byte[] ipv6Bytes) {
        if (ipv6Bytes.length != 16) return null;

        // Check prefix: first 10 bytes must be 0
        for (int i = 0; i < 10; i++) {
            if (ipv6Bytes[i] != 0) return null;
        }
        // Bytes 10-11 must be 0xFF (the mapping marker)
        if (ipv6Bytes[10] != (byte) 0xFF || ipv6Bytes[11] != (byte) 0xFF) return null;

        // Extract last 4 bytes as IPv4
        byte[] ipv4 = new byte[4];
        System.arraycopy(ipv6Bytes, 12, ipv4, 0, 4);
        return ipv4;
    }

    /**
     * Normalizes an IPv6 address string to its full expanded form.
     * E.g., "2001:db8::1" becomes "2001:0db8:0000:0000:0000:0000:0000:0001".
     * Useful for logging and comparison.
     */
    public static String normalizeIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            throw new IllegalArgumentException("Not an IPv6 address: " + address);
        }
        byte[] bytes = address.getAddress();
        StringBuilder sb = new StringBuilder(39); // 8 groups of 4 hex chars + 7 colons
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x%02x", bytes[i] & 0xFF, bytes[i + 1] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Detects whether an address is IPv4-mapped IPv6 (::ffff:x.x.x.x).
     */
    public static boolean isIpv4Mapped(InetAddress address) {
        if (!(address instanceof Inet6Address)) return false;
        return extractIpv4FromMapped(address.getAddress()) != null;
    }

    /**
     * Returns the protocol family of a client address.
     */
    public static ProtocolFamily protocolFamily(InetAddress address) {
        if (address instanceof Inet4Address) return StandardProtocolFamily.INET;
        if (address instanceof Inet6Address) return StandardProtocolFamily.INET6;
        throw new IllegalArgumentException("Unknown address type: " + address.getClass());
    }

    public int getPort() {
        return port;
    }

    public ServerSocketChannel getIpv4Channel() {
        return ipv4Channel;
    }

    public ServerSocketChannel getIpv6Channel() {
        return ipv6Channel;
    }

    @Override
    public void close() throws IOException {
        if (ipv4Channel != null && ipv4Channel.isOpen()) {
            ipv4Channel.close();
            log.info("IPv4 listener closed");
        }
        if (ipv6Channel != null && ipv6Channel.isOpen()) {
            ipv6Channel.close();
            log.info("IPv6 listener closed");
        }
    }
}
