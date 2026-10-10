package server;

import api.Endpoints;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * {@code POST /api/logout}: frees the name and ends the session. The account stays in the
 * engine, for the same name to pick up on its next login.
 */
@WebServlet(name = "logout", urlPatterns = Endpoints.LOGOUT)
public class LogoutServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = requireUser(req);
        if (user == null) {
            fail(resp, HttpServletResponse.SC_UNAUTHORIZED, "Log in first.", "NotLoggedIn");
            return;
        }
        MarketContext.online(getServletContext()).remove(user);
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(MarketContext.USER);   // before invalidate: sessionDestroyed must not free it twice
            session.invalidate();
        }
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
