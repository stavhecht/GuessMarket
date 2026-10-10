package api;

/**
 * Where each engine call lives on the server, relative to the web application's context
 * path. Written once here so a servlet's mapping and the client's request cannot drift
 * apart.
 *
 * <p>Each path is one {@link engine.service.Market} method, or one of the four things a
 * client does that the market interface does not name (log in, log out, ask for the whole
 * market at once, upload a file). The verbs follow the usual rule: a read is a GET with
 * query parameters, a command is a POST with a JSON body from this package.
 */
public final class Endpoints {

    private Endpoints() {
    }

    public static final String LOGIN = "/api/login";
    public static final String LOGOUT = "/api/logout";
    public static final String ME = "/api/me";
    /** POST puts money into the logged-in user's account, from a {@link LoadFundsRequest}. */
    public static final String FUNDS = "/api/me/funds";
    public static final String USERS = "/api/users";

    /** Everything the screens read, in one response and one consistent view. */
    public static final String MARKET = "/api/market";

    /** GET lists the events; POST creates one from a {@link CreateEventRequest}. */
    public static final String EVENTS = "/api/events";
    public static final String EVENT_STATUS = "/api/events/status";
    public static final String EVENT_BOOK = "/api/events/book";
    public static final String EVENT_HISTORY = "/api/events/history";
    public static final String QUOTE = "/api/events/quote";
    public static final String PARTICIPATE = "/api/events/participate";
    public static final String ORDER = "/api/events/order";
    /** POST withdraws the logged-in user's last waiting order, from a {@link CancelOrderRequest}. */
    public static final String CANCEL_ORDER = "/api/events/order/cancel";
    public static final String CLOSE = "/api/events/close";
    public static final String UPLOAD = "/api/events/upload";
    /** GET an event's chat after a given message; POST one line to it, from a {@link ChatRequest}. */
    public static final String CHAT = "/api/events/chat";

    /** The query parameters the GET endpoints read. */
    public static final String PARAM_ID = "id";
    public static final String PARAM_OPTION = "option";
    public static final String PARAM_SHARES = "shares";
    /** The chat's "only what is newer than this message id". */
    public static final String PARAM_AFTER = "after";

    /** The multipart part the events file arrives in. */
    public static final String UPLOAD_PART = "file";
}