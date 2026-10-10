package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/events/status?id=}: an LMSR event's live state. */
@WebServlet(name = "eventStatus", urlPatterns = Endpoints.EVENT_STATUS)
public class EventStatusServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getEventStatus(intParam(req, Endpoints.PARAM_ID)));
    }
}
