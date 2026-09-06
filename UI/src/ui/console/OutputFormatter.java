package ui.console;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OptionView;
import engine.dto.HoldingView;
import engine.dto.PurchaseResult;
import engine.dto.SettlementResult;
import engine.dto.UserView;
import engine.model.Trade;                     //immutable

import java.util.List;

/**
 * The only class in the application that writes to the console.
 *
 * <p>This is also the only place values get rounded: the engine carries full
 * precision end to end, and {@code %.2f} is applied here at the moment of printing.
 */
public class OutputFormatter {

    private static final String PRICE = "%.2f";

    /** @param currentUser the name being acted as, or {@code null} if none has been chosen */
    public void printMenu(String currentUser) {
        System.out.println("===== GuessMarket =====");
        System.out.println("Acting as: " + (currentUser == null ? "(nobody yet)" : currentUser));
        System.out.println("1. Load events file");
        System.out.println("2. Select user");
        System.out.println("3. Show all events");
        System.out.println("4. Show event status");
        System.out.println("5. Participate in an event");
        System.out.println("6. My account");
        System.out.println("7. Close an event");
        System.out.println("8. Save the current session to a file");
        System.out.println("9. Load a saved session");
        System.out.println("10. Exit");
    }

    public void printUsers(List<UserView> users) {
        System.out.println();
        System.out.println("--- Users ---");
        for (int i = 0; i < users.size(); i++) {
            UserView user = users.get(i);
            System.out.printf("  %d) %-12s cash " + PRICE, i + 1, user.name(), user.balance());
            if (!user.marketMakerEventIds().isEmpty()) {
                System.out.print("   market maker for events " + user.marketMakerEventIds());
            }
            System.out.println();
        }
        System.out.println();
    }

    public void printAccount(UserView user) {
        System.out.println();
        System.out.printf("--- Account: %s ---%n", user.name());
        System.out.printf("  Balance:   " + PRICE + "%n", user.balance());
        if (user.reservedCash() > 0) {
            System.out.printf("  Reserved:  " + PRICE + "   (promised to resting buy orders)%n",
                    user.reservedCash());
            System.out.printf("  Available: " + PRICE + "%n", user.availableCash());
        }
        if (!user.marketMakerEventIds().isEmpty()) {
            System.out.println("  Market maker for events: " + user.marketMakerEventIds());
        }

        System.out.println("  Holdings:");
        if (user.holdings().isEmpty()) {
            System.out.println("    (no shares held)");
            return;
        }
        for (HoldingView holding : user.holdings()) {
            System.out.printf("    [%d] %s%n", holding.eventId(), holding.eventName());
            for (int i = 0; i < holding.optionNames().size(); i++) {
                System.out.printf("      %-20s %d shares", holding.optionNames().get(i), holding.shares()[i]);
                if (holding.lockedShares()[i] > 0) {
                    System.out.printf("   (%d offered for sale)", holding.lockedShares()[i]);
                }
                System.out.println();
            }
        }
    }

    public void printEvents(List<EventView> events) {
        System.out.println();
        System.out.println("--- Events ---");
        for (EventView event : events) {
            System.out.printf("[%d] %s (%s)%n", event.id(), event.name(), event.status());
            System.out.println("    " + event.description());
            System.out.printf("    Commission: " + PRICE + "%%  (%s)   Trading: %s%n",
                    event.commissionRate() * 100, event.commissionMethod(), event.tradingMethod());
            if (event.marketMaker() != null) {
                System.out.println("    Market maker: " + event.marketMaker());
            }
            List<String> names = event.optionNames();
            for (int i = 0; i < names.size(); i++) {
                System.out.printf("    Option %d: %s%n", i + 1, names.get(i));   // 1-based for the user
            }
        }
    }

    public void printEventStatus(EventStatusView status) {
        System.out.println();
        System.out.printf("--- Event %d: %s ---%n", status.eventId(), status.name());

        List<OptionView> options = status.options();
        for (int i = 0; i < options.size(); i++) {
            OptionView option = options.get(i);
            System.out.printf("  %d) %-20s price " + PRICE + "   shares %d%n",
                    i + 1, option.name(), option.currentPrice(), option.totalShares());
        }

        System.out.printf("  Account balance:      " + PRICE + "%n", status.accountBalance());
        System.out.printf("  Commission collected: " + PRICE + "%n", status.commissionCollected());

        if (status.closed()) {
            System.out.println("  Status: CLOSED, winner: " + status.winningOptionName());
            long[] finalShares = status.finalSharesPerOption();
            if (finalShares != null) {
                for (int i = 0; i < finalShares.length; i++) {
                    System.out.printf("    Final shares, option %d: %d%n", i + 1, finalShares[i]);
                }
            }
        } else {
            System.out.println("  Status: ACTIVE");
        }

        printHistory(status.history());
    }

    private void printHistory(List<Trade> history) {
        System.out.println("  Purchase history (newest first):");
        if (history.isEmpty()) {
            System.out.println("    (no purchases yet)");
            return;
        }
        for (Trade trade : history) {
            System.out.printf("    %d shares of %-20s paid " + PRICE + "%n",
                    trade.shares(), trade.optionName(), trade.totalPaid());
        }
    }

    public void printPurchaseResult(PurchaseResult result) {
        System.out.println();
        System.out.printf("Bought %d shares of %s.%n", result.sharesBought(), result.optionName());
        System.out.printf("  Shares cost: " + PRICE + "%n", result.sharesCost());
        System.out.printf("  Commission:  " + PRICE + "%n", result.commission());
        System.out.printf("  Total paid:  " + PRICE + "%n", result.totalPaid());
        printEventStatus(result.updatedStatus());
    }

    public void printSettlement(SettlementResult result) {
        System.out.println();
        System.out.printf("Event %d closed. Winning option: %s%n",
                result.eventId(), result.winningOptionName());
        long[] shares = result.sharesPerOption();
        for (int i = 0; i < shares.length; i++) {
            System.out.printf("  Shares held on option %d: %d%n", i + 1, shares[i]);
        }
        if (result.commissionMoved() > 0) {
            // Show the deduction, so it's clear where the winners' money went.
            System.out.printf("  Gross winnings:    " + PRICE + "%n",
                    result.totalPaidToWinners() + result.commissionMoved());
            System.out.printf("  Commission taken:  " + PRICE + "%n", result.commissionMoved());
        }
        System.out.printf("  Paid to winners:   " + PRICE + "%n", result.totalPaidToWinners());
        if (result.subsidyReturned() > 0) {
            // The unspent subsidy going home, so the account visibly ends at nothing.
            System.out.printf("  Returned to maker: " + PRICE + "%n", result.subsidyReturned());
        }
    }

    public void printError(String message) {
        System.out.println();
        System.out.println(message);
        System.out.println();
    }

    public void printMessage(String message) {
        System.out.println(message);
    }
}
