package server;

import api.CloseRequest;
import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code POST /api/events/close}: settles an event; only its market maker may. */
@WebServlet(name = "close", urlPatterns = Endpoints.CLOSE)
public class CloseServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            CloseRequest close = body(req, CloseRequest.class);
            return engine.closeEvent(user, close.eventId(), close.winningOptionIndex());
        });
    }
}
