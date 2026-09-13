package ui.desktop;

import engine.service.MarketEngine;

import java.util.List;

/**
 * The desktop front end of exercise 2: an admin view onto one loaded market, with the
 * engine in the same process.
 *
 * <p>The window itself is {@link Shell}. What this class adds is what only a local engine
 * can do: load an events file from a path, replacing the market, and save the whole session
 * to a {@code .gm} file and open it again, with the look the window was wearing riding
 * along. The {@code fx:controller} attribute of {@code DesktopApp.fxml} names this class,
 * which is where Scene Builder reads the {@code fx:id}s from; the fields they fill are the
 * shell's, and the client window uses the same file.
 */
public class DesktopApp extends Shell {

    private String loadedFile;

    /** Required by JavaFX, which instantiates this class reflectively. */
    public DesktopApp() {
    }

    /** Used by {@code Main}: the engine to drive, handed on to the instance JavaFX creates. */
    public DesktopApp(MarketEngine engine) {
        super(engine);
    }

    /** The engine itself rather than the {@link engine.service.Market} face of it: the session commands need it. */
    @Override
    public MarketEngine engine() {
        return (MarketEngine) super.engine();
    }

    @Override
    protected boolean singleUser() {
        return false;   // an admin console: anyone in the file may be acted as
    }

    /**
     * Puts the behaviour on the file strip the layout file drew: one primary action, one
     * read-only field saying what is loaded, and the two session commands. The field is the
     * design's three states in one place (nothing loaded, loading, and a flash of green
     * when a file has just come in) and the FXML leaves it in the first of them, with the
     * bar and the percentage hidden and unmanaged.
     */
    @Override
    protected void wireFileBar() {
        loadFile.setOnAction(action -> chooseFile("Load events file", "XML files", "*.xml", false)
                .ifPresent(file -> runWithProgress("Reading", file, () -> {
                    engine().loadEventsFile(file.getPath());
                    loadedFile = file.getPath();
                    return "Loaded " + file.getName() + ".";
                })));

        // The look rides along in the session file, so reopening it reopens the window.
        saveSession.setOnAction(action -> chooseFile("Save session", "GuessMarket sessions", "*.gm", true)
                .ifPresent(file -> perform(() ->
                        "Session saved to " + engine().saveState(file.getPath(), uiState()) + ".")));

        loadSession.setOnAction(action -> chooseFile("Load session", "GuessMarket sessions", "*.gm", false)
                .ifPresent(file -> perform(() -> {
                    loadedFile = engine().loadState(file.getPath());
                    live().reset();   // the balances behind a restored session are not this run's
                    wearUiState(engine().getRestoredUiState());
                    return "Session loaded from " + loadedFile + ".";
                })));

        whileIdle.addAll(List.of(loadFile, saveSession, loadSession));
    }

    @Override
    protected String actingAsText() {
        String user = engine().getCurrentUserName();
        return user == null ? "Nobody selected. Pick one on the Users tab" : "Acting as " + user;
    }

    @Override
    protected String fileStateText() {
        return engine().isFileLoaded() ? "LOADED" : "NO FILE LOADED";
    }

    @Override
    protected void refreshBar() {
        filePath.setText(loadedFile == null ? "" : loadedFile);
    }

    @Override
    protected void opened() {
        refresh();
        if (engine().isFileLoaded()) {
            report(engine().getEvents().size() + " events loaded.", false);
        }
    }

    // --- what a session carries about the window ---

    /**
     * How the window is set up, in the one string a saved session keeps for the front end.
     *
     * <p>The engine writes it and never reads it, which is exactly why it is a
     * {@code String}: {@code engine} cannot be allowed to depend on a {@code ui} type. So
     * the format is this class's own, and it is the theme's name followed by a
     * {@code key=value} for everything else worth reopening a session in.
     *
     * <p><b>The theme comes first and bare</b> because that is the whole of what a session
     * saved before this method existed contains. Read back by {@link #wearUiState}, such a
     * file is a theme and no settings, rather than a name this build fails to recognise,
     * and a session saved here still opens in the right theme in a build that only knows
     * how to read one.
     */
    private String uiState() {
        return theme().name() + FIELD + "animations=" + (animations.isSelected() ? "on" : "off");
    }

    /** What separates the fields of {@link #uiState}. Not a character any field can hold. */
    private static final String FIELD = ";";

    /**
     * Sets the window up the way the session being opened was saved.
     *
     * <p>Tolerant in the same way {@link Theme#named} is, and for the same reason: this
     * string comes out of a file the app cannot vouch for. A field it does not recognise, a
     * value it cannot read, a field that is not there at all: each of those leaves that one
     * part of the window as it is. A session saved by a build with a setting this one does
     * not have is a window set up the old way, not a broken session.
     */
    private void wearUiState(String state) {
        if (state == null) {
            return;     // saved before the front end kept anything at all
        }
        String[] fields = state.split(FIELD);

        Theme saved = Theme.named(fields[0]);
        if (saved != null && saved != theme()) {
            wearTheme(saved);
        }

        for (String field : fields) {
            if (field.equals("animations=on")) {
                animations.setSelected(true);        // Ticker follows the box, not this
            } else if (field.equals("animations=off")) {
                animations.setSelected(false);
            }
        }
    }
}