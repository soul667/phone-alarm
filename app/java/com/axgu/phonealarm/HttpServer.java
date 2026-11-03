package com.axgu.phonealarm;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简内嵌 HTTP server：请求行/头/体解析 + 分发，逐请求关闭连接，零第三方依赖。 */
public class HttpServer {

    public static class Request {
        public String method;
        public String path;
        public Map<String, String> query = new HashMap<>();
        public Map<String, String> headers = new HashMap<>();
        public String body = "";

        public String param(String k) { return query.get(k); }
    }

    public static class Response {
        public int status = 200;
        public String contentType = "application/json; charset=utf-8";
        public byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        public static Response json(int code, Object any) {
            Response r = new Response();
            r.status = code;
            r.body = String.valueOf(any).getBytes(StandardCharsets.UTF_8);
            return r;
        }
    }

    public interface Handler {
        Response handle(Request req) throws Exception;
    }

    private final int port;
    private final Handler handler;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    public volatile boolean running = false;
    public volatile long startedAt = System.currentTimeMillis();
    public volatile int requestsServed = 0;

    public HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(this::loop, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) { }
    }

    private void loop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> serve(s));
            } catch (Exception e) {
                if (running) sleep(200);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            Request req = new Request();

            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            req.method = parts[0];
            String target = parts[1];
            int q = target.indexOf('?');
            req.path = q < 0 ? target : target.substring(0, q);
            if (q >= 0) {
                for (String kv : target.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        req.query.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                                URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                    }
                }
            }

            int contentLen = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) {
                    String k = h.substring(0, c).trim().toLowerCase();
                    String v = h.substring(c + 1).trim();
                    req.headers.put(k, v);
                    if ("content-length".equals(k)) contentLen = Integer.parseInt(v);
                }
            }

            if (contentLen > 0) {
                if (contentLen > 256 * 1024) contentLen = 256 * 1024;
                byte[] buf = new byte[contentLen];
                int off = 0, n;
                while (off < contentLen && (n = in.read(buf, off, contentLen - off)) >= 0) off += n;
                req.body = new String(buf, StandardCharsets.UTF_8);
            }

            Response res;
            try {
                res = handler.handle(req);
            } catch (Exception e) {
                res = Response.json(500, "{\"error\":" + jsonString(String.valueOf(e)) + "}");
            }
            requestsServed++;

            byte[] body = res.body;
            OutputStream out = sock.getOutputStream();
            String head = "HTTP/1.1 " + res.status + " " + reason(res.status) + "\r\n"
                    + "Content-Type: " + res.contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static String readLine(BufferedInputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new Exception("header line too long");
        }
        return sb.toString();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Error";
        }
    }

    /** 最小 JSON 字符串转义，用于把异常文本塞进响应。 */
    public static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }
}
