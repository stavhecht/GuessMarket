package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/me}: the logged-in user's own account. */
@WebServlet(name = "me", urlPatterns = Endpoints.ME)
public class MeServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getAccount(user));
    }
}
