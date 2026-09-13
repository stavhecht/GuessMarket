package server;

import api.Endpoints;
import api.ParticipateRequest;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code POST /api/events/participate}: buys LMSR shares as the logged-in user. */
@WebServlet(name = "participate", urlPatterns = Endpoints.PARTICIPATE)
public class ParticipateServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            ParticipateRequest purchase = body(req, ParticipateRequest.class);
            return engine.participate(user, purchase.eventId(), purchase.optionIndex(), purchase.shares());
        });
    }
}
