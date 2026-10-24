package my.rafhan.app.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The HTTP plumbing the phone remote's server and API share: reading a request line, parsing a
 * query string and writing the reply. Every reply carries the security headers (no sniffing, no
 * referrer, and a strict CSP).
 */
final class Http {
    /** Locked down by default; scripts run only when the page's own tag carries the nonce. */
    private static final String CSP = "default-src 'none'; img-src 'self' data: blob:; font-src 'self';"
            + " connect-src 'self'; manifest-src 'self'; style-src 'self' 'unsafe-inline';"
            + " base-uri 'none'; form-action 'self'; frame-ancestors 'none'; script-src ";
    static final String CSP_NO_SCRIPTS = CSP + "'none'";

    private Http() {}

    static String cspWithNonce(String nonce) {
        return CSP + "'nonce-" + nonce + "'";
    }

    static void send(OutputStream out, int code, String type, byte[] body, String cache) throws IOException {
        send(out, code, type, body, cache, null, CSP_NO_SCRIPTS);
    }

    static void send(OutputStream out, int code, String type, byte[] body, String cache, String setCookie) throws IOException {
        send(out, code, type, body, cache, setCookie, CSP_NO_SCRIPTS);
    }

    static void send(OutputStream out, int code, String type, byte[] body, String cache, String setCookie,
                     String csp) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Cache-Control: ").append(cache).append("\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Content-Security-Policy: ").append(csp).append("\r\n");
        if (setCookie != null) head.append("Set-Cookie: ").append(setCookie).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    static void json(OutputStream out, Object[] codeAndBody) throws IOException {
        json(out, (Integer) codeAndBody[0], (JSONObject) codeAndBody[1]);
    }

    static void json(OutputStream out, int code, JSONObject body) throws IOException {
        json(out, code, body, null);
    }

    static void json(OutputStream out, int code, JSONObject body, String setCookie) throws IOException {
        send(out, code, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), "no-store", setCookie);
    }

    static boolean post(String method, OutputStream out) throws IOException {
        if ("POST".equals(method)) return true;
        json(out, 405, err("Use POST."));
        return false;
    }

    static JSONObject ok() {
        return new JSONObjectBuilder().put("ok", true).build();
    }

    static JSONObject err(String message) {
        return new JSONObjectBuilder().put("ok", false).put("error", message).build();
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return map;
    }

    static void closeQuietly(Socket c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 410: return "Gone";
            case 411: return "Length Required";
            case 415: return "Unsupported Media Type";
            case 500: return "Internal Server Error";
            case 507: return "Insufficient Storage";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** JSONObject.put throws a checked exception for non-finite numbers only; hide it. */
    static final class JSONObjectBuilder {
        private final JSONObject o = new JSONObject();

        JSONObjectBuilder put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (JSONException ignored) {
            }
            return this;
        }

        JSONObject build() {
            return o;
        }
    }
}
