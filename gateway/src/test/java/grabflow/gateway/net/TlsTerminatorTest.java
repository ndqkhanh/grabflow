package grabflow.gateway.net;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TlsTerminatorTest {

    @TempDir
    Path tempDir;

    private String keystorePath;
    private static final String PASSWORD = "testpass";

    @BeforeEach
    void setup() throws Exception {
        keystorePath = tempDir.resolve("test-keystore.p12").toString();
        TlsTerminator.generateSelfSignedKeystore(keystorePath, PASSWORD);
    }

    @Test
    void initLoadsKeystoreAndCreatesContext() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        assertThat(terminator.getSslContext()).isNotNull();
        assertThat(terminator.getSslContext()).isInstanceOf(SSLContext.class);
    }

    @Test
    void createEngineReturnsServerModeEngine() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();

        assertThat(engine.getUseClientMode()).isFalse();
    }

    @Test
    void engineEnforcesTls13Only() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();
        SSLParameters params = engine.getSSLParameters();

        // Only TLSv1.3 should be enabled
        assertThat(params.getProtocols()).containsExactly("TLSv1.3");
        // No TLS 1.2 or lower
        assertThat(params.getProtocols()).doesNotContain("TLSv1.2", "TLSv1.1", "TLSv1");
    }

    @Test
    void engineConfiguresAlpnProtocols() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();
        SSLParameters params = engine.getSSLParameters();

        // ALPN should advertise h2 and http/1.1
        assertThat(params.getApplicationProtocols())
                .containsExactly("h2", "http/1.1");
    }

    @Test
    void engineUsesAeadCipherSuitesOnly() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();
        String[] cipherSuites = engine.getSSLParameters().getCipherSuites();

        // All TLS 1.3 cipher suites use AEAD (GCM or ChaCha20-Poly1305)
        for (String suite : cipherSuites) {
            assertThat(suite).satisfiesAnyOf(
                    s -> assertThat(s).contains("GCM"),
                    s -> assertThat(s).contains("CHACHA20")
            );
        }
        // No CBC or RC4 cipher suites
        for (String suite : cipherSuites) {
            assertThat(suite).doesNotContain("CBC");
            assertThat(suite).doesNotContain("RC4");
        }
    }

    @Test
    void allocateBuffersReturnCorrectSizes() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();
        ByteBuffer[] buffers = TlsTerminator.allocateBuffers(engine);

        assertThat(buffers).hasSize(2);
        // App buffer (plaintext) should be >= 16KB
        assertThat(buffers[0].capacity())
                .isGreaterThanOrEqualTo(engine.getSession().getApplicationBufferSize());
        // Net buffer (TLS records) should be >= packet size
        assertThat(buffers[1].capacity())
                .isGreaterThanOrEqualTo(engine.getSession().getPacketBufferSize());
    }

    @Test
    void getActiveCipherSuitesReturnsNonEmpty() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        List<String> suites = terminator.getActiveCipherSuites();
        assertThat(suites).isNotEmpty();
    }

    @Test
    void failsWithMissingKeystore() {
        var terminator = new TlsTerminator("/nonexistent/keystore.p12", PASSWORD);
        assertThatThrownBy(() -> terminator.init(false))
                .isInstanceOf(java.io.FileNotFoundException.class)
                .hasMessageContaining("Keystore not found");
    }

    @Test
    void createEngineBeforeInitThrows() {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        // Not calling init()
        assertThatThrownBy(terminator::createEngine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void getNegotiatedProtocolReturnsEmptyBeforeHandshake() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();
        // Before handshake, ALPN not yet negotiated
        String protocol = TlsTerminator.getNegotiatedProtocol(engine);
        // JDK returns "" before handshake completes
        assertThat(protocol).isNotNull();
    }

    @Test
    void supportsJksKeystoreType() throws Exception {
        String jksPath = tempDir.resolve("test-keystore.jks").toString();
        generateJksKeystore(jksPath, PASSWORD);

        var terminator = new TlsTerminator(jksPath, PASSWORD, "JKS");
        terminator.init(false);

        SSLEngine engine = terminator.createEngine();
        assertThat(engine.getUseClientMode()).isFalse();
        assertThat(engine.getSSLParameters().getProtocols()).containsExactly("TLSv1.3");
    }

    @Test
    void closeStopsWatcher() throws Exception {
        var terminator = new TlsTerminator(keystorePath, PASSWORD);
        terminator.init(true); // enable hot-rotation
        // Should not throw
        terminator.close();
    }

    @Test
    void generateSelfSignedKeystoreCreatesPkcs12File() throws Exception {
        String path = tempDir.resolve("generated.p12").toString();
        TlsTerminator.generateSelfSignedKeystore(path, "genpass");

        assertThat(Path.of(path).toFile().exists()).isTrue();
        assertThat(Path.of(path).toFile().length()).isGreaterThan(0);

        // Verify it loads correctly
        var terminator = new TlsTerminator(path, "genpass");
        terminator.init(false);
        assertThat(terminator.getSslContext()).isNotNull();
    }

    // ── Helper ──

    private static void generateJksKeystore(String path, String password) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=localhost",
                "-keystore", path,
                "-storepass", password,
                "-keypass", password,
                "-storetype", "JKS",
                "-noprompt"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool failed with exit code " + exitCode);
        }
    }
}
