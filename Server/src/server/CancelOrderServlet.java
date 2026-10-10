package server;

import api.CancelOrderRequest;
import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code POST /api/events/order/cancel}: withdraws the logged-in user's last waiting order. */
@WebServlet(name = "cancelOrder", urlPatterns = Endpoints.CANCEL_ORDER)
public class CancelOrderServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            CancelOrderRequest cancel = body(req, CancelOrderRequest.class);
            return engine.cancelLastOrder(user, required(cancel.eventId(), "eventId"));
        });
    }
}