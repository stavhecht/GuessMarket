package server;

import api.ErrorBody;
import api.Json;
import engine.exception.EngineException;
import engine.exception.EventClosedException;
import engine.exception.EventNotFoundException;
import engine.exception.NotMarketMakerException;
import engine.exception.UserNotFoundException;
import engine.service.MarketEngine;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.Lock;

/**
 * What every endpoint has in common: the engine, the lock around it, the logged-in user,
 * JSON in and out, and one place where an engine refusal becomes an HTTP status.
 *
 * <p>The idea is {@code ConsoleApp.dispatch} catching {@code EngineException} in exactly
 * one place: a servlet says what to call, and {@link #read} / {@link #write} do the rest.
 * Read takes the shared lock, write the exclusive one; nothing in a servlet touches the
 * engine outside one of the two.
 *
 * <p>The status for a refusal follows the exception the engine threw, which is the only
 * thing that tells them apart (they all carry a message and nothing else): a thing that
 * does not exist is 404, a thing only the market maker may do is 403, a thing already
 * done is 409, and anything else the user typed wrong is 400. The body is always an
 * {@link ErrorBody} with the engine's own sentence, which the client shows unchanged.
 */
abstract class ApiServlet extends HttpServlet {

    private static final String JSON = "application/json";

    /** One engine call, made while the right lock is held. */
    @FunctionalInterface
    interface Call<T> {
        T on(MarketEngine engine, String user) throws IOException;
    }

    /** Runs {@code call} under the read lock and answers with what it returns. */
    protected final void read(HttpServletRequest req, HttpServletResponse resp, Call<?> call)
            throws IOException {
        answer(req, resp, MarketContext.lock(getServletContext()).readLock(), call);
    }

    /** Runs {@code call} under the write lock and answers with what it returns. */
    protected final void write(HttpServletRequest req, HttpServletResponse resp, Call<?> call)
            throws IOException {
        answer(req, resp, MarketContext.lock(getServletContext()).writeLock(), call);
    }

    private void answer(HttpServletRequest req, HttpServletResponse resp, Lock lock, Call<?> call)
            throws IOException {
        String user = requireUser(req);
        if (user == null) {
            fail(resp, HttpServletResponse.SC_UNAUTHORIZED, "Log in first.", "NotLoggedIn");
            return;
        }
        Object result;
        lock.lock();
        try {
            result = call.on(MarketContext.engine(getServletContext()), user);
        } catch (EngineException e) {
            fail(resp, statusFor(e), e.getMessage(), e.getClass().getSimpleName());
            return;
        } catch (IllegalArgumentException e) {
            // A parameter or body this class could not read: the caller's, not the server's.
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage(), e.getClass().getSimpleName());
            return;
        } catch (RuntimeException e) {
            fail(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.toString() : e.getMessage(), e.getClass().getSimpleName());
            return;
        } finally {
            lock.unlock();
        }
        if (result == null) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            ok(resp, result);
        }
    }

    /** The logged-in user's name, or {@code null} if this session has none. */
    protected static String requireUser(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session == null ? null : (String) session.getAttribute(MarketContext.USER);
    }

    static int statusFor(EngineException e) {
        if (e instanceof EventNotFoundException || e instanceof UserNotFoundException) {
            return HttpServletResponse.SC_NOT_FOUND;
        }
        if (e instanceof NotMarketMakerException) {
            return HttpServletResponse.SC_FORBIDDEN;
        }
        if (e instanceof EventClosedException) {
            return HttpServletResponse.SC_CONFLICT;
        }
        return HttpServletResponse.SC_BAD_REQUEST;
    }

    /** The request body as one of the {@code api} records. Event names may be non-ASCII. */
    protected static <T> T body(HttpServletRequest req, Class<T> type) throws IOException {
        req.setCharacterEncoding(StandardCharsets.UTF_8.name());
        T parsed = Json.GSON.fromJson(req.getReader(), type);
        if (parsed == null) {
            throw new IllegalArgumentException("The request has no body.");
        }
        return parsed;
    }

    /**
     * A whole-number query parameter. A missing or unreadable one is the caller's mistake,
     * and reads as 400 rather than as a stack trace.
     */
    protected static int intParam(HttpServletRequest req, String name) {
        String raw = req.getParameter(name);
        if (raw == null) {
            throw new IllegalArgumentException("Missing the '" + name + "' parameter.");
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a whole number for '" + name + "'.");
        }
    }

    protected static long longParam(HttpServletRequest req, String name) {
        String raw = req.getParameter(name);
        if (raw == null) {
            throw new IllegalArgumentException("Missing the '" + name + "' parameter.");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a whole number for '" + name + "'.");
        }
    }

    protected static void ok(HttpServletResponse resp, Object body) throws IOException {
        send(resp, HttpServletResponse.SC_OK, body);
    }

    protected static void fail(HttpServletResponse resp, int status, String message, String type)
            throws IOException {
        send(resp, status, new ErrorBody(message, type));
    }

    private static void send(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType(JSON);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Json.GSON.toJson(body, resp.getWriter());
    }
}