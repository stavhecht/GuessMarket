package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/events/quote?id=&option=&shares=}: what an LMSR purchase would cost. */
@WebServlet(name = "quote", urlPatterns = Endpoints.QUOTE)
public class QuoteServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.quoteParticipation(
                intParam(req, Endpoints.PARAM_ID),
                intParam(req, Endpoints.PARAM_OPTION),
                longParam(req, Endpoints.PARAM_SHARES)));
    }
}
