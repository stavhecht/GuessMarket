package ui.desktop;

import engine.dto.EventView;
import engine.dto.SettlementResult;
import engine.exception.EngineException;
import engine.service.MarketEngine;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The desktop front end: an admin view onto one loaded market, in the shape the design
 * lays out — a title strip, a file bar, two tabs, and a status line.
 *
 * <p>The two tabs are the two screens: {@link EventsScreen} is every event and the market
 * of whichever one is selected, {@link UsersScreen} is every user and the account of
 * whichever one is selected. Both drive the same engine and both are redrawn by
 * {@link #refresh()}, so a trade made on one is visible on the other.
 *
 * <p>Like {@link ui.console.ConsoleApp}, this class never reaches past {@link MarketEngine}:
 * it reads DTOs and calls commands. It owns the same two conversions the console owns —
 * the option numbers a person sees against the 0-based indices the engine uses, and the
 * moment at which a figure gets rounded for display.
 *
 * <p>Every engine call goes through {@link #perform}, so a rejected command paints its
 * reason in the status bar and leaves the window as it was, in the same spirit as
 * {@code ConsoleApp.dispatch} catching {@code EngineException} in exactly one place.
 * Don't add try/catch to the individual handlers.
 */
public class DesktopApp extends Application {

    private static MarketEngine sharedEngine;

    private MarketEngine engine;

    // Built in start() rather than here: a JavaFX control cannot be created until the
    // toolkit is up, and Main constructs this class before ever calling run().
    private Stage stage;
    private Scene scene;
    private Theme theme = Theme.LIGHT;

    private Button themeToggle;
    private final List<Circle> tinted = new ArrayList<>();
    private Label fileState;
    private Label filePath;
    private Circle loadedMark;
    private ProgressBar loading;
    private Label percent;
    private Label status;
    private Label actingAs;

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

        eventsScreen = new EventsScreen(this);
        usersScreen = new UsersScreen(this);
        status = Widgets.muted("Load an events file to begin.");
        actingAs = Widgets.note("");

        Tab events = new Tab("Events", workspace(eventsScreen));
        Tab users = new Tab("Users", workspace(usersScreen));
        events.setClosable(false);
        users.setClosable(false);

        TabPane tabs = new TabPane(events, users);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> refresh());

        // The mocked-up title strip is gone — the real window already has one — so the
        // file bar is the top of the app, and the theme toggle rides along on its right.
        BorderPane root = new BorderPane();
        root.setTop(fileBar());
        root.setCenter(tabs);
        root.setBottom(statusBar());

        scene = new Scene(root, 1440, 940);
        theme.applyTo(scene);
        repaintShapes();

        stage.setScene(scene);
        stage.setTitle("Guess Market");
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.show();

        refresh();
        if (engine.isFileLoaded()) {
            report(engine.getEvents().size() + " events loaded.", false);
        }
    }

    /**
     * The file strip: one primary action, one read-only field saying what is loaded, and
     * the two session commands. The field is the design's three states in one place —
     * nothing loaded, loading, and a flash of green when a file has just come in.
     */
    private HBox fileBar() {
        Button loadFile = Widgets.button("Load File", "primary");
        loadFile.setOnAction(action -> chooseFile("Load events file", "XML files", "*.xml", false)
                .ifPresent(this::loadInBackground));

        loadedMark = new Circle(8);
        loadedMark.getProperties().put("token", "up");
        tinted.add(loadedMark);
        loadedMark.setVisible(false);
        loadedMark.setManaged(false);

        fileState = Widgets.tiny("no file loaded");
        filePath = Widgets.label("", "mono", "faint");
        HBox.setHgrow(filePath, Priority.ALWAYS);
        filePath.setMaxWidth(Double.MAX_VALUE);

        loading = new ProgressBar(0);
        loading.setVisible(false);
        loading.setManaged(false);
        loading.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(loading, Priority.ALWAYS);

        // Fixed width, right-aligned, as the design asks: the digits must not shuffle the
        // bar sideways as the count goes 9% → 10% → 100%.
        percent = Widgets.label("0%", "pct");
        percent.setMinWidth(38);
        percent.setPrefWidth(38);
        percent.setAlignment(Pos.CENTER_RIGHT);
        percent.setVisible(false);
        percent.setManaged(false);

        HBox field = Widgets.row(8, loadedMark, fileState, filePath, loading, percent);
        field.getStyleClass().add("sunken");
        field.setPadding(Widgets.pad(0, 10, 0, 10));
        field.setMinHeight(30);
        field.setPrefHeight(30);
        field.setMinWidth(0);
        HBox.setHgrow(field, Priority.ALWAYS);

        Button saveSession = Widgets.button("Save session");
        saveSession.setOnAction(action -> chooseFile("Save session", "GuessMarket sessions", "*.gm", true)
                .ifPresent(file -> perform(() -> "Session saved to " + engine.saveState(file.getPath()) + ".")));

        themeToggle = Widgets.button(theme.label(), "tiny");
        themeToggle.setOnAction(action -> cycleTheme());

        Button loadSession = Widgets.button("Load session");
        loadSession.setOnAction(action -> chooseFile("Load session", "GuessMarket sessions", "*.gm", false)
                .ifPresent(file -> perform(() -> {
                    loadedFile = engine.loadState(file.getPath());
                    live.reset();   // the balances behind a restored session are not this run's
                    return "Session loaded from " + loadedFile + ".";
                })));

        whileIdle.addAll(List.of(loadFile, saveSession, loadSession));

        HBox bar = Widgets.row(10, loadFile, field, saveSession, loadSession, themeToggle);
        bar.getStyleClass().add("toolstrip");
        return bar;
    }

    private static Region workspace(javafx.scene.Node content) {
        StackPane pane = new StackPane(content);
        pane.getStyleClass().add("workspace");
        return pane;
    }

    /**
     * The status line, plus who commands are being issued as.
     *
     * <p>The design puts the choice of user on the Users screen and nowhere else, which
     * leaves the Events screen able to buy without saying on whose behalf — so the answer
     * sits here, visible from both.
     */
    private HBox statusBar() {
        HBox bar = Widgets.row(0, status, Widgets.grower(), actingAs);
        bar.getStyleClass().add("statusbar");
        return bar;
    }

    // --- loading ---

    /**
     * How long the bar takes to cross the strip when the file itself takes no time at all.
     *
     * <p>Reading one of these files is over in a few milliseconds, which on screen is a
     * flicker and nothing else — so the two ramps below are stretched over this instead.
     * A file that really does take longer is not padded past its own time: the bar simply
     * rests at {@link #HANDOVER} while the engine works, and finishes when it is done.
     */
    private static final long RAMP_MILLIS = 1600;

    /** The fraction the bar has reached by the time the engine is actually called. */
    private static final double HANDOVER = 0.4;

    /**
     * Reads an events file off the FX thread, so a big one cannot freeze the window while
     * JAXB works through it. Nothing on screen is redrawn until it has succeeded — the
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
        theme = theme.next();
        theme.applyTo(scene);
        themeToggle.setText(theme.label());
        repaintShapes();
        // The few things drawn rather than styled — depth bars, the chart, the title dots —
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
     * halfway is exactly the case where the screen must not be trusted — though the engine
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
            return String.format("Event %d closed on %s: %s paid out, %s commission.",
                    result.eventId(), result.winningOptionName(),
                    Widgets.money(result.totalPaidToWinners()), Widgets.money(result.commissionMoved()));
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
                : user == null ? "Nobody selected — pick one on the Users tab"
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
