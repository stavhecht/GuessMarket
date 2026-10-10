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
 *
 * <p>No rule about events lives here. This servlet checks only that each field the chosen
 * trading method needs was sent; whether the values make a legal event (the commission's
 * range, the options' names, what the creator can afford) is {@code MarketEngine}'s to
 * decide, the same rules it holds a form and a file to.
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
            CommissionMethod commission = named(CommissionMethod.class, event.commissionMethod(), "commissionMethod");
            int commissionPercent = required(event.commissionPercent(), "commissionPercent");
            if (CreateEventRequest.LMSR.equals(event.tradingMethod())) {
                return engine.createLmsrEvent(user, event.name(), event.description(), commissionPercent,
                        commission, event.optionNames(), required(event.b(), "b"));
            }
            if (CreateEventRequest.ORDER_BOOK.equals(event.tradingMethod())) {
                return engine.createOrderBookEvent(user, event.name(), event.description(), commissionPercent,
                        commission, event.optionNames(), required(event.initialInvestment(), "initialInvestment"),
                        required(event.d(), "d"), required(event.allowMint(), "allowMint"));
            }
            throw new IllegalArgumentException("The trading method must be " + CreateEventRequest.LMSR
                    + " or " + CreateEventRequest.ORDER_BOOK + ".");
        });
    }
}