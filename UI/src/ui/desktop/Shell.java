package ui.desktop;

import engine.dto.EventView;
import engine.exception.EngineException;
import engine.service.Market;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The window, in the shape the design lays out: a file bar, two tabs, and a status line,
 * with the market it shows left to a subclass.
 *
 * <p>Two apps are this window. {@link DesktopApp} is the exercise-2 admin view, whose
 * {@link engine.service.MarketEngine} is in the same process and whose file bar loads a
 * file from disk and saves a session. The exercise-3 client is the same window over a
 * market on a server, whose file bar uploads a file and logs out. Everything they share
 * is here, and what they do not share is the handful of hooks at the foot of this class:
 * where the market comes from, what the file-bar buttons do, and what the two labels in
 * the bar say.
 *
 * <p>The two tabs are the two screens: {@link EventsScreen} is every event and the market
 * of whichever one is selected, {@link UsersScreen} is every user (or, for a client, the
 * one logged in) and the account of whichever one is selected. Both drive the same
 * {@link Market} and both are redrawn by {@link #refresh()}, so a trade made on one is
 * visible on the other.
 *
 * <p>Like {@link ui.console.ConsoleApp}, this class never reaches past {@link Market}:
 * it reads DTOs and calls commands. It owns the same two conversions the console owns:
 * the option numbers a person sees against the 0-based indices the engine uses, and the
 * moment at which a figure gets rounded for display.
 *
 * <p>Every command goes through {@link #perform}, so a rejected one paints its reason in
 * the status bar and leaves the window as it was, in the same spirit as
 * {@code ConsoleApp.dispatch} catching {@code EngineException} in exactly one place.
 * Don't add try/catch to the individual handlers.
 *
 * <p><b>The fixed shell of the window comes from {@code DesktopApp.fxml}</b>, loaded in
 * {@link #start}: the file bar, the tab pane and the status line are built by
 * {@link FXMLLoader} and injected into the {@code @FXML} fields below, and this class then
 * only wires the behaviour onto them. What the loader cannot build is everything generated
 * from the loaded market (table rows, the option cards, the ladder, the chart) so the two
 * tabs' bodies are still {@link EventsScreen} and {@link UsersScreen}, constructed here and
 * dropped into the tabs the FXML declared. The layout file spells those bodies out anyway,
 * with one representative row of each kind, so that Scene Builder shows the real design
 * rather than two empty boxes; those stand-ins are what {@code setContent} replaces.
 */
public abstract class Shell extends Application implements Host {

    /** The layout file, beside this class. Named once so the failure message can quote it. */
    private static final String LAYOUT = "DesktopApp.fxml";

    /**
     * The instance {@code Main} built, for the one JavaFX builds.
     *
     * <p>{@link Application#launch} instantiates the class reflectively and never sees the
     * object {@code run()} was called on, so what that object was given (the market) is
     * parked here for {@link #start} to pick up. The main class does not extend
     * {@code Application} itself (that alone would break every way of starting the app,
     * see {@code ui.Main}), which is why the handoff is needed at all.
     */
    private static Shell handoff;

    private Market market;

    // Built in start() rather than here: a JavaFX control cannot be created until the
    // toolkit is up, and Main constructs this class before ever calling run().
    private Stage stage;
    private Scene scene;
    private Parent shellRoot;
    private Theme theme = Theme.LIGHT;

    private final List<Circle> tinted = new ArrayList<>();

    // Everything below is injected out of DesktopApp.fxml by name: the field name here is
    // the fx:id there. Rename one and you must rename the other: the compiler cannot see
    // into the layout file, so a mismatch is a null at start-up, not a build error.
    @FXML protected Button loadFile;
    @FXML protected Button saveSession;
    @FXML protected Button loadSession;
    @FXML protected Button logout;
    @FXML protected Button themeToggle;
    @FXML protected Label fileState;
    @FXML protected Label filePath;
    @FXML protected Circle loadedMark;
    @FXML protected ProgressBar loading;
    @FXML protected Label percent;
    @FXML protected Label status;
    @FXML protected Label actingAs;
    @FXML protected TabPane tabs;
    @FXML protected Tab eventsTab;
    @FXML protected Tab usersTab;
    @FXML protected CheckBox animations;

    /** The file-bar buttons, disabled while a load is in flight. */
    protected final List<Button> whileIdle = new ArrayList<>();

    private EventsScreen eventsScreen;
    private UsersScreen usersScreen;

    /**
     * What the charts watch. Refreshed here, before either screen redraws, so a listener on
     * a price or a balance has already seen the new figure by the time the screen that owns
     * the chart is asked to lay itself out again.
     */
    private final LiveMarket live = new LiveMarket();

    /** Required by JavaFX, which instantiates the subclass reflectively. */
    protected Shell() {
    }

    /** Used by a main class: the market to drive, handed on to the instance JavaFX creates. */
    protected Shell(Market market) {
        this.market = market;
    }

    /** Opens the window and does not return until it is closed. */
    public void run() {
        handoff = this;
        Application.launch(getClass());
    }

    @Override
    public Market engine() {
        return market;
    }

    @Override
    public LiveMarket live() {
        return live;
    }

    @Override
    public Window window() {
        return stage;
    }

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        if (handoff != null) {
            this.market = handoff.market;
            handoff = null;
        }

        // The shell first: this call is what fills in every @FXML field above.
        shellRoot = loadLayout();

        // Then the two generated bodies, over the stand-ins the layout file carries.
        eventsScreen = new EventsScreen(this);
        usersScreen = new UsersScreen(this, singleUser());
        eventsTab.setContent(workspace(eventsScreen));
        usersTab.setContent(workspace(usersScreen));
        if (singleUser()) {
            usersTab.setText("Account");    // one account, the logged-in user's: not a list of users
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> refresh());

        usersScreen.animateOnlyWhile(usersTab.selectedProperty());

        wireBar();
        wireFileBar();
        wireAnimationSwitch();

        scene = new Scene(shellRoot);
        theme.applyTo(scene);
        repaintShapes();

        stage.setScene(scene);
        stage.setTitle("Guess Market");
        stage.setMinWidth(700);
        stage.setMinHeight(460);
        stage.show();

        opened();
    }

    /**
     * Builds the window's fixed shell from {@code DesktopApp.fxml} and hands back its root.
     *
     * <p>The loader is told to use <em>this</em> instance as the controller rather than
     * making one of its own: JavaFX already created this object and {@code run()} already
     * handed it the market, so a second controller built reflectively out of the
     * {@code fx:controller} attribute would be wired to nothing. A controller factory that
     * ignores the class it is asked for and returns {@code this} is how the attribute stays
     * in the file (Scene Builder reads it to offer the {@code fx:id}s) without a second
     * instance ever existing; it is also what lets the attribute name {@code DesktopApp}
     * while the client runs the same file, since the loader injects the fields of whatever
     * object comes back, up its whole class hierarchy.
     *
     * <p>The file has to be on the classpath beside this class, the way
     * {@code guessmarket.css} does, and the failure if it is not says so plainly: the
     * window cannot be built without it, and a stack trace out of {@code FXMLLoader} would
     * not name the missing file.
     */
    private Parent loadLayout() {
        URL layout = Shell.class.getResource(LAYOUT);
        if (layout == null) {
            throw new IllegalStateException(LAYOUT + " is missing from the classpath, next to "
                    + "Shell.class, and the window's layout is read from it at start-up.");
        }
        FXMLLoader loader = new FXMLLoader(layout);
        loader.setControllerFactory(type -> this);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + LAYOUT + ": " + e.getMessage(), e);
        }
    }

    /**
     * The part of the file strip both windows share: the buttons hold their labels, the
     * theme toggle cycles, and the green mark is filled from the theme. What the other
     * buttons do is the subclass's, in {@link #wireFileBar}.
     */
    private void wireBar() {
        // The file bar is above the scrolling workspace and is squeezed by the window
        // itself, so its buttons hold their labels and the path field is what gives.
        for (Button button : List.of(loadFile, saveSession, loadSession, logout, themeToggle)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }

        themeToggle.setText(theme.label());
        themeToggle.setOnAction(action -> cycleTheme());

        // Filled rather than styled, so repaintShapes has to know about it and which token
        // it wears. A looked-up colour cannot reach a Shape's fill from the stylesheet.
        loadedMark.getProperties().put("token", "up");
        tinted.add(loadedMark);
    }

    /**
     * Hands the check box on the tab strip the two things in the window that move.
     *
     * <p>{@link Ticker} takes it as a binding, so every figure in both screens reads the
     * same switch, and a ticker built later (either screen rebuilds its panes as the
     * selection changes) is governed by it without being told. The load bar's ramp reads it
     * per load instead: see {@link #runWithProgress}.
     *
     * <p>Only the animation is switched off. Every figure is still written, and the bar
     * still crosses the strip, so the window with this unticked says exactly what it says
     * with it ticked, only without the movement.
     */
    private void wireAnimationSwitch() {
        animations.setTooltip(new Tooltip(
                "Roll figures to their new value, and ramp the load bar, instead of jumping"));
        Ticker.animated().bind(animations.selectedProperty());
    }

    /**
     * A tab's body, in the padded ground the design puts it on, and behind a scroll pane.
     *
     * <p>The window is resizable and will be opened on screens smaller than the one this
     * was drawn for, so every screen names the smallest size it is still worth drawing at
     * ({@code EventsScreen} its two panels, {@code UsersScreen} its account column) and
     * gives width and height back down to that. Underneath it, the choice is between
     * crushing a table into its neighbours and scrolling, and this is the scrolling: the
     * viewport is fitted in both directions, so the bars appear only past the floor and
     * nothing moves at the sizes above it.
     */
    private static Region workspace(Node content) {
        StackPane pane = new StackPane(content);
        pane.getStyleClass().add("workspace");

        ScrollPane scroller = new ScrollPane(pane);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.getStyleClass().add("workspace-scroll");
        return scroller;
    }

    /**
     * Shows {@code root} in the window instead of the shell, dressed the same way. For a
     * window that has something to show before the market (a login), and
     * {@link #showShell()} is the way back.
     */
    protected void showRoot(Parent root) {
        scene.setRoot(root);
        theme.applyTo(scene);
    }

    /** Puts the file bar, the tabs and the status line back on screen. */
    protected void showShell() {
        showRoot(shellRoot);
    }

    // --- long work with a bar ---

    /**
     * How long the bar takes to cross the strip when the work itself takes no time at all.
     *
     * <p>Reading one of these files locally is over in a few milliseconds, which on screen
     * is a flicker and nothing else, so the two ramps below are stretched over this instead.
     * Work that really does take longer is not padded past its own time: the bar simply
     * rests at {@link #HANDOVER} while the engine works, and finishes when it is done.
     * Work that is slow by nature (an upload, waiting on a server) is not padded at all:
     * see the {@code padded} flag of {@link #runWithProgress}.
     */
    private static final long RAMP_MILLIS = 1600;

    /** The fraction the bar has reached by the time the engine is actually called. */
    private static final double HANDOVER = 0.4;

    /**
     * Runs {@code work} on a file off the FX thread, so a big one cannot freeze the window
     * while it is read, with the file strip showing the bar. Nothing on screen is redrawn
     * until it has succeeded: the engine leaves the market untouched if the file is
     * rejected, and so does this.
     *
     * <p>The bar is bound to the {@link Task}'s own progress rather than driven from
     * outside it: the task is the only thing that knows how far along it is, and binding
     * is what keeps the FX thread out of the worker's way.
     *
     * @param verb   what the strip says while it runs ("Reading", "Uploading")
     * @param padded whether to stretch the bar over {@link #RAMP_MILLIS}. True for work done
     *               in this process, which would otherwise be a flicker. False for work that
     *               takes its own time somewhere else, such as an upload the server reads:
     *               there the wait is real, so nothing is added to it, and since nobody here
     *               knows how far along the server is, the bar is indeterminate until the
     *               answer arrives rather than pretending to a fraction
     * @param work   the engine call, returning the sentence to report when it succeeds
     */
    protected void runWithProgress(String verb, File file, boolean padded, Callable<String> work) {
        // Read here rather than in the task: the switch is a control, and a control is the
        // FX thread's. A load already under way keeps the ramp it started with.
        boolean ramp = animations.isSelected();

        Task<String> load = new Task<>() {
            @Override
            protected String call() throws Exception {
                if (!padded) {
                    updateProgress(ProgressBar.INDETERMINATE_PROGRESS, 1);
                    String done = work.call();
                    updateProgress(1, 1);
                    return done;
                }
                updateProgress(0, 1);
                creep(0, HANDOVER, Math.round(RAMP_MILLIS * HANDOVER));
                String done = work.call();
                creep(HANDOVER, 1, Math.round(RAMP_MILLIS * (1 - HANDOVER)));
                return done;
            }

            /**
             * Walks progress from one fraction to another over {@code millis}, in steps
             * small enough that the bar moves rather than jumps.
             *
             * <p>This is the deliberate delay. Without it the whole thing is over before
             * the strip has finished appearing, and a load that reports nothing looks the
             * same as a load that failed. It is the one movement in the window that is
             * spent rather than saved, so the animation switch turns it off with the rest:
             * unticked, the bar goes straight to the fraction it was walking to.
             */
            private void creep(double from, double to, long millis) throws InterruptedException {
                if (!ramp) {
                    updateProgress(to, 1);
                    return;
                }
                int steps = 32;
                for (int step = 1; step <= steps; step++) {
                    Thread.sleep(millis / steps);
                    updateProgress(from + (to - from) * step / steps, 1);
                }
            }
        };

        loading.progressProperty().bind(load.progressProperty());
        percent.textProperty().bind(load.progressProperty().map(Shell::asPercent));

        load.setOnSucceeded(done -> {
            showLoading(false);
            live.reset();       // a new market, not a change to the old one
            refresh();
            flashLoaded();
            report(load.getValue(), false);
        });
        load.setOnFailed(done -> {
            showLoading(false);
            Throwable cause = load.getException();
            report(cause == null || cause.getMessage() == null ? "Could not read that file."
                    : cause.getMessage(), true);
            refresh();
        });

        showLoading(true);
        report(verb + " " + file.getName() + "…", false);
        Thread worker = new Thread(load, "guessmarket-load");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * A task's progress as the design writes it. A negative progress is a task that has not
     * started or cannot say how far along it is, and a percentage of it would be a lie, so
     * it reads as nothing rather than as 0% or −100%.
     */
    private static String asPercent(Number progress) {
        double fraction = progress == null ? -1 : progress.doubleValue();
        return fraction < 0 ? "" : Math.round(fraction * 100) + "%";
    }

    private void showLoading(boolean busy) {
        for (Node control : List.of(loading, percent)) {
            control.setVisible(busy);
            control.setManaged(busy);
        }
        filePath.setVisible(!busy);
        filePath.setManaged(!busy);
        for (Button button : whileIdle) {
            button.setDisable(busy);
        }
        fileState.setText(busy ? "LOADING" : fileStateText());
    }

    /** The design's green tick, for as long as it takes to notice it. */
    private void flashLoaded() {
        loadedMark.setVisible(true);
        loadedMark.setManaged(true);
        fileState.setText("LOADED " + market.getEvents().size() + " EVENTS · "
                + market.getUsers().size() + " USERS");
        fileState.getStyleClass().add("up");

        PauseTransition settle = new PauseTransition(Duration.seconds(4));
        settle.setOnFinished(done -> {
            loadedMark.setVisible(false);
            loadedMark.setManaged(false);
            fileState.getStyleClass().remove("up");
            fileState.setText(fileStateText());
        });
        settle.play();
    }

    // --- the look ---

    private void cycleTheme() {
        wearTheme(theme.next());
    }

    /**
     * Dresses the window in one theme: the toggle's next look, or the one a restored
     * session was saved wearing.
     */
    protected void wearTheme(Theme wanted) {
        theme = wanted;
        theme.applyTo(scene);
        themeToggle.setText(theme.label());
        repaintShapes();
        // The few things drawn rather than styled (depth bars, the chart, the title dots)
        // read their colours off the theme when they are built, so they are rebuilt here.
        Platform.runLater(this::refresh);
    }

    /** The handful of shapes that are filled rather than styled, repainted for the theme. */
    private void repaintShapes() {
        for (Circle circle : tinted) {
            circle.setFill(javafx.scene.paint.Color.web(
                    theme.token((String) circle.getProperties().get("token"))));
        }
    }

    // --- commands ---

    /**
     * Runs one engine command and reports it.
     *
     * <p>Whatever happens the window is redrawn afterwards, because a command that failed
     * halfway is exactly the case where the screen must not be trusted, though the engine
     * makes sure there is no such halfway.
     *
     * @param action performs the command and returns what to tell the user
     */
    @Override
    public void perform(Supplier<String> action) {
        try {
            report(action.get(), false);
        } catch (EngineException e) {
            report(e.getMessage(), true);
        } catch (RuntimeException e) {
            report(e.getMessage() == null ? e.toString() : e.getMessage(), true);
        } finally {
            refresh();
        }
    }

    @Override
    public void createEvent() {
        EventDialogs.create(this);
    }

    @Override
    public void closeEvent(EventView event) {
        EventDialogs.close(this, event);
    }

    @Override
    public void loadFunds() {
        EventDialogs.loadFunds(this);
    }

    /**
     * Nothing by default: opening an account is up to the market, and only the admin app's
     * engine takes one from a form, so {@code DesktopApp} overrides this. A client never
     * reaches it, since its Users tab has no list panel to carry the button.
     */
    @Override
    public void addUser() {
        report("Accounts in this market are opened by logging in.", true);
    }

    /** A sum as the screens write it, for a subclass's own label in the bar. */
    protected static String money(double amount) {
        return Widgets.money(amount);
    }

    /** Rereads everything on screen from the market. Cheap, and the only way state gets stale. */
    protected void refresh() {
        if (market.isFileLoaded()) {
            live.sync(market);
        }
        eventsScreen.refresh();
        usersScreen.refresh();
        actingAs.setText(market.isFileLoaded() ? actingAsText() : "");
        if (!loading.isVisible() && !fileState.getStyleClass().contains("up")) {
            fileState.setText(fileStateText());
        }
        refreshBar();
    }

    @Override
    public void report(String message, boolean isError) {
        status.setText(message);
        status.getStyleClass().removeAll("down", "muted");
        status.getStyleClass().add(isError ? "down" : "muted");
    }

    protected Optional<File> chooseFile(String title, String description, String pattern, boolean forSaving) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, pattern));
        return Optional.ofNullable(forSaving ? chooser.showSaveDialog(stage) : chooser.showOpenDialog(stage));
    }

    // --- what each window decides for itself ---

    /**
     * Whether the Users tab shows one account, the logged-in user's, rather than everyone
     * with a picker. The admin view shows everyone; a client is somebody in particular.
     */
    protected abstract boolean singleUser();

    /** Puts the behaviour on the file strip's buttons: what a file is, and what a session is. */
    protected abstract void wireFileBar();

    /** The right-hand end of the status line, once there is a market: who commands act as. */
    protected abstract String actingAsText();

    /** What the file strip says when nothing is in flight. */
    protected abstract String fileStateText();

    /** Called once the window is up, for the subclass's first refresh and greeting. */
    protected abstract void opened();

    /** The last thing {@link #refresh} does; for any label of the subclass's own. */
    protected void refreshBar() {
    }

    // --- reading what was typed ---

    /*
     * These turn text into a number and do nothing else. Whether the number is acceptable
     * (a positive share count, a price inside the book's range, a commission under the
     * ceiling) is the engine's to say, and it says it whichever window or client is asking:
     * a figure checked here as well would be a second rule that could drift from the first,
     * and the client's copy of it would protect a server that cannot assume the client ran it.
     */

    /** A typed whole number of {@code what}; the message says what was expected, and {@link #perform} shows it. */
    static long readLong(String text, String what) {
        try {
            return Long.parseLong(text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a whole number of " + what + ".");
        }
    }

    /** The same, for the figures the engine takes as an {@code int}. */
    static int readInt(String text, String what) {
        try {
            return Integer.parseInt(text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a whole " + what + ".");
        }
    }

    /**
     * A typed figure that may have a fraction. Commas are not stripped here as they are for a
     * whole number, since {@code 0,5} is somebody's half and must not become 5.
     */
    static double readDouble(String text, String what) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a " + what + ".");
        }
    }
}
