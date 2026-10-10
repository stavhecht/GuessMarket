package server;

import api.Endpoints;
import api.MarketSnapshot;
import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OrderBookStatusView;
import engine.service.MarketEngine;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/market}: everything a refresh reads, in one response under one read lock.
 * See {@link MarketSnapshot} for why it exists beside the per-call endpoints.
 */
@WebServlet(name = "market", urlPatterns = Endpoints.MARKET)
public class SnapshotServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> snapshot(engine));
    }

    static MarketSnapshot snapshot(MarketEngine engine) {
        List<EventView> events = engine.getEvents();
        Map<Integer, EventStatusView> lmsr = new LinkedHashMap<>();
        Map<Integer, OrderBookStatusView> books = new LinkedHashMap<>();
        Map<Integer, List<double[]>> histories = new LinkedHashMap<>();
        for (EventView event : events) {
            if (event.tradingMethod().equals("LMSR")) {
                lmsr.put(event.id(), engine.getEventStatus(event.id()));
                histories.put(event.id(), engine.getPriceHistory(event.id()));
            } else {
                books.put(event.id(), engine.getOrderBookStatus(event.id()));
            }
        }
        return new MarketSnapshot(events, lmsr, books, histories, engine.getUsers());
    }
}
