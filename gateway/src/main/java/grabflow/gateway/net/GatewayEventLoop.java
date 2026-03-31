package grabflow.gateway.net;

import grabflow.common.GatewayRequest;
import grabflow.common.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Non-blocking NIO event loop for the API Gateway.
 *
 * <p>Manages the full lifecycle of client connections: accept, read, process, write.
 * Uses a single Selector thread (on a virtual thread) with the reactor pattern:
 * one thread multiplexes thousands of connections via non-blocking I/O.</p>
 *
 * <h3>Event Loop Flow</h3>
 * <pre>
 *   while (running):
 *     selector.select()          // block until events arrive
 *     for each ready key:
 *       if OP_ACCEPT → accept new connection, register for OP_READ
 *       if OP_READ   → read data, parse HTTP, invoke handler, queue response
 *       if OP_WRITE  → flush queued response bytes to client
 * </pre>
 *
 * <p>This is the same pattern used by Nginx, Redis, and Node.js for high-concurrency
 * networking. It avoids the overhead of thread-per-connection by multiplexing all I/O
 * through a single kernel call ({@code epoll} on Linux, {@code kqueue} on macOS).</p>
 */
public class GatewayEventLoop implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(GatewayEventLoop.class);
    private static final int READ_BUFFER_SIZE = 16 * 1024; // 16 KB per connection

    private final DualStackListener listener;
    private final Function<GatewayRequest, GatewayResponse> requestHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Selector selector;
    private Thread eventLoopThread;

    private final ConcurrentHashMap<SelectionKey, ConnectionState> connections = new ConcurrentHashMap<>();

    /**
     * @param listener       dual-stack IPv4/IPv6 listener
     * @param requestHandler function that processes requests and returns responses
     */
    public GatewayEventLoop(DualStackListener listener,
                            Function<GatewayRequest, GatewayResponse> requestHandler) {
        this.listener = listener;
        this.requestHandler = requestHandler;
    }

    /**
     * Starts the event loop on a virtual thread.
     */
    public void start() throws IOException {
        selector = Selector.open();
        listener.bind(selector);
        running.set(true);

        eventLoopThread = Thread.ofVirtual()
                .name("gateway-event-loop")
                .start(this::eventLoop);
        log.info("Gateway event loop started on port {}", listener.getPort());
    }

    /**
     * The core event loop. Runs until {@link #stop()} is called.
     */
    private void eventLoop() {
        while (running.get()) {
            try {
                int readyCount = selector.select(1000); // 1s timeout for graceful shutdown check
                if (readyCount == 0) continue;

                var selectedKeys = selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (CancelledKeyException e) {
                        closeConnection(key);
                    } catch (IOException e) {
                        log.debug("I/O error on connection: {}", e.getMessage());
                        closeConnection(key);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Event loop select() failed", e);
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return;

        clientChannel.configureBlocking(false);
        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);

        var state = new ConnectionState(
                ByteBuffer.allocate(READ_BUFFER_SIZE),
                ByteBuffer.allocate(READ_BUFFER_SIZE)
        );
        connections.put(clientKey, state);

        InetSocketAddress remoteAddr = (InetSocketAddress) clientChannel.getRemoteAddress();
        log.debug("Accepted connection from {} ({})",
                remoteAddr.getAddress().getHostAddress(),
                DualStackListener.protocolFamily(remoteAddr.getAddress()));
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState state = connections.get(key);
        if (state == null) {
            closeConnection(key);
            return;
        }

        ByteBuffer readBuf = state.readBuffer();
        int bytesRead = channel.read(readBuf);
        if (bytesRead == -1) {
            closeConnection(key);
            return;
        }

        readBuf.flip();

        // Try to parse an HTTP request from the accumulated data
        GatewayRequest request = parseHttpRequest(readBuf, channel);
        if (request == null) {
            // Incomplete request -- wait for more data
            readBuf.compact();
            return;
        }

        readBuf.clear();

        // Process request through the handler pipeline
        GatewayResponse response = requestHandler.apply(request);

        // Serialize response and queue for writing
        byte[] responseBytes = serializeHttpResponse(response);
        ByteBuffer writeBuf = state.writeBuffer();
        writeBuf.clear();
        if (responseBytes.length <= writeBuf.capacity()) {
            writeBuf.put(responseBytes);
            writeBuf.flip();
        } else {
            // Response too large for buffer -- use a temporary larger buffer
            state.setOverflowWrite(ByteBuffer.wrap(responseBytes));
        }

        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState state = connections.get(key);
        if (state == null) {
            closeConnection(key);
            return;
        }

        ByteBuffer writeBuf = state.overflowWrite() != null ? state.overflowWrite() : state.writeBuffer();
        channel.write(writeBuf);

        if (!writeBuf.hasRemaining()) {
            state.clearOverflowWrite();
            // Switch back to reading for keep-alive connections
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    /**
     * Parses an HTTP/1.1 request from the buffer.
     * Returns null if the request is incomplete (waiting for more data).
     */
    private GatewayRequest parseHttpRequest(ByteBuffer buf, SocketChannel channel) throws IOException {
        // Look for end of headers: \r\n\r\n
        int headerEnd = findHeaderEnd(buf);
        if (headerEnd == -1) return null;

        byte[] headerBytes = new byte[headerEnd];
        buf.get(headerBytes);
        // Skip the \r\n\r\n
        buf.position(buf.position() + 4);

        String headerStr = new String(headerBytes, StandardCharsets.US_ASCII);
        String[] lines = headerStr.split("\r\n");
        if (lines.length == 0) return null;

        // Parse request line: "GET /path HTTP/1.1"
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length < 3) return null;

        String method = requestLine[0];
        String path = requestLine[1];
        String httpVersion = requestLine[2];

        // Parse headers
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colonIdx = lines[i].indexOf(':');
            if (colonIdx > 0) {
                String name = lines[i].substring(0, colonIdx).trim().toLowerCase();
                String value = lines[i].substring(colonIdx + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }

        // Read body if Content-Length present
        byte[] body = new byte[0];
        var contentLengthValues = headers.get("content-length");
        if (contentLengthValues != null && !contentLengthValues.isEmpty()) {
            int contentLength = Integer.parseInt(contentLengthValues.getFirst());
            if (buf.remaining() >= contentLength) {
                body = new byte[contentLength];
                buf.get(body);
            } else {
                // Body not fully received yet
                return null;
            }
        }

        InetSocketAddress remote = (InetSocketAddress) channel.getRemoteAddress();

        return new GatewayRequest(method, path, httpVersion, headers, body,
                remote.getAddress(), remote.getPort());
    }

    private int findHeaderEnd(ByteBuffer buf) {
        for (int i = buf.position(); i < buf.limit() - 3; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n'
                    && buf.get(i + 2) == '\r' && buf.get(i + 3) == '\n') {
                return i - buf.position();
            }
        }
        return -1;
    }

    /**
     * Serializes an HTTP/1.1 response to bytes.
     */
    private byte[] serializeHttpResponse(GatewayResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(response.statusCode()).append(" ")
                .append(statusText(response.statusCode())).append("\r\n");

        for (var entry : response.headers().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n");

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] body = response.body();

        byte[] result = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(body, 0, result, headerBytes.length, body.length);
        return result;
    }

    private String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    private void closeConnection(SelectionKey key) {
        connections.remove(key);
        try {
            key.channel().close();
        } catch (IOException ignored) {}
        key.cancel();
    }

    /**
     * Returns the number of active connections.
     */
    public int activeConnections() {
        return connections.size();
    }

    /**
     * Gracefully stops the event loop.
     */
    public void stop() {
        running.set(false);
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public void close() throws IOException {
        stop();
        if (eventLoopThread != null) {
            try {
                eventLoopThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        listener.close();
        if (selector != null) {
            selector.close();
        }
    }

    /**
     * Tracks per-connection state: read/write buffers.
     */
    static final class ConnectionState {
        private final ByteBuffer readBuffer;
        private final ByteBuffer writeBuffer;
        private ByteBuffer overflowWrite;

        ConnectionState(ByteBuffer readBuffer, ByteBuffer writeBuffer) {
            this.readBuffer = readBuffer;
            this.writeBuffer = writeBuffer;
        }

        ByteBuffer readBuffer() { return readBuffer; }
        ByteBuffer writeBuffer() { return writeBuffer; }
        ByteBuffer overflowWrite() { return overflowWrite; }
        void setOverflowWrite(ByteBuffer buf) { this.overflowWrite = buf; }
        void clearOverflowWrite() { this.overflowWrite = null; }
    }
}
