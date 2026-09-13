package server;

import api.CreateEventRequest;
import api.Endpoints;
import engine.model.CommissionMethod;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@code /api/events}: GET lists every event, POST creates one from the form's fields,
 * run by the user who posted it.
 */
@WebServlet(name = "events", urlPatterns = Endpoints.EVENTS)
public class EventsServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getEvents());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            CreateEventRequest event = body(req, CreateEventRequest.class);
            CommissionMethod commission = named(CommissionMethod.class, event.commissionMethod(), "commission method");
            if (CreateEventRequest.LMSR.equals(event.tradingMethod())) {
                return engine.createLmsrEvent(user, event.name(), event.description(), event.commissionPercent(),
                        commission, event.optionNames(), unbox(event.b(), "b"));
            }
            if (CreateEventRequest.ORDER_BOOK.equals(event.tradingMethod())) {
                return engine.createOrderBookEvent(user, event.name(), event.description(), event.commissionPercent(),
                        commission, event.optionNames(), unbox(event.initialInvestment(), "initial investment"),
                        unbox(event.d(), "d"), unbox(event.allowMint(), "allow-mint"));
            }
            throw new IllegalArgumentException("The trading method must be " + CreateEventRequest.LMSR
                    + " or " + CreateEventRequest.ORDER_BOOK + ".");
        });
    }

    /** An enum by its name, with the caller's mistake read as 400 rather than a stack trace. */
    static <E extends Enum<E>> E named(Class<E> type, String name, String what) {
        try {
            return Enum.valueOf(type, name == null ? "" : name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a " + what + ".");
        }
    }

    private static <T> T unbox(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("The event needs a value for " + what + ".");
        }
        return value;
    }
}
