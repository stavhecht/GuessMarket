package ui.desktop;

import engine.dto.EventView;
import engine.dto.SettlementResult;
import engine.dto.UserView;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;

import java.util.Optional;

/**
 * The dialogs a window opens on the market: the create-event form, the question of which
 * option won, and the question of how much to load into an account. Written against
 * {@link Host} so that both windows share them.
 */
public final class EventDialogs {

    private EventDialogs() {
    }

    /**
     * Opens the create-event form, and reports what it did.
     *
     * <p>The dialog is handed the market and calls it while it is still on screen, so a
     * refusal is shown on the form itself and the form stays up with everything typed into it
     * intact. It closes only on an event the engine accepted, and what it hands back is the
     * sentence describing that event, put through {@link Host#perform} so it is reported and
     * the window redrawn around it exactly like any other command. This method contains no
     * rules at all.
     */
    public static void create(Host host) {
        String creator = host.engine().getCurrentUserName();
        if (creator == null) {
            host.report("Select a user on the Users tab first: whoever creates an event runs it.", true);
            return;
        }
        CreateEventDialog dialog = new CreateEventDialog(creator, host.engine());
        dialog.initOwner(host.window());
        host.theme().applyTo(dialog.getDialogPane());
        dialog.showAndWait().ifPresent(made -> host.perform(() -> made));
    }

    /** Asks which option won, then settles the event on it. */
    public static void close(Host host, EventView event) {
        ChoiceDialog<String> dialog = new ChoiceDialog<>(event.optionNames().get(0), event.optionNames());
        dialog.setTitle("Close event " + event.id());
        dialog.setHeaderText("Which option won?");
        dialog.setContentText("Winning option");
        dialog.initOwner(host.window());
        host.theme().applyTo(dialog.getDialogPane());

        Optional<String> winner = dialog.showAndWait();
        if (winner.isEmpty()) {
            return;
        }

        int winningIndex = event.optionNames().indexOf(winner.get());   // the engine counts from 0
        host.perform(() -> {
            SettlementResult result = host.engine().closeEvent(event.id(), winningIndex);
            String returned = result.subsidyReturned() > 0
                    ? ", " + Widgets.money(result.subsidyReturned()) + " back to the market maker"
                    : "";
            return String.format("Event %d closed on %s: %s paid out, %s commission%s.",
                    result.eventId(), result.winningOptionName(),
                    Widgets.money(result.totalPaidToWinners()), Widgets.money(result.commissionMoved()),
                    returned);
        });
    }

    /**
     * Asks how much, then loads it into the current user's account.
     *
     * <p>The figure is read inside {@link Host#perform}, the way the trade form reads its
     * quantity, so a sum that is not a number is reported in the status bar like any
     * other refusal rather than being argued about on the dialog.
     */
    public static void loadFunds(Host host) {
        String user = host.engine().getCurrentUserName();
        if (user == null) {
            host.report("Select a user first: funds are loaded into somebody's account.", true);
            return;
        }
        TextInputDialog dialog = new TextInputDialog("100");
        dialog.setTitle("Load funds");
        dialog.setHeaderText("Load funds into " + user + "'s account");
        dialog.setContentText("Amount");
        dialog.initOwner(host.window());
        host.theme().applyTo(dialog.getDialogPane());

        Optional<String> typed = dialog.showAndWait();
        if (typed.isEmpty()) {
            return;
        }
        host.perform(() -> {
            double amount = Shell.readPositiveDouble(typed.get(), "amount");
            UserView account = host.engine().loadFunds(amount);
            return String.format("Loaded %s into %s's account; the balance is now %s.",
                    Widgets.money(amount), account.name(), Widgets.money(account.balance()));
        });
    }
}