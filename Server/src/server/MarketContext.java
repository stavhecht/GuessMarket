package server;

import engine.service.MarketEngine;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The one market the server runs, and who is in it.
 *
 * <p>Created when the web application starts and kept on the {@link ServletContext}, so
 * every servlet reaches the same {@link MarketEngine}. Three things live beside it:
 *
 * <ul>
 *   <li>A read-write lock. The engine has no locking of its own (a single console never
 *       needed any), and Tomcat serves requests on many threads, so every servlet takes
 *       the read lock to look and the write lock to change anything. Coarse, and enough:
 *       every engine command validates and then commits inside one method, so a call
 *       under the write lock is atomic by construction.</li>
 *   <li>The set of names logged in right now. A name is unique among the clients
 *       connected, which is a different thing from the engine's users: a user who logs
 *       out keeps their account (money, holdings, the events they run) and picks it up
 *       again by logging in under the same name, while a second client asking for that
 *       name meanwhile is turned away.</li>
 *   <li>The cash a first login starts with, which is the server's policy and not the
 *       engine's: an exercise-3 file names no users and no money, so somebody has to.</li>
 * </ul>
 *
 * <p>A session that dies without logging out (a client closed from the window's corner,
 * or a network gone away) is caught by {@link #sessionDestroyed} when Tomcat times it
 * out, so its name is freed rather than held for ever.
 */
@WebListener
public class MarketContext implements ServletContextListener, HttpSessionListener {

    /** What a user who has never been here before starts with. */
    public static final int STARTING_CASH = 0;

    /** The session attribute holding the logged-in user's name, as the engine spells it. */
    public static final String USER = "user";

    private static final String ENGINE = MarketContext.class.getName() + ".engine";
    private static final String LOCK = MarketContext.class.getName() + ".lock";
    private static final String ONLINE = MarketContext.class.getName() + ".online";

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        MarketEngine engine = new MarketEngine();
        engine.openEmptyMarket();   // the market exists before any file: events arrive by upload
        context.setAttribute(ENGINE, engine);
        context.setAttribute(LOCK, new ReentrantReadWriteLock(true));
        context.setAttribute(ONLINE, ConcurrentHashMap.newKeySet());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        // Nothing to release: the market lives and dies with the server.
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
    }

    /** A session gone without a logout still frees its name. */
    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        Object user = session.getAttribute(USER);
        if (user != null) {
            online(session.getServletContext()).remove((String) user);
        }
    }

    public static MarketEngine engine(ServletContext context) {
        return (MarketEngine) context.getAttribute(ENGINE);
    }

    public static ReentrantReadWriteLock lock(ServletContext context) {
        return (ReentrantReadWriteLock) context.getAttribute(LOCK);
    }

    @SuppressWarnings("unchecked")
    public static Set<String> online(ServletContext context) {
        return (Set<String>) context.getAttribute(ONLINE);
    }
}