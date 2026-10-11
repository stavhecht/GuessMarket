package webclient;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web client as one jar: {@code java -jar WebClient.jar [server]} serves the built React
 * app at {@code http://localhost:3000} and forwards every {@code /api} request to the Guess
 * Market server, unchanged. It is what the Vite dev server does in {@code vite.config.ts},
 * with nothing but the JDK, so the machine running it needs Java and no Node.js.
 *
 * <p>Why a forwarder at all, and why the session travels as a header, is the same story as
 * there. The page and the market are on different origins, and Tomcat sends no CORS headers,
 * so the browser only ever talks to this server, which talks to Tomcat for it. Tomcat's
 * {@code JSESSIONID} cookie is lifted out of each answer into {@code X-GM-Session}, kept by
 * the page in memory, and turned back into a cookie on the way in, so each browser tab is one
 * client, as each JavaFX window is.
 *
 * <p>Every answer from {@code /api} also carries {@code X-GM-Server}, the market's address, so
 * the page can name the server it is connected to without being built for one.
 */
public final class WebClientServer {

    /** Where the market is, unless told otherwise: the JavaFX client's own default. */
    static final String DEFAULT_SERVER = "http://localhost:8080/GuessMarket";
    static final int DEFAULT_PORT = 3000;

    /** The built React app, packed into the jar under this folder by build-jar.sh. */
    private static final String STATIC_ROOT = "/webclient/static";

    private static final String SESSION_HEADER = "X-GM-Session";
    private static final String SERVER_HEADER = "X-GM-Server";
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern JSESSIONID = Pattern.compile("^JSESSIONID=([^;]+)");

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "woff2", "font/woff2");

    private final String server;
    private final HttpClient http = HttpClient.newBuilder()
                                              .connectTimeout(Duration.ofSeconds(5))
                                              .followRedirects(HttpClient.Redirect.NEVER)
                                              .build();

    private WebClientServer(String server) {
        this.server = server;
    }

    /**
     * @param args the market's address, optionally; the port is {@code -Dguessmarket.port}
     *             or {@code GM_PORT}, and {@code -Dguessmarket.open=false} (or {@code GM_OPEN=0})
     *             keeps the browser from being opened
     */
    public static void main(String[] args) throws IOException {
        String server = (args.length > 0 ? args[0]
                : System.getProperty("guessmarket.server", env("GM_SERVER", DEFAULT_SERVER))).replaceAll("/+$", "");
        int port = Integer.parseInt(System.getProperty("guessmarket.port", env("GM_PORT", String.valueOf(DEFAULT_PORT))));
        boolean open = !"false".equals(System.getProperty("guessmarket.open")) && !"0".equals(System.getenv("GM_OPEN"));

        if (WebClientServer.class.getResource(STATIC_ROOT + "/index.html") == null) {
            System.err.println("This jar carries no web page: build it with WebClient/build-jar.sh, which packs the "
                    + "React app into it.");
            System.exit(1);
        }

        HttpServer httpServer;
        try {
            // Loopback only, as the dev server binds: the page is for this machine's browser.
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (BindException taken) {
            System.err.println("Port " + port + " is already in use. Close whatever is using it (another web client?), "
                    + "or start this one on another port: java -Dguessmarket.port=3001 -jar WebClient.jar");
            System.exit(1);
            return;
        }
        WebClientServer client = new WebClientServer(server);
        // HttpExchange is AutoCloseable only from JDK 18, and this is built for 17, so each
        // exchange is closed here, whatever the handler did.
        httpServer.createContext("/api/", exchange -> {
            try {
                client.forward(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.createContext("/", exchange -> {
            try {
                client.serveFile(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();

        String url = "http://localhost:" + port + "/";
        System.out.println();
        System.out.println("Guess Market web client: " + url + "   (server: " + server + ")");
        System.out.println("Leave this window open while using the client; Ctrl+C stops it.");
        System.out.println();
        if (!client.serverAnswers()) {
            System.out.println("WARNING: the Guess Market server does not answer at " + server);
            System.out.println("Start Tomcat with GuessMarket deployed. The client connects once it is up.");
            System.out.println();
        }
        if (open) {
            openBrowser(url);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception | Error ignored) {
            // A machine with no desktop browser to open: the address printed above is enough.
        }
    }

    /** The market answers 401 to anyone not logged in; anything else means it is not up. */
    private boolean serverAnswers() {
        try {
            HttpResponse<Void> answer = http.send(HttpRequest.newBuilder(URI.create(server + "/api/market"))
                                                             .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return answer.statusCode() == 401 || answer.statusCode() == 200;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            return false;
        }
    }

    // --- /api: forwarded to the market ---

    private void forward(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        URI incoming = exchange.getRequestURI();
        String target = server + incoming.getRawPath() + (incoming.getRawQuery() == null ? "" : "?" + incoming.getRawQuery());

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(15));
        String session = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (session != null && SESSION_ID.matcher(session).matches()) {
            request.header("Cookie", "JSESSIONID=" + session);    // nothing of the browser's own reaches Tomcat
        }
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type != null) {
            request.header("Content-Type", type);
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        request.method(method, "GET".equals(method) || "HEAD".equals(method)
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<byte[]> answer;
        try {
            answer = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException | HttpTimeoutException e) {
            unreachable(exchange, e.getClass().getSimpleName());
            return;
        } catch (IOException e) {
            unreachable(exchange, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unreachable(exchange, "interrupted");
            return;
        }

        Headers out = exchange.getResponseHeaders();
        answer.headers().firstValue("Content-Type").ifPresent(value -> out.set("Content-Type", value));
        for (String cookie : answer.headers().allValues("Set-Cookie")) {
            Matcher id = JSESSIONID.matcher(cookie);
            if (id.find()) {
                out.set(SESSION_HEADER, id.group(1));
            }
        }
        out.set(SERVER_HEADER, server);
        out.set("Cache-Control", "no-store");
        send(exchange, answer.statusCode(), answer.body());
    }

    /** Tomcat not answering, said in the shape the page reads every refusal in: an ErrorBody. */
    private void unreachable(HttpExchange exchange, String reason) throws IOException {
        String message = "Cannot reach the server at " + server + " (" + reason + "). Is Tomcat running with GuessMarket deployed?";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set(SERVER_HEADER, server);
        send(exchange, 502, ("{\"error\":" + quote(message) + ",\"type\":\"Unreachable\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    // --- everything else: the page itself ---

    private void serveFile(HttpExchange exchange) throws IOException {
        if (!List.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.contains("..")) {
            send(exchange, 404, new byte[0]);
            return;
        }
        // One page: the root and anything that is not a file of the build is index.html.
        String file = path.equals("/") ? "/index.html" : path;
        InputStream resource = WebClientServer.class.getResourceAsStream(STATIC_ROOT + file);
        if (resource == null) {
            file = "/index.html";
            resource = WebClientServer.class.getResourceAsStream(STATIC_ROOT + file);
        }
        byte[] content;
        try (InputStream in = resource) {
            content = in.readAllBytes();
        }
        String extension = file.substring(file.lastIndexOf('.') + 1);
        exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        // The build names its assets by their content, so they never change under one name;
        // the page that names them must always be fresh.
        exchange.getResponseHeaders().set("Cache-Control",
                file.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        send(exchange, 200, "HEAD".equals(exchange.getRequestMethod()) ? null : content);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null || body.length == 0 || status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
