package server;

import api.Endpoints;
import api.LoginRequest;
import engine.dto.UserView;
import engine.exception.EngineException;
import engine.service.MarketEngine;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * {@code POST /api/login}: become somebody, by name alone.
 *
 * <p>No password and no sign-up, as the exercise asks: a name nobody is using is yours.
 * If the engine already has a user of that name (someone who logged out earlier), the
 * account is picked up again, money and holdings intact; if not, one is opened with
 * {@link MarketContext#STARTING_CASH}. Either way the name is refused with 409 while
 * another client is logged in under it, which is the one rule the login page exists to
 * show, and the client stays on that page to try another.
 *
 * <p>The whole thing runs under the write lock, since a first login creates a user, and
 * the check-and-claim on the online set is inside that too, so two clients asking for the
 * same free name at the same instant cannot both get it.
 */
@WebServlet(name = "login", urlPatterns = Endpoints.LOGIN)
public class LoginServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String wanted;
        try {
            LoginRequest login = body(req, LoginRequest.class);
            wanted = login.name() == null ? "" : login.name().trim();
        } catch (RuntimeException e) {     // no body, or not JSON: Gson's complaint is not for the user
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "Send a JSON body with a 'name'.", "BadRequest");
            return;
        }
        if (wanted.isEmpty()) {
            fail(resp, HttpServletResponse.SC_BAD_REQUEST, "Please enter a name.", "BadRequest");
            return;
        }

        MarketEngine engine = MarketContext.engine(getServletContext());
        Set<String> online = MarketContext.online(getServletContext());
        Lock lock = MarketContext.lock(getServletContext()).writeLock();
        lock.lock();
        try {
            // The name as the engine spells it, if this person has been here before.
            UserView account = engine.findUser(wanted).orElse(null);
            String name = account == null ? wanted : account.name();

            if (online.contains(name)) {
                fail(resp, HttpServletResponse.SC_CONFLICT,
                        "'" + name + "' is already logged in. Choose another name.", "AlreadyLoggedIn");
                return;
            }
            if (account == null) {
                account = engine.registerUser(name, MarketContext.STARTING_CASH);
                name = account.name();
            }

            HttpSession session = req.getSession(true);
            String before = (String) session.getAttribute(MarketContext.USER);
            if (before != null) {
                online.remove(before);   // the same client, logging in again as somebody else
            }
            online.add(name);
            session.setAttribute(MarketContext.USER, name);
            ok(resp, account);
        } catch (EngineException e) {
            fail(resp, statusFor(e), e.getMessage(), e.getClass().getSimpleName());
        } finally {
            lock.unlock();
        }
    }
}