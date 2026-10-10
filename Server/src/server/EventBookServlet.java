package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/events/book?id=}: an order-book event's live state. */
@WebServlet(name = "eventBook", urlPatterns = Endpoints.EVENT_BOOK)
public class EventBookServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getOrderBookStatus(intParam(req, Endpoints.PARAM_ID)));
    }
}
