package client;

import api.CloseRequest;
import api.CreateEventRequest;
import api.Endpoints;
import api.ErrorBody;
import api.Json;
import api.LoadFundsRequest;
import api.LoginRequest;
import api.MarketSnapshot;
import api.OrderRequest;
import api.ParticipateRequest;
import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OrderBookStatusView;
import engine.dto.OrderResult;
import engine.dto.PurchaseQuote;
import engine.dto.PurchaseResult;
import engine.dto.SettlementResult;
import engine.dto.UserView;
import engine.exception.EngineException;
import engine.exception.EventNotFoundException;
import engine.model.CommissionMethod;
import engine.model.OrderSide;
import engine.service.Market;
import javafx.application.Platform;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The market on the server, worn as the {@link Market} the screens were written against.
 *
 * <p>Every method of the interface is one HTTP call to the endpoint of the same name
 * (see {@link Endpoints}), so the exercise-2 screens run over the network without a line
 * of them changing. Two things make that comfortable rather than merely true:
 *
 * <p><b>Reads come out of a snapshot, not off the wire.</b> A refresh reads the event
 * list, the status of every event and every user, and the screens refresh on every
 * command, every tab switch and every selection, all on the FX thread. Answering each of
 * those with a request would put the network on the thread that paints. So the whole
 * market is fetched at once ({@code GET /api/market}), kept as one immutable
 * {@link MarketSnapshot}, and the reads answer out of it in no time at all. The snapshot
 * is refetched by a poller every {@link #POLL_MILLIS}, which is how another client's
 * trade reaches this window, and by every command of this window's own before it
 * returns, so the refresh that follows a command sees what the command did, which is the
 * contract the screens were built on.
 *
 * <p><b>Refusals arrive as the engine's own exceptions.</b> The server answers a refusal
 * with the engine's sentence and a 4xx, and {@link #call} turns that back into an
 * {@link EngineException}, so the screens' one {@code catch} paints it in the status bar
 * exactly as it would have painted the local engine's. A server that cannot be reached
 * is reported the same way.
 *
 * <p>The session is the cookie Tomcat sets on login, kept by a {@link CookieJar} of one
 * host, so this object is one logged-in client and nothing about who it is has to be
 * repeated on each request.
 */
public class RemoteMarket implements Market {

    /**
     * How often the poller asks the server whether anything has changed. The exercise
     * allows up to two seconds and calls half a second reasonable; the fetch is one GET
     * answered from memory, and an unchanged answer costs the window nothing, so it is
     * the reasonable one.
     */
    public static final long POLL_MILLIS = 500;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType XML = MediaType.get("application/xml");
    private static final String LMSR = "LMSR";

    private final String base;
    private final OkHttpClient http;

    private volatile MarketSnapshot snapshot;
    private volatile String snapshotJson;
    private volatile String userName;
    private Thread poller;

    /**
     * @param base the web application's address, e.g. {@code http://localhost:8080/GuessMarket}
     */
    public RemoteMarket(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = new OkHttpClient.Builder()
                                    .cookieJar(new OneHostCookieJar())
                                    .connectTimeout(5, TimeUnit.SECONDS)
                                    .readTimeout(10, TimeUnit.SECONDS)
                                    .build();
    }

    public String base() {
        return base;
    }

    // --- the session ---

    /**
     * Becomes {@code name} on the server. On success the first snapshot is fetched, so the
     * window has a market to show the moment it opens.
     *
     * @throws EngineException with the server's sentence if the name is taken or blank, or
     *                         the server cannot be reached
     */
    public UserView login(String name) {
        UserView me = call(post(Endpoints.LOGIN, new LoginRequest(name)), UserView.class);
        userName = me.name();
        reload();
        return me;
    }

    /** Frees the name on the server and forgets the market. Never throws: it is a goodbye. */
    public void logout() {
        stopPolling();
        try {
            call(post(Endpoints.LOGOUT, null), Void.class);
        } catch (EngineException ignored) {
            // The name is freed when the session times out; there is nothing to tell the user.
        }
        userName = null;
        snapshot = null;
        snapshotJson = null;
    }

    /** Sends an events file up; every event in it joins the market with this user as its maker. */
    public List<EventView> uploadEventsFile(File file) {
        RequestBody part = RequestBody.create(file, XML);
        RequestBody body = new MultipartBody.Builder()
                                            .setType(MultipartBody.FORM)
                                            .addFormDataPart(Endpoints.UPLOAD_PART, file.getName(), part)
                                            .build();
        Request request = new Request.Builder().url(base + Endpoints.UPLOAD).post(body).build();
        List<EventView> created = call(request, listOf(EventView.class));
        reload();
        return created;
    }

    // --- the snapshot ---

    /**
     * Fetches the market again.
     *
     * @return whether anything in it changed since the last fetch, judged on the JSON
     *         itself rather than on the parsed records: several of them carry arrays
     *         (holdings, the price history), and an array compares by identity, so two
     *         equal snapshots would never be {@code equals}
     */
    public boolean reload() {
        String json = call(get(Endpoints.MARKET), String.class);
        if (json.equals(snapshotJson)) {
            return false;
        }
        snapshot = Json.GSON.fromJson(json, MarketSnapshot.class);
        snapshotJson = json;
        return true;
    }

    /**
     * Starts asking the server for the market every {@link #POLL_MILLIS}, and runs
     * {@code onChanged} on the FX thread each time something in it moved.
     *
     * @param onTrouble told, on the FX thread, when the server cannot be reached; the poll
     *                  carries on and tries again
     */
    public void startPolling(Runnable onChanged, Consumer<String> onTrouble) {
        stopPolling();
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (reload()) {
                        Platform.runLater(onChanged);
                    }
                } catch (EngineException e) {
                    Platform.runLater(() -> onTrouble.accept(e.getMessage()));
                }
            }
        }, "guessmarket-poll");
        thread.setDaemon(true);
        thread.start();
        poller = thread;
    }

    public void stopPolling() {
        if (poller != null) {
            poller.interrupt();
            poller = null;
        }
    }

    private MarketSnapshot current() {
        MarketSnapshot now = snapshot;
        if (now == null) {
            throw new EngineException("Not logged in.");
        }
        return now;
    }

    // --- Market: the reads, out of the snapshot ---

    @Override
    public boolean isFileLoaded() {
        return snapshot != null;
    }

    @Override
    public String getCurrentUserName() {
        return userName;
    }

    /** A client is who it logged in as; there is nobody else to be. */
    @Override
    public UserView selectUser(String name) {
        throw new EngineException("You are logged in as '" + userName + "' and act as them.");
    }

    @Override
    public List<UserView> getUsers() {
        return current().users();
    }

    @Override
    public List<EventView> getEvents() {
        return current().events();
    }

    @Override
    public EventStatusView getEventStatus(int eventId) {
        EventStatusView status = current().lmsrStatuses().get(eventId);
        if (status == null) {
            throw new EventNotFoundException("There is no LMSR event with id " + eventId + ".");
        }
        return status;
    }

    @Override
    public OrderBookStatusView getOrderBookStatus(int eventId) {
        OrderBookStatusView status = current().bookStatuses().get(eventId);
        if (status == null) {
            throw new EventNotFoundException("There is no order-book event with id " + eventId + ".");
        }
        return status;
    }

    @Override
    public List<double[]> getPriceHistory(int eventId) {
        List<double[]> history = current().priceHistories().get(eventId);
        if (history == null) {
            throw new EventNotFoundException("There is no LMSR event with id " + eventId + ".");
        }
        return history;
    }

    /**
     * The one read that goes to the wire: a quote depends on a share count typed a
     * moment ago, which no snapshot can hold. It is asked on every keystroke, and the
     * callers already swallow a refusal, so a server out of reach costs a missing figure
     * and nothing more.
     */
    @Override
    public PurchaseQuote quoteParticipation(int eventId, int optionIndex, long shares) {
        HttpUrl url = HttpUrl.get(base + Endpoints.QUOTE).newBuilder()
                .addQueryParameter(Endpoints.PARAM_ID, String.valueOf(eventId))
                .addQueryParameter(Endpoints.PARAM_OPTION, String.valueOf(optionIndex))
                .addQueryParameter(Endpoints.PARAM_SHARES, String.valueOf(shares))
                .build();
        return call(new Request.Builder().url(url).get().build(), PurchaseQuote.class);
    }

    /** The logged-in user's own account, out of the snapshot; {@code null} before the first fetch. */
    public UserView account() {
        MarketSnapshot now = snapshot;
        if (now == null || userName == null) {
            return null;
        }
        for (UserView user : now.users()) {
            if (user.name().equals(userName)) {
                return user;
            }
        }
        return null;
    }

    // --- Market: the commands, each followed by a reload ---

    @Override
    public UserView loadFunds(double amount) {
        UserView account = call(post(Endpoints.FUNDS, new LoadFundsRequest(amount)), UserView.class);
        reload();
        return account;
    }

    @Override
    public PurchaseResult participate(int eventId, int optionIndex, long shares) {
        PurchaseResult result = call(post(Endpoints.PARTICIPATE,
                new ParticipateRequest(eventId, optionIndex, shares)), PurchaseResult.class);
        reload();
        return result;
    }

    @Override
    public OrderResult placeOrder(int eventId, int optionIndex, OrderSide side, double price, long quantity) {
        OrderResult result = call(post(Endpoints.ORDER,
                new OrderRequest(eventId, optionIndex, side.name(), price, quantity)), OrderResult.class);
        reload();
        return result;
    }

    @Override
    public SettlementResult closeEvent(int eventId, int winningOptionIndex) {
        SettlementResult result = call(post(Endpoints.CLOSE,
                new CloseRequest(eventId, winningOptionIndex)), SettlementResult.class);
        reload();
        return result;
    }

    @Override
    public EventView createLmsrEvent(String name, String description, int commissionPercent,
                                     CommissionMethod commissionMethod, List<String> optionNames, double b) {
        EventView made = call(post(Endpoints.EVENTS, new CreateEventRequest(name, description, commissionPercent,
                commissionMethod.name(), optionNames, CreateEventRequest.LMSR, b, null, null, null)),
                EventView.class);
        reload();
        return made;
    }

    @Override
    public EventView createOrderBookEvent(String name, String description, int commissionPercent,
                                          CommissionMethod commissionMethod, List<String> optionNames,
                                          int initialInvestment, int d, boolean allowMint) {
        EventView made = call(post(Endpoints.EVENTS, new CreateEventRequest(name, description, commissionPercent,
                commissionMethod.name(), optionNames, CreateEventRequest.ORDER_BOOK, null,
                initialInvestment, d, allowMint)), EventView.class);
        reload();
        return made;
    }

    // --- the wire ---

    private Request get(String path) {
        return new Request.Builder().url(base + path).get().build();
    }

    private Request post(String path, Object body) {
        RequestBody content = RequestBody.create(body == null ? "" : Json.GSON.toJson(body), JSON);
        return new Request.Builder().url(base + path).post(content).build();
    }

    private static Type listOf(Class<?> element) {
        return com.google.gson.reflect.TypeToken.getParameterized(List.class, element).getType();
    }

    /**
     * Makes one request and reads the answer as {@code type}: the raw text for
     * {@code String}, nothing for {@code Void}, a parsed record otherwise.
     *
     * <p>Everything that can go wrong comes out as an {@link EngineException} carrying a
     * sentence for the status bar: the server's own for a refusal it explained, and this
     * class's for a server that is unreachable or answered with something other than JSON
     * (Tomcat's own page for a path it does not know, say).
     */
    private <T> T call(Request request, Type type) {
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new EngineException(explain(response.code(), text));
            }
            if (type == Void.class) {
                return null;
            }
            if (type == String.class) {
                @SuppressWarnings("unchecked")
                T raw = (T) text;
                return raw;
            }
            return Json.GSON.fromJson(text, type);
        } catch (IOException e) {
            throw new EngineException("Cannot reach the server at " + base + ": " + e.getMessage());
        }
    }

    private static String explain(int code, String text) {
        try {
            ErrorBody error = Json.GSON.fromJson(text, ErrorBody.class);
            if (error != null && error.error() != null) {
                return code == 401 ? "Your session has ended; log in again." : error.error();
            }
        } catch (RuntimeException notJson) {
            // fall through: the body is somebody else's page
        }
        return "The server replied " + code + ".";
    }

    /**
     * Keeps the cookies one host sets, which for this app is the session cookie Tomcat
     * hands out on login. Nothing is persisted: closing the window is logging out.
     */
    private static final class OneHostCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> byHost = new ConcurrentHashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            byHost.compute(url.host(), (host, kept) -> {
                List<Cookie> next = new ArrayList<>(kept == null ? List.of() : kept);
                for (Cookie cookie : cookies) {
                    next.removeIf(old -> old.name().equals(cookie.name()));
                    next.add(cookie);
                }
                return next;
            });
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> kept = byHost.get(url.host());
            if (kept == null) {
                return List.of();
            }
            long now = System.currentTimeMillis();
            List<Cookie> live = new ArrayList<>();
            for (Cookie cookie : kept) {
                if (cookie.expiresAt() > now) {
                    live.add(cookie);
                }
            }
            return live;
        }
    }
}