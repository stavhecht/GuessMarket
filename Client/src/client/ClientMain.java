package client;

/**
 * Starts the client. Deliberately not the {@code Application} itself: see {@code ui.Main}
 * for why the main class must not extend it (a class-path launch would refuse to start).
 *
 * <p>The server's address is the first argument, or the {@code guessmarket.server} system
 * property, or the default below: the web application as IntelliJ's Tomcat configuration
 * deploys it on this machine.
 */
public class ClientMain {

    public static final String DEFAULT_SERVER = "http://localhost:8080/GuessMarket";

    public static void main(String[] args) {
        String server = args.length > 0 ? args[0]
                : System.getProperty("guessmarket.server", DEFAULT_SERVER);
        new ClientApp(new RemoteMarket(server)).run();
    }
}