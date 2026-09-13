package server;

import api.Endpoints;
import api.OrderRequest;
import engine.model.OrderSide;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code POST /api/events/order}: places a limit order as the logged-in user. */
@WebServlet(name = "order", urlPatterns = Endpoints.ORDER)
public class OrderServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        write(req, resp, (engine, user) -> {
            OrderRequest order = body(req, OrderRequest.class);
            OrderSide side = EventsServlet.named(OrderSide.class, order.side(), "side");
            return engine.placeOrder(user, order.eventId(), order.optionIndex(), side, order.price(), order.quantity());
        });
    }
}
