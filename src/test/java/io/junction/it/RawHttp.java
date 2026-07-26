package io.junction.it;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A minimal HTTP/1.1 client over a raw socket.
 *
 * <p>{@code java.net.http.HttpClient} manages its own connection pool and hides
 * framing, which is exactly what several of these tests need to observe — whether
 * one TCP connection carried two requests, what the wire framing was, whether the
 * server closed. So the tests drive the socket directly.
 */
final class RawHttp implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final BufferedInputStream in;

    RawHttp(int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        this.socket.setSoTimeout(30_000);
        this.out = socket.getOutputStream();
        this.in = new BufferedInputStream(socket.getInputStream());
    }

    void write(String raw) throws IOException {
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    void writeBytes(byte[] b) throws IOException {
        out.write(b);
    }

    void flush() throws IOException {
        out.flush();
    }

    OutputStream outputStream() {
        return out;
    }

    /** Reads one complete response, honouring Content-Length or chunked framing. */
    Response readResponse() throws IOException {
        String statusLine = readLine();
        if (statusLine == null) {
            throw new IOException("connection closed before a response arrived");
        }
        List<String> headerLines = new ArrayList<>();
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            headerLines.add(line);
        }

        Headers headers = new Headers(headerLines);
        int status = Integer.parseInt(statusLine.split(" ")[1]);

        byte[] body;
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = readChunked();
        } else {
            String cl = headers.get("content-length");
            body = cl == null ? new byte[0] : readFully(Integer.parseInt(cl.trim()));
        }
        return new Response(status, headers, body);
    }

    private byte[] readChunked() throws IOException {
        var buf = new java.io.ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine();
            if (sizeLine == null) {
                throw new IOException("truncated chunked body");
            }
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
            if (size == 0) {
                while ((sizeLine = readLine()) != null && !sizeLine.isEmpty()) {
                    // consume trailers
                }
                return buf.toByteArray();
            }
            buf.write(readFully(size));
            readLine(); // CRLF after each chunk
        }
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(b, read, n - read);
            if (r < 0) {
                throw new IOException("truncated body: wanted " + n + ", got " + read);
            }
            read += r;
        }
        return b;
    }

    private String readLine() throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** True once the peer has closed its side. */
    boolean isClosedByPeer() throws IOException {
        return in.read() == -1;
    }

    InputStream inputStream() {
        return in;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    record Response(int status, Headers headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static final class Headers {
        private final List<String> lines;

        Headers(List<String> lines) {
            this.lines = lines;
        }

        String get(String name) {
            String want = name.toLowerCase(Locale.ROOT) + ":";
            for (String l : lines) {
                if (l.toLowerCase(Locale.ROOT).startsWith(want)) {
                    return l.substring(want.length()).trim();
                }
            }
            return null;
        }

        boolean contains(String name) {
            return get(name) != null;
        }

        @Override
        public String toString() {
            return String.join(" | ", lines);
        }
    }
}
