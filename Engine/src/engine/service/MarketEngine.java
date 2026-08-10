package engine.service;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OptionView;
import engine.dto.PurchaseResult;
import engine.dto.SettlementResult;
import engine.exception.EventClosedException;
import engine.exception.InvalidOptionException;
import engine.exception.InvalidShareAmountException;
import engine.exception.NoFileLoadedException;
import engine.exception.StateFileException;
import engine.model.Account;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Option;
import engine.model.Trade;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The front — the only engine class the UI imports.
 *
 * <p>Two rules hold throughout: every DTO is built here (the UI never constructs one),
 * and no value is ever rounded (formatting is the UI's job, so the account identity
 * {@code balance == subsidy + Σ costs − payouts} stays exact).
 */
public class MarketEngine {

    /** Appended to the path the user gives, so they never have to type an extension. */
    public static final String STATE_EXTENSION = ".gm";

    /** Not final: loading a saved session replaces the whole collection at once. */
    private EventManager eventManager = new EventManager();
    private final LmsrCalculator lmsr = new LmsrCalculator();
    private final XmlEventLoader loader = new XmlEventLoader();
    private boolean fileLoaded =  false;

    /**
     * Loads a new events file, replacing everything currently in memory.
     * If the file is rejected, the previously loaded state is left untouched.
     */
    public void loadEventsFile(String path) {
        List<Event> events = loader.load(path);
        eventManager.loadEvents(events);
        eventManager.applyInitialSubsidies(lmsr);
        fileLoaded = true;
    }

    /**
     * Writes the current session — every event with its prices, balances and trade
     * history — to {@code path} plus {@value #STATE_EXTENSION}.
     *
     * <p>{@link EventManager} holds all of it and is {@link java.io.Serializable}, so one
     * {@code writeObject} captures the lot. The user supplies the path without an
     * extension, as the brief asks; typing one anyway is tolerated rather than doubled.
     *
     * @return the path of the file actually written, for the UI to report
     */
    public String saveState(String path) {
        File file = stateFile(path);
        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(file))) {
            stream.writeObject(eventManager);
            return file.getPath();
        } catch (FileNotFoundException e) {
            // What this really means here: the folder is missing, or is not writable.
            throw new StateFileException("Could not write " + file.getPath()
                    + ". Check that the folder exists and can be written to.", e);
        } catch (IOException e) {
            throw new StateFileException("Could not save to " + file.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Restores a session previously written by {@link #saveState}, replacing whatever is
     * loaded now. An unreadable file leaves the current session untouched, because the
     * field is only reassigned once the read has succeeded.
     *
     * @return the path of the file actually read, for the UI to report
     */
    public String loadState(String path) {
        File file = stateFile(path);
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file))) {
            eventManager = (EventManager) stream.readObject();
            fileLoaded = true;
            return file.getPath();
        } catch (FileNotFoundException e) {
            throw new StateFileException("There is no saved session at " + file.getPath() + ".", e);
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            throw new StateFileException("Could not load a saved session from " + file.getPath()
                    + ": " + e.getMessage(), e);
        }
    }

    private File stateFile(String path) {
        String trimmed = path.trim();
        return new File(trimmed.endsWith(STATE_EXTENSION) ? trimmed : trimmed + STATE_EXTENSION);
    }

    /**
     * Whether a file has been loaded successfully.
     *
     * <p>Lets the UI turn a command away before it prompts for anything, rather than
     * collecting an event id and a share count only to fail on the engine call. The
     * commands below still check for themselves — they can't trust a caller to have asked.
     */
    public boolean isFileLoaded() {
        return fileLoaded;
    }

    public List<EventView> getEvents() {
        requireFileLoaded();
        List<EventView> views = new ArrayList<>();
        for (Event event : eventManager.getAllEvents()) {
            views.add(new EventView(
                    event.getId(),
                    event.getName(),
                    event.getDescription(),
                    event.getCommissionRate(),
                    event.getCommissionMethod().name(),
                    List.of(event.getOption(0).getName(), event.getOption(1).getName()),
                    event.getStatus().name()));
        }
        return views;
    }

    public EventStatusView getEventStatus(int eventId) {
        requireFileLoaded();
        return buildStatusView(eventManager.getEvent(eventId));
    }

    /**
     * Buys {@code shares} of one outcome at the LMSR price.
     *
     * <p>Everything is validated and every figure computed before the first mutation,
     * so a rejected purchase can't leave shares issued against an untouched account.
     */
    public PurchaseResult participate(int eventId, int optionIndex, long shares) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);

        if (!event.isActive()) {
            throw new EventClosedException("Event " + eventId + " is already closed — no more purchases.");
        }
        validateOptionIndex(optionIndex);
        if (shares <= 0) {
            throw new InvalidShareAmountException("Number of shares must be a positive whole number.");
        }

        long q0 = event.getOption(0).getShares();
        long q1 = event.getOption(1).getShares();

        double sharesCost = lmsr.purchaseCost(q0, q1, optionIndex, shares, event.getB());
        double commission = event.getCommissionMethod() == CommissionMethod.PER_PURCHASE
                ? sharesCost * event.getCommissionRate()
                : 0.0;
        double totalPaid = sharesCost + commission;

        // --- commit ---
        Option bought = event.getOption(optionIndex);
        bought.addShares(shares);
        Account account = event.getMMAccount();
        account.deposit(sharesCost);
        if (commission > 0) {
            account.addCommission(commission);
        }
        event.recordTrade(bought.getName(), shares, sharesCost, commission);

        return new PurchaseResult(bought.getName(), shares, sharesCost, commission, totalPaid,
                buildStatusView(event));
    }

    /**
     * Settles an event. Winners are owed one currency unit per share; under
     * {@code ON_CLOSE} the operator's commission is deducted from that payout, so only
     * winning participants ever pay it.
     *
     * <p>Funding the commission out of the winnings rather than out of the account is
     * what makes it always affordable: the account still parts with exactly the gross
     * obligation it was subsidised for, so the b·ln2 solvency guarantee is untouched no
     * matter how one-sided the market got.
     */
    public SettlementResult closeEvent(int eventId, int winningOptionIndex) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);

        if (!event.isActive()) {
            throw new EventClosedException("Event " + eventId + " has already been closed.");
        }
        validateOptionIndex(winningOptionIndex);

        Account account = event.getMMAccount();
        long[] sharesPerOption = {
                event.getOption(0).getShares(),
                event.getOption(1).getShares()
        };

        // The full obligation — one unit per winning share — leaves the account either way.
        double grossWinnings = sharesPerOption[winningOptionIndex];
        account.withdraw(grossWinnings);

        // Under ON_CLOSE the operator's share is carved out of that, leaving the rest
        // for the winners. No cap is needed: the money is already owed to someone.
        double commissionMoved = event.getCommissionMethod() == CommissionMethod.ON_CLOSE
                ? grossWinnings * event.getCommissionRate()
                : 0.0;
        if (commissionMoved > 0) {
            account.addCommission(commissionMoved);
        }
        double totalPaidToWinners = grossWinnings - commissionMoved;

        event.close(winningOptionIndex);

        return new SettlementResult(eventId,
                event.getOption(winningOptionIndex).getName(),
                sharesPerOption,
                commissionMoved,
                totalPaidToWinners);
    }

    // --- internals/private methods ---

    private EventStatusView buildStatusView(Event event) {
        long q0 = event.getOption(0).getShares();
        long q1 = event.getOption(1).getShares();
        double[] prices = lmsr.prices(q0, q1, event.getB());

        List<OptionView> options = List.of(
                new OptionView(event.getOption(0).getName(), prices[0], q0),
                new OptionView(event.getOption(1).getName(), prices[1], q1));

        List<Trade> history = new ArrayList<>(event.getTrades());
        Collections.reverse(history);   // newest first

        boolean closed = !event.isActive();
        Integer winner = event.getWinningOptionIndex();

        return new EventStatusView(
                event.getId(),
                event.getName(),
                options,
                event.getMMAccount().getBalance(),
                event.getMMAccount().getCommissionCollected(),
                List.copyOf(history),
                closed,
                winner == null ? null : event.getOption(winner).getName(),
                closed ? new long[] { q0, q1 } : null);
    }

    private void requireFileLoaded() {
        if (!fileLoaded) {
            throw new NoFileLoadedException("Load an events file first (command 1).");
        }
    }

    private void validateOptionIndex(int optionIndex) {
        if (optionIndex < 0 || optionIndex >= Event.OPTION_COUNT) {
            throw new InvalidOptionException("Option must be 1 or " + Event.OPTION_COUNT + ".");
        }
    }
}
