package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/events/history?id=}: an LMSR event's price series, open first. */
@WebServlet(name = "eventHistory", urlPatterns = Endpoints.EVENT_HISTORY)
public class EventHistoryServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getPriceHistory(intParam(req, Endpoints.PARAM_ID)));
    }
}
