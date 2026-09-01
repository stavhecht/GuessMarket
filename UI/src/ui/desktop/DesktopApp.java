package ui.desktop;

import engine.dto.EventView;
import engine.dto.SettlementResult;
import engine.exception.EngineException;
import engine.service.MarketEngine;
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
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The desktop front end: an admin view onto one loaded market, in the shape the design
 * lays out: a title strip, a file bar, two tabs, and a status line.
 *
 * <p>The two tabs are the two screens: {@link EventsScreen} is every event and the market
 * of whichever one is selected, {@link UsersScreen} is every user and the account of
 * whichever one is selected. Both drive the same engine and both are redrawn by
 * {@link #refresh()}, so a trade made on one is visible on the other.
 *
 * <p>Like {@link ui.console.ConsoleApp}, this class never reaches past {@link MarketEngine}:
 * it reads DTOs and calls commands. It owns the same two conversions the console owns:
 * the option numbers a person sees against the 0-based indices the engine uses, and the
 * moment at which a figure gets rounded for display.
 *
 * <p>Every engine call goes through {@link #perform}, so a rejected command paints its
 * reason in the status bar and leaves the window as it was, in the same spirit as
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
public class DesktopApp extends Application {

    /** The layout file, beside this class. Named once so the failure message can quote it. */
    private static final String LAYOUT = "DesktopApp.fxml";

    private static MarketEngine sharedEngine;

    private MarketEngine engine;

    // Built in start() rather than here: a JavaFX control cannot be created until the
    // toolkit is up, and Main constructs this class before ever calling run().
    private Stage stage;
    private Scene scene;
    private Theme theme = Theme.LIGHT;

    private final List<Circle> tinted = new ArrayList<>();

    // Everything below is injected out of DesktopApp.fxml by name: the field name here is
    // the fx:id there. Rename one and you must rename the other: the compiler cannot see
    // into the layout file, so a mismatch is a null at start-up, not a build error.
    @FXML private Button loadFile;
    @FXML private Button saveSession;
    @FXML private Button loadSession;
    @FXML private Button themeToggle;
    @FXML private Label fileState;
    @FXML private Label filePath;
    @FXML private Circle loadedMark;
    @FXML private ProgressBar loading;
    @FXML private Label percent;
    @FXML private Label status;
    @FXML private Label actingAs;
    @FXML private TabPane tabs;
    @FXML private Tab eventsTab;
    @FXML private Tab usersTab;

    /** The file-bar buttons, disabled while a load is in flight. */
    private final List<Button> whileIdle = new ArrayList<>();

    private EventsScreen eventsScreen;
    private UsersScreen usersScreen;

    /**
     * What the charts watch. Refreshed here, before either screen redraws, so a listener on
     * a price or a balance has already seen the new figure by the time the screen that owns
     * the chart is asked to lay itself out again.
     */
    private final LiveMarket live = new LiveMarket();

    private String loadedFile;

    /** Required by JavaFX, which instantiates this class reflectively. */
    public DesktopApp() {
    }

    /** Used by {@code Main}: the engine to drive, handed on to the instance JavaFX creates. */
    public DesktopApp(MarketEngine engine) {
        this.engine = engine;
    }

    /** Opens the window and does not return until it is closed. */
    public void run() {
        sharedEngine = engine;
        Application.launch(DesktopApp.class);
    }

    MarketEngine engine() {
        return engine;
    }

    /** The observable figures the two screens bind their charts to. */
    LiveMarket live() {
        return live;
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.engine = sharedEngine != null ? sharedEngine : new MarketEngine();

        // The shell first: this call is what fills in every @FXML field above.
        Parent root = loadLayout();

        // Then the two generated bodies, over the stand-ins the layout file carries.
        eventsScreen = new EventsScreen(this);
        usersScreen = new UsersScreen(this);
        eventsTab.setContent(workspace(eventsScreen));
        usersTab.setContent(workspace(usersScreen));
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> refresh());

        // Money moved by a purchase on the Events tab would otherwise roll behind this one's
        // back; held on the tab's own selection, the movement waits for the tab to open.
        usersScreen.animateOnlyWhile(usersTab.selectedProperty());

        wireFileBar();

        // The window's size is the layout file's: it sets the root's preferred size, and a
        // Scene with no dimensions of its own takes it.
        scene = new Scene(root);
        theme.applyTo(scene);
        repaintShapes();

        stage.setScene(scene);
        stage.setTitle("Guess Market");
        // The floor is the file bar: everything under it scrolls, and that strip cannot.
        stage.setMinWidth(700);
        stage.setMinHeight(460);
        stage.show();

        refresh();
        if (engine.isFileLoaded()) {
            report(engine.getEvents().size() + " events loaded.", false);
        }
    }

    /**
     * Builds the window's fixed shell from {@code DesktopApp.fxml} and hands back its root.
     *
     * <p>The loader is told to use <em>this</em> instance as the controller rather than
     * making one of its own: JavaFX already created this object and {@code Main} already
     * handed it the engine, so a second {@code DesktopApp} built reflectively out of the
     * {@code fx:controller} attribute would be wired to nothing. A controller factory that
     * ignores the class it is asked for and returns {@code this} is how the attribute stays
     * in the file (Scene Builder reads it to offer the {@code fx:id}s) without a second
     * instance ever existing.
     *
     * <p>The file has to be on the classpath beside this class, the way
     * {@code guessmarket.css} does, and the failure if it is not says so plainly: the
     * window cannot be built without it, and a stack trace out of {@code FXMLLoader} would
     * not name the missing file.
     */
    private Parent loadLayout() {
        URL layout = DesktopApp.class.getResource(LAYOUT);
        if (layout == null) {
            throw new IllegalStateException(LAYOUT + " is missing from the classpath, next to "
                    + "DesktopApp.class, and the window's layout is read from it at start-up.");
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
     * Puts the behaviour on the file strip the layout file drew: one primary action, one
     * read-only field saying what is loaded, and the two session commands. The field is the
     * design's three states in one place (nothing loaded, loading, and a flash of green
     * when a file has just come in) and the FXML leaves it in the first of them, with the
     * bar and the percentage hidden and unmanaged.
     */
    private void wireFileBar() {
        // The file bar is above the scrolling workspace and is squeezed by the window
        // itself, so its buttons hold their labels and the path field is what gives.
        for (Button button : List.of(loadFile, saveSession, loadSession, themeToggle)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }

        loadFile.setOnAction(action -> chooseFile("Load events file", "XML files", "*.xml", false)
                .ifPresent(this::loadInBackground));

        // The theme rides along in the session file, so reopening it reopens the look.
        saveSession.setOnAction(action -> chooseFile("Save session", "GuessMarket sessions", "*.gm", true)
                .ifPresent(file -> perform(() ->
                        "Session saved to " + engine.saveState(file.getPath(), theme.name()) + ".")));

        loadSession.setOnAction(action -> chooseFile("Load session", "GuessMarket sessions", "*.gm", false)
                .ifPresent(file -> perform(() -> {
                    loadedFile = engine.loadState(file.getPath());
                    live.reset();   // the balances behind a restored session are not this run's
                    Theme saved = Theme.named(engine.getRestoredUiState());
                    if (saved != null && saved != theme) {
                        wearTheme(saved);
                    }
                    return "Session loaded from " + loadedFile + ".";
                })));

        themeToggle.setText(theme.label());
        themeToggle.setOnAction(action -> cycleTheme());

        // Filled rather than styled, so repaintShapes has to know about it and which token
        // it wears. A looked-up colour cannot reach a Shape's fill from the stylesheet.
        loadedMark.getProperties().put("token", "up");
        tinted.add(loadedMark);

        whileIdle.addAll(List.of(loadFile, saveSession, loadSession));
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
    private static Region workspace(javafx.scene.Node content) {
        StackPane pane = new StackPane(content);
        pane.getStyleClass().add("workspace");

        ScrollPane scroller = new ScrollPane(pane);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(true);
        scroller.getStyleClass().add("workspace-scroll");
        return scroller;
    }

    // --- loading ---

    /**
     * How long the bar takes to cross the strip when the file itself takes no time at all.
     *
     * <p>Reading one of these files is over in a few milliseconds, which on screen is a
     * flicker and nothing else, so the two ramps below are stretched over this instead.
     * A file that really does take longer is not padded past its own time: the bar simply
     * rests at {@link #HANDOVER} while the engine works, and finishes when it is done.
     */
    private static final long RAMP_MILLIS = 1600;

    /** The fraction the bar has reached by the time the engine is actually called. */
    private static final double HANDOVER = 0.4;

    /**
     * Reads an events file off the FX thread, so a big one cannot freeze the window while
     * JAXB works through it. Nothing on screen is redrawn until it has succeeded: the
     * engine leaves the previous session untouched if the file is rejected, and so does
     * this.
     *
     * <p>The bar is bound to the {@link Task}'s own progress rather than driven from
     * outside it: the task is the only thing that knows how far along it is, and binding
     * is what keeps the FX thread out of the worker's way.
     */
    private void loadInBackground(File file) {
        Task<Void> load = new Task<>() {
            @Override
            protected Void call() throws InterruptedException {
                updateProgress(0, 1);
                creep(0, HANDOVER, Math.round(RAMP_MILLIS * HANDOVER));
                engine.loadEventsFile(file.getPath());
                creep(HANDOVER, 1, Math.round(RAMP_MILLIS * (1 - HANDOVER)));
                return null;
            }

            /**
             * Walks progress from one fraction to another over {@code millis}, in steps
             * small enough that the bar moves rather than jumps.
             *
             * <p>This is the deliberate delay. Without it the whole thing is over before
             * the strip has finished appearing, and a load that reports nothing looks the
             * same as a load that failed.
             */
            private void creep(double from, double to, long millis) throws InterruptedException {
                int steps = 32;
                for (int step = 1; step <= steps; step++) {
                    Thread.sleep(millis / steps);
                    updateProgress(from + (to - from) * step / steps, 1);
                }
            }
        };

        loading.progressProperty().bind(load.progressProperty());
        percent.textProperty().bind(load.progressProperty().map(DesktopApp::asPercent));

        load.setOnSucceeded(done -> {
            loadedFile = file.getPath();
            showLoading(false);
            live.reset();       // a new market, not a change to the old one
            refresh();
            flashLoaded();
            report("Loaded " + file.getName() + ".", false);
        });
        load.setOnFailed(done -> {
            showLoading(false);
            Throwable cause = load.getException();
            report(cause == null || cause.getMessage() == null ? "Could not read that file."
                    : cause.getMessage(), true);
            refresh();
        });

        showLoading(true);
        report("Reading " + file.getName() + "…", false);
        Thread worker = new Thread(load, "guessmarket-load");
        worker.setDaemon(true);
        worker.start();
    }

    /** A task's progress as the design writes it. Before it starts, that is 0 and not −100. */
    private static String asPercent(Number progress) {
        double fraction = progress == null ? 0 : progress.doubleValue();
        return Math.round(Math.max(0, fraction) * 100) + "%";
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
        fileState.setText(busy ? "LOADING" : (loadedFile == null ? "NO FILE LOADED" : "LOADED"));
    }

    /** The design's green tick, for as long as it takes to notice it. */
    private void flashLoaded() {
        loadedMark.setVisible(true);
        loadedMark.setManaged(true);
        fileState.setText("LOADED " + engine.getEvents().size() + " EVENTS · "
                + engine.getUsers().size() + " USERS");
        fileState.getStyleClass().add("up");

        PauseTransition settle = new PauseTransition(Duration.seconds(4));
        settle.setOnFinished(done -> {
            loadedMark.setVisible(false);
            loadedMark.setManaged(false);
            fileState.getStyleClass().remove("up");
            fileState.setText("LOADED");
        });
        settle.play();
    }

    private void cycleTheme() {
        wearTheme(theme.next());
    }

    /**
     * Dresses the window in one theme: the toggle's next look, or the one a restored
     * session was saved wearing.
     */
    private void wearTheme(Theme wanted) {
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
    void perform(Supplier<String> action) {
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

    /**
     * Opens the create-event form, and issues what it hands back.
     *
     * <p>The dialog returns a command rather than a description of an event: it knows how to
     * read a form, and the engine knows what an event may be. So a rejected event reports
     * itself through {@link #perform} in the status bar, exactly like a rejected purchase,
     * and this method contains no rules at all.
     */
    void createEvent() {
        String creator = engine.getCurrentUserName();
        if (creator == null) {
            report("Select a user on the Users tab first: whoever creates an event runs it.", true);
            return;
        }
        CreateEventDialog dialog = new CreateEventDialog(creator);
        dialog.initOwner(stage);
        theme.applyTo(dialog.getDialogPane());
        dialog.showAndWait().ifPresent(command -> perform(() -> command.apply(engine)));
    }

    /** Asks which option won, then settles the event on it. */
    void closeEvent(EventView event) {
        ChoiceDialog<String> dialog = new ChoiceDialog<>(event.optionNames().get(0), event.optionNames());
        dialog.setTitle("Close event " + event.id());
        dialog.setHeaderText("Which option won?");
        dialog.setContentText("Winning option");
        dialog.initOwner(stage);
        theme.applyTo(dialog.getDialogPane());

        Optional<String> winner = dialog.showAndWait();
        if (winner.isEmpty()) {
            return;
        }

        int winningIndex = event.optionNames().indexOf(winner.get());   // the engine counts from 0
        perform(() -> {
            SettlementResult result = engine.closeEvent(event.id(), winningIndex);
            String returned = result.subsidyReturned() > 0
                    ? ", " + Widgets.money(result.subsidyReturned()) + " back to the market maker"
                    : "";
            return String.format("Event %d closed on %s: %s paid out, %s commission%s.",
                    result.eventId(), result.winningOptionName(),
                    Widgets.money(result.totalPaidToWinners()), Widgets.money(result.commissionMoved()),
                    returned);
        });
    }

    /** Rereads everything on screen from the engine. Cheap, and the only way state gets stale. */
    private void refresh() {
        if (engine.isFileLoaded()) {
            live.sync(engine);
        }
        eventsScreen.refresh();
        usersScreen.refresh();
        filePath.setText(loadedFile == null ? "" : loadedFile);
        String user = engine.isFileLoaded() ? engine.getCurrentUserName() : null;
        actingAs.setText(!engine.isFileLoaded() ? ""
                : user == null ? "Nobody selected. Pick one on the Users tab"
                : "Acting as " + user);
        if (!loading.isVisible() && !fileState.getStyleClass().contains("up")) {
            fileState.setText(engine.isFileLoaded() ? "LOADED" : "NO FILE LOADED");
        }
    }

    void report(String message, boolean isError) {
        status.setText(message);
        status.getStyleClass().removeAll("down", "muted");
        status.getStyleClass().add(isError ? "down" : "muted");
    }

    private Optional<File> chooseFile(String title, String description, String pattern, boolean forSaving) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, pattern));
        return Optional.ofNullable(forSaving ? chooser.showSaveDialog(stage) : chooser.showOpenDialog(stage));
    }

    // --- reading what was typed ---

    /**
     * Turns a typed figure into a number, the way {@link ui.console.InputReader} does for the
     * console: the message says what was expected, and the caller's {@link #perform} shows it.
     */
    static long readPositiveLong(String text, String what) {
        try {
            long value = Long.parseLong(text.trim().replace(",", ""));
            if (value <= 0) {
                throw new IllegalArgumentException("The number of " + what + " must be greater than 0.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a whole number of " + what + ".");
        }
    }

    /**
     * A whole number with a floor rather than a strictly positive one: a commission of 0 and
     * an order book opened with no initial investment are both legal, so
     * {@link #readPositiveLong} would refuse figures the engine accepts.
     */
    static int readWholeNumber(String text, String what, int least) {
        try {
            int value = Integer.parseInt(text.trim().replace(",", ""));
            if (value < least) {
                throw new IllegalArgumentException(
                        "The " + what + " cannot be less than " + least + ".");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a whole " + what + ".");
        }
    }

    static double readPositiveDouble(String text, String what) {
        try {
            double value = Double.parseDouble(text.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("The " + what + " must be greater than 0.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text.trim() + "' is not a " + what + ".");
        }
    }
}
