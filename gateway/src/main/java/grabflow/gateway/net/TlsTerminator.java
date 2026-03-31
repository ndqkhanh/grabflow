package grabflow.gateway.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TLS 1.3 terminator with ALPN negotiation and certificate hot-rotation.
 *
 * <h3>CS Fundamental: TLS 1.3 Handshake</h3>
 * <p>TLS 1.3 (RFC 8446) reduces the handshake from 2-RTT to 1-RTT compared to TLS 1.2.
 * The handshake flow:</p>
 * <pre>
 *   Client                              Server
 *     │──── ClientHello ────────────────▶│  (supported_versions, key_share, ALPN)
 *     │                                  │
 *     │◀─── ServerHello ────────────────│  (selected key_share, selected version)
 *     │◀─── EncryptedExtensions ────────│  (selected ALPN protocol)
 *     │◀─── Certificate ────────────────│  (server certificate chain)
 *     │◀─── CertificateVerify ──────────│  (signature over handshake transcript)
 *     │◀─── Finished ──────────────────│  (HMAC over handshake transcript)
 *     │                                  │
 *     │──── Finished ────────────────────▶│
 *     │                                  │
 *     │◀──── Application Data ──────────▶│  (encrypted with AEAD cipher)
 * </pre>
 *
 * <h3>Key Differences from TLS 1.2</h3>
 * <ul>
 *   <li><b>1-RTT handshake</b>: Client sends key_share in ClientHello (vs 2-RTT in 1.2)</li>
 *   <li><b>Forward secrecy mandatory</b>: Only ephemeral ECDHE key exchange (no static RSA)</li>
 *   <li><b>AEAD-only ciphers</b>: AES-GCM or ChaCha20-Poly1305 (no CBC, no RC4)</li>
 *   <li><b>0-RTT resumption</b>: Optional early data using pre-shared keys (PSK)</li>
 *   <li><b>Encrypted extensions</b>: Server extensions are encrypted (vs plaintext in 1.2)</li>
 * </ul>
 *
 * <h3>ALPN (Application-Layer Protocol Negotiation)</h3>
 * <p>ALPN is a TLS extension (RFC 7301) that lets the client and server agree on an
 * application protocol during the TLS handshake, avoiding an extra round-trip.
 * The gateway uses ALPN to negotiate between HTTP/2 ("h2") and HTTP/1.1.</p>
 *
 * <h3>Certificate Hot-Rotation</h3>
 * <p>Uses a {@link WatchService} to monitor the certificate directory for changes.
 * When a certificate file is modified, the {@link SSLContext} is atomically replaced
 * via an {@link AtomicReference}. Existing connections continue with their original
 * context; only new connections use the updated certificate.</p>
 */
public class TlsTerminator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(TlsTerminator.class);

    /**
     * TLS 1.3 cipher suites supported by JDK 21.
     * All use AEAD (Authenticated Encryption with Associated Data).
     */
    private static final String[] TLS13_CIPHER_SUITES = {
            "TLS_AES_256_GCM_SHA384",        // AES-256 in GCM mode, SHA-384 for HKDF
            "TLS_AES_128_GCM_SHA256",        // AES-128 in GCM mode, SHA-256 for HKDF
            "TLS_CHACHA20_POLY1305_SHA256"   // ChaCha20-Poly1305, good for non-AES-NI hardware
    };

    /**
     * ALPN protocols: prefer HTTP/2 over HTTP/1.1.
     */
    private static final String[] ALPN_PROTOCOLS = {"h2", "http/1.1"};

    private final String keystorePath;
    private final char[] keystorePassword;
    private final String keystoreType;
    private final AtomicReference<SSLContext> sslContextRef = new AtomicReference<>();
    private volatile boolean watcherRunning;
    private Thread watcherThread;

    /**
     * Creates a TLS terminator with the specified keystore.
     *
     * @param keystorePath     path to the keystore file (JKS or PKCS12)
     * @param keystorePassword keystore password
     * @param keystoreType     keystore type: "JKS" or "PKCS12"
     */
    public TlsTerminator(String keystorePath, String keystorePassword, String keystoreType) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword.toCharArray();
        this.keystoreType = keystoreType;
    }

    public TlsTerminator(String keystorePath, String keystorePassword) {
        this(keystorePath, keystorePassword, "PKCS12");
    }

    /**
     * Initializes the SSL context and optionally starts the certificate file watcher.
     *
     * @param enableHotRotation if true, watches the keystore file for changes
     */
    public void init(boolean enableHotRotation) throws Exception {
        SSLContext ctx = buildSslContext();
        sslContextRef.set(ctx);
        log.info("TLS 1.3 context initialized with keystore: {} (type: {})", keystorePath, keystoreType);
        logEnabledProtocols(ctx);

        if (enableHotRotation) {
            startCertificateWatcher();
        }
    }

    /**
     * Creates an {@link SSLEngine} configured for server-side TLS 1.3 with ALPN.
     *
     * <p>The SSLEngine is a state machine that drives the TLS handshake:
     * <pre>
     *   wrap()   → produces outbound TLS records (handshake + app data)
     *   unwrap() → consumes inbound TLS records
     * </pre>
     * After the handshake completes, {@code getHandshakeStatus() == FINISHED},
     * and the negotiated ALPN protocol is available via
     * {@code engine.getApplicationProtocol()}.</p>
     *
     * @return configured SSLEngine in server mode
     */
    public SSLEngine createEngine() {
        SSLContext ctx = sslContextRef.get();
        if (ctx == null) {
            throw new IllegalStateException("TLS not initialized -- call init() first");
        }

        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(false);

        // Enforce TLS 1.3 only
        SSLParameters params = engine.getSSLParameters();
        params.setProtocols(new String[]{"TLSv1.3"});
        params.setCipherSuites(TLS13_CIPHER_SUITES);

        // ALPN: advertise supported application protocols
        params.setApplicationProtocols(ALPN_PROTOCOLS);

        engine.setSSLParameters(params);
        return engine;
    }

    /**
     * Returns the ALPN-negotiated protocol after TLS handshake completes.
     * Returns "h2" for HTTP/2 or "http/1.1" for HTTP/1.1.
     *
     * @param engine the SSLEngine after handshake completion
     * @return negotiated protocol string, or empty string if ALPN was not used
     */
    public static String getNegotiatedProtocol(SSLEngine engine) {
        String protocol = engine.getApplicationProtocol();
        return protocol != null ? protocol : "";
    }

    /**
     * Allocates correctly-sized ByteBuffers for TLS record processing.
     * TLS records have maximum sizes defined by the SSLSession:
     * <ul>
     *   <li>Application buffer: max plaintext size (typically 16 KB)</li>
     *   <li>Network buffer: max TLS record size (typically 16 KB + TLS overhead)</li>
     * </ul>
     */
    public static ByteBuffer[] allocateBuffers(SSLEngine engine) {
        SSLSession session = engine.getSession();
        ByteBuffer appBuffer = ByteBuffer.allocate(session.getApplicationBufferSize());
        ByteBuffer netBuffer = ByteBuffer.allocate(session.getPacketBufferSize());
        return new ByteBuffer[]{appBuffer, netBuffer};
    }

    /**
     * Returns the currently active cipher suites.
     */
    public List<String> getActiveCipherSuites() {
        SSLContext ctx = sslContextRef.get();
        if (ctx == null) return List.of();
        SSLEngine engine = ctx.createSSLEngine();
        return Arrays.asList(engine.getEnabledCipherSuites());
    }

    /**
     * Returns the current SSL context (for testing and introspection).
     */
    public SSLContext getSslContext() {
        return sslContextRef.get();
    }

    // ── Internal: SSLContext construction ──

    private SSLContext buildSslContext() throws Exception {
        KeyStore ks = loadKeyStore();

        // KeyManager: provides our server certificate and private key
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);

        // TrustManager: validates client certificates (for mTLS)
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        // Create context explicitly for TLSv1.3
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    private KeyStore loadKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance(keystoreType);
        File f = new File(keystorePath);
        if (!f.exists()) {
            throw new FileNotFoundException("Keystore not found: " + keystorePath);
        }
        try (InputStream is = new FileInputStream(f)) {
            ks.load(is, keystorePassword);
        }
        return ks;
    }

    private void logEnabledProtocols(SSLContext ctx) {
        SSLEngine engine = ctx.createSSLEngine();
        log.info("Enabled TLS protocols: {}", Arrays.asList(engine.getEnabledProtocols()));
        log.info("ALPN protocols: {}", Arrays.asList(ALPN_PROTOCOLS));
    }

    // ── Certificate Hot-Rotation via WatchService ──

    /**
     * Starts a virtual thread that monitors the keystore file for modifications.
     * When detected, atomically reloads the {@link SSLContext}.
     *
     * <p>Uses {@link WatchService} (backed by OS-native file events like inotify on Linux
     * or kqueue on macOS) for efficient file change detection without polling.</p>
     */
    private void startCertificateWatcher() {
        watcherRunning = true;
        watcherThread = Thread.ofVirtual()
                .name("tls-cert-watcher")
                .start(() -> {
                    Path certDir = Path.of(keystorePath).getParent();
                    if (certDir == null) certDir = Path.of(".");
                    String certFileName = Path.of(keystorePath).getFileName().toString();

                    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                        certDir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                        log.info("Certificate watcher started, monitoring: {}", certDir);

                        while (watcherRunning) {
                            WatchKey key;
                            try {
                                key = watcher.take(); // blocks until event
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                            for (WatchEvent<?> event : key.pollEvents()) {
                                Path changed = (Path) event.context();
                                if (changed != null && changed.toString().equals(certFileName)) {
                                    reloadCertificate();
                                }
                            }

                            if (!key.reset()) {
                                log.warn("Certificate watch key invalidated");
                                break;
                            }
                        }
                    } catch (IOException e) {
                        log.error("Certificate watcher failed", e);
                    }
                });
    }

    /**
     * Atomically reloads the SSL context from the keystore file.
     * New connections will use the updated certificate; existing connections
     * continue with their original SSLEngine (no disruption).
     */
    private void reloadCertificate() {
        try {
            // Small delay to ensure file write is complete
            Thread.sleep(500);
            SSLContext newCtx = buildSslContext();
            SSLContext oldCtx = sslContextRef.getAndSet(newCtx);
            log.info("Certificate hot-reloaded from: {} (old context replaced)", keystorePath);
        } catch (Exception e) {
            log.error("Failed to reload certificate -- keeping existing context", e);
        }
    }

    // ── Self-Signed Keystore Generation (for testing) ──

    /**
     * Generates a self-signed PKCS12 keystore for testing.
     * Uses keytool with RSA-2048 and SHA-256 signature.
     */
    public static void generateSelfSignedKeystore(String path, String password) throws Exception {
        File f = new File(path);
        if (f.exists()) f.delete();
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "grabflow",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-validity", "1",
                "-dname", "CN=localhost, OU=Gateway, O=GrabFlow, L=HCMC, ST=HCMC, C=VN",
                "-keystore", path,
                "-storepass", password,
                "-keypass", password,
                "-storetype", "PKCS12",
                "-noprompt"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool failed (exit " + exitCode + "): " + new String(output));
        }
    }

    @Override
    public void close() throws IOException {
        watcherRunning = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }
}
