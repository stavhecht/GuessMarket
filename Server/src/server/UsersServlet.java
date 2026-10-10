package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/users}: every account in the market. */
@WebServlet(name = "users", urlPatterns = Endpoints.USERS)
public class UsersServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        read(req, resp, (engine, user) -> engine.getUsers());
    }
}
