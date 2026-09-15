package server;

import api.Endpoints;
import api.LoadFundsRequest;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code POST /api/me/funds}: loads money into the logged-in user's account; answers with the account after. */
@WebServlet(name = "funds", urlPatterns = Endpoints.FUNDS)
public class FundsServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            LoadFundsRequest load = body(req, LoadFundsRequest.class);
            return engine.loadFunds(user, load.amount());
        });
    }
}