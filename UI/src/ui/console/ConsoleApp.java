package ui.console;

import engine.dto.EventStatusView;
import engine.dto.OptionView;
import engine.dto.UserView;
import engine.exception.EngineException;
import engine.service.MarketEngine;
import java.util.List;

/**
 * The menu loop and command dispatch.
 *
 * <p>Every handler catches {@link EngineException} in one place and prints the
 * message — a rejected command returns to the menu, it never ends the session.
 *
 * <p>This class also owns the 1-based ↔ 0-based conversion: the user picks
 * "option 1", the engine receives index 0. Indices stay 0-based everywhere inside.
 */
public class ConsoleApp {

    private static final int MENU_MIN = 1;
    private static final int MENU_MAX = 10;
    private static final int EXIT_CHOICE = 10;

    /** The two commands that bring a file in; everything else needs one already loaded. */
    private static final int LOAD_FILE_CHOICE = 1;
    private static final int LOAD_SESSION_CHOICE = 9;

    private final MarketEngine engine;
    private final InputReader in = new InputReader();
    private final OutputFormatter out = new OutputFormatter();

    public ConsoleApp(MarketEngine engine) {
        this.engine = engine;
    }

    public void run() {
        while (true) {
            out.printMenu(engine.getCurrentUserName());
            int choice = in.readMenuChoice(MENU_MIN, MENU_MAX);
            if (choice == EXIT_CHOICE) {
                out.printMessage("Goodbye, hope to see you soon.");
                return;
            }
            dispatch(choice);
        }
    }

    private void dispatch(int choice) {
        // Every command except the two that bring events in (1 loads an XML file,
        // 9 loads a saved session) needs events, so the check happens once here —
        // and before any handler prompts for anything.
        if (choice != LOAD_FILE_CHOICE && choice != LOAD_SESSION_CHOICE && !engine.isFileLoaded()) {
            out.printError("No events file is loaded yet! Choose 1 to load one first.");
            return;
        }

        try {
            switch (choice) {
                case 1 -> handleLoadFile();
                case 2 -> handleSelectUser();
                case 3 -> handleShowEvents();
                case 4 -> handleEventStatus();
                case 5 -> handleParticipate();
                case 6 -> handleShowAccount();
                case 7 -> handleClose();
                case 8 -> handleSaveSession();
                case 9 -> handleLoadSession();
                default -> out.printError("Unknown command.");
            }
        } catch (EngineException e) {
            out.printError(e.getMessage());
        }
    }

    /**
     * Picks who the console acts as. The users are numbered on screen and chosen by
     * number, so a name with a space in it never has to be typed.
     */
    private void handleSelectUser() {
        List<UserView> users = engine.getUsers();
        out.printUsers(users);

        int number = in.readInt("Pick a user (1-" + users.size() + "): ");
        if (number < 1 || number > users.size()) {
            out.printError("Please enter a number between 1 and " + users.size() + ".");
            return;
        }
        out.printMessage("\nNow acting as " + engine.selectUser(users.get(number - 1).name()).name() + ".");
    }

    private void handleShowAccount() {
        out.printAccount(engine.getAccount());
        in.waitForEnter();
    }

    private void handleLoadFile() {
        String path = in.readLine("Full path to the events XML file: ");
        engine.loadEventsFile(path);
        out.printMessage("\nFile loaded successfully!");
    }

    private void handleSaveSession() {
        String path = in.readLine("Full path to save to, without an extension: ");
        out.printMessage("\nSession saved to " + engine.saveState(path));
    }

    private void handleLoadSession() {
        String path = in.readLine("Full path of the saved session, without an extension: ");
        out.printMessage("\nSession loaded from " + engine.loadState(path));
    }

    private void handleShowEvents() {
        out.printEvents(engine.getEvents());
        in.waitForEnter();
    }

    private void handleEventStatus() {
        int eventId = in.readInt("Event id: ");
        out.printEventStatus(engine.getEventStatus(eventId));
        in.waitForEnter();
    }

    private void handleParticipate() {
        EventStatusView status = readActiveEvent();
        if (status == null) {
            return;
        }
        int optionNumber = readOptionNumber(status.options());
        long shares = in.readPositiveLong("How many shares? ");

        out.printPurchaseResult(engine.participate(status.eventId(), optionNumber - 1, shares));
    }

    private void handleClose() {
        EventStatusView status = readActiveEvent();
        if (status == null) {
            return;
        }
        int optionNumber = readOptionNumber(status.options());

        out.printSettlement(engine.closeEvent(status.eventId(), optionNumber - 1));
    }

    /**
     * Prompts for an event id and shows its state, returning {@code null} if the event
     * is already closed.
     *
     * <p>Failing here rather than at the engine call spares the user from typing an
     * option and a share count that are certain to be rejected. The engine repeats both
     * checks — it can't trust a caller to have made them.
     */
    private EventStatusView readActiveEvent() {
        int eventId = in.readInt("Event id: ");
        EventStatusView status = engine.getEventStatus(eventId);   // throws on unknown id
        if (status.closed()) {
            out.printError("Event " + eventId + " is closed.");
            return null;
        }
        out.printEventStatus(status);
        return status;
    }

    /** Prompts for a 1-based option number; the caller converts to a 0-based index. */
    private int readOptionNumber(List<OptionView> options) {
        while (true) {
            int number = in.readInt("Option number (1-" + options.size() + "): ");
            if (number >= 1 && number <= options.size()) {
                return number;
            }
            out.printError("Please enter a number between 1 and " + options.size());
        }
    }
}
