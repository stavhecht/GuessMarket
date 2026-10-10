package client;

import engine.dto.EventView;
import engine.dto.UserView;
import engine.exception.EngineException;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import ui.desktop.Shell;

import java.io.IOException;
import java.net.URL;
import java.util.List;

/**
 * The exercise-3 client: the exercise-2 window over a market on a server.
 *
 * <p>Everything on screen after the login is {@link Shell}: the file bar, the two tabs,
 * the status line, and inside them the same screens, panes, charts and dialogs the admin
 * app shows, reading and commanding a {@link RemoteMarket} instead of a local engine and
 * none the wiser. What this class adds is only what a client has and an admin console
 * does not: a login page in front of the shell, an upload where the admin loads a file, a
 * logout where it saves a session, a poll that brings other clients' trades into the
 * window, and each event's chat, which the Events tab shows for a window that has one.
 */
public class ClientApp extends Shell {

    private static final String LOGIN_LAYOUT = "Login.fxml";

    // Injected out of Login.fxml, by the same rule as the shell's fields: fx:id is field name.
    @FXML private VBox loginPane;
    @FXML private TextField loginName;
    @FXML private Button loginButton;
    @FXML private Label loginError;

    private Parent loginRoot;

    /** Required by JavaFX, which instantiates this class reflectively. */
    public ClientApp() {
    }

    /** Used by {@link ClientMain}: the server to talk to, handed on to the instance JavaFX creates. */
    public ClientApp(RemoteMarket market) {
        super(market);
    }

    private RemoteMarket market() {
        return (RemoteMarket) engine();
    }

    @Override
    protected boolean singleUser() {
        return true;    // a client is whoever logged in, and the account tab is theirs
    }

    /** Everyone connected to the server reads the same chat for each event. */
    @Override
    public boolean hasChat() {
        return true;
    }

    /**
     * The file strip, as a client: the primary button uploads rather than loads, the two
     * session buttons the admin app has are gone (a session lives on the server now), and
     * the log-out button the layout keeps hidden for the admin app is shown.
     */
    @Override
    protected void wireFileBar() {
        loadFile.setText("Upload file");
        // Not padded: the upload is a round trip to the server, which takes the time it takes,
        // so the bar waits on that rather than on a ramp of its own.
        loadFile.setOnAction(action -> chooseFile("Upload events file", "XML files", "*.xml", false)
                .ifPresent(file -> runWithProgress("Uploading", file, false, () -> {
                    List<EventView> made = market().uploadEventsFile(file);
                    return "Uploaded " + made.size() + (made.size() == 1 ? " event" : " events")
                            + " from " + file.getName() + "; you are their market maker.";
                })));

        for (Button button : List.of(saveSession, loadSession)) {
            button.setVisible(false);
            button.setManaged(false);
        }
        logout.setVisible(true);
        logout.setManaged(true);
        logout.setOnAction(action -> logOut());

        whileIdle.addAll(List.of(loadFile, logout));
    }

    /**
     * Who this window is, and what they have: the balance sits in the status line so it can
     * be read from the Events tab too, where the purchases that move it are made. It is
     * rewritten on every refresh, which the poll drives, so it follows other clients' trades
     * into this account as well as this window's own.
     */
    @Override
    protected String actingAsText() {
        UserView me = market().account();
        String balance = me == null ? "" : " · balance " + money(me.balance());
        return "Logged in as " + market().getCurrentUserName() + balance + " · " + market().base();
    }

    @Override
    protected String fileStateText() {
        return market().isFileLoaded() ? "CONNECTED" : "NOT LOGGED IN";
    }

    /** The window opens on the login page; the shell waits behind it. */
    @Override
    protected void opened() {
        loginRoot = loadLogin();
        loginButton.setOnAction(action -> logIn());
        showRoot(loginRoot);
        loginName.requestFocus();
    }

    private Parent loadLogin() {
        URL layout = ClientApp.class.getResource(LOGIN_LAYOUT);
        if (layout == null) {
            throw new IllegalStateException(LOGIN_LAYOUT + " is missing from the classpath, next to "
                    + "ClientApp.class, and the login page is read from it at start-up.");
        }
        FXMLLoader loader = new FXMLLoader(layout);
        loader.setControllerFactory(type -> this);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + LOGIN_LAYOUT + ": " + e.getMessage(), e);
        }
    }

    /**
     * Asks the server for the typed name. A refusal (the name is in use, or the server is
     * out of reach) is written under the field and the page stays, with the name still in
     * it, for another try; acceptance swaps the shell in and starts the poll.
     */
    private void logIn() {
        loginError.setText("");
        loginButton.setDisable(true);
        try {
            String name = market().login(loginName.getText()).name();
            live().reset();
            showShell();
            refresh();
            report("Logged in as " + name + ". Upload an events file, or trade on the events already here.", false);
            market().startPolling(this::refresh, trouble -> report(trouble, true));
        } catch (EngineException e) {
            loginError.setText(e.getMessage());
        } finally {
            loginButton.setDisable(false);
        }
    }

    /** Frees the name on the server and goes back to the login page, ready for the next. */
    private void logOut() {
        market().logout();
        live().reset();
        refresh();                  // with no market, both screens empty themselves
        loginError.setText("");
        showRoot(loginRoot);
        loginName.requestFocus();
    }

    /** Closing the window is logging out, so the name is free for the next client. */
    @Override
    public void stop() {
        if (market().getCurrentUserName() != null) {
            market().logout();
        }
    }
}