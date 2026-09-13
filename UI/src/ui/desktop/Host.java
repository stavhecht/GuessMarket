package ui.desktop;

import engine.dto.EventView;
import engine.service.Market;
import javafx.stage.Window;

import java.util.function.Supplier;

/**
 * What a screen needs from the window it sits in.
 *
 * <p>{@link EventsScreen}, {@link UsersScreen} and the panes inside them are written once
 * and run in two windows: the exercise-2 admin app, whose engine is in the same process,
 * and the exercise-3 client, whose engine is on a server. Neither screen knows which; it
 * asks its host for the {@link Market} to read and command, the {@link LiveMarket} its
 * charts follow, and the few things only a window can do: run a command and report how it
 * went, open the two dialogs, own a dialog, say what theme to dress one in.
 *
 * <p>{@link Shell} implements all of it over {@code DesktopApp.fxml}; the two apps extend
 * that and differ only in where their market comes from.
 */
public interface Host {

    /** The market every read and every command goes to. */
    Market engine();

    /** The observable figures the charts and tickers follow. */
    LiveMarket live();

    /**
     * Runs one command and reports it: the sentence it returns on success, its message on
     * a refusal. Whatever happens the window is redrawn afterwards.
     */
    void perform(Supplier<String> action);

    /** Writes one line to the status bar. */
    void report(String message, boolean isError);

    /** Opens the create-event form. */
    void createEvent();

    /** Asks which option won, then settles {@code event} on it. */
    void closeEvent(EventView event);

    /** The window, for a dialog to be owned by. */
    Window window();

    /** The look the window is wearing, for a dialog to wear too. */
    Theme theme();
}