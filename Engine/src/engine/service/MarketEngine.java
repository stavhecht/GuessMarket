package engine.service;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OptionView;
import engine.dto.PurchaseResult;
import engine.dto.SettlementResult;
import engine.dto.TradeView;
import engine.exception.EventClosedException;
import engine.exception.InvalidOptionException;
import engine.exception.InvalidShareAmountException;
import engine.exception.NoFileLoadedException;
import engine.model.Account;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Option;
import engine.model.Trade;

import java.util.ArrayList;
import java.util.List;

/**
 * The facade — the only engine class the UI imports.
 *
 * <p>Two rules hold throughout: every DTO is built here (the UI never constructs one),
 * and no value is ever rounded (formatting is the UI's job, so the account identity
 * {@code balance == subsidy + Σ costs − payouts} stays exact).
 */
public class MarketEngine {

    private final EventManager eventManager = new EventManager();
    private final LmsrCalculator lmsr = new LmsrCalculator();
    private final XmlEventLoader loader = new XmlEventLoader();
    private boolean fileLoaded;

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

    // --- internals ---

    private EventStatusView buildStatusView(Event event) {
        long q0 = event.getOption(0).getShares();
        long q1 = event.getOption(1).getShares();
        double[] prices = lmsr.prices(q0, q1, event.getB());

        List<OptionView> options = List.of(
                new OptionView(event.getOption(0).getName(), prices[0], q0),
                new OptionView(event.getOption(1).getName(), prices[1], q1));

        List<Trade> trades = event.getTrades();
        List<TradeView> history = new ArrayList<>(trades.size());
        for (int i = trades.size() - 1; i >= 0; i--) {   // newest first
            Trade trade = trades.get(i);
            history.add(new TradeView(trade.optionName(), trade.shares(), trade.totalPaid()));
        }

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
