package ui.desktop;

import engine.dto.EventStatusView;
import engine.dto.OptionView;
import engine.dto.PurchaseQuote;
import engine.dto.PurchaseResult;
import engine.exception.EngineException;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The body of an LMSR event: one card per outcome, showing what the scoring rule quotes,
 * what the next purchase would cost, and a field to make it.
 *
 * <p>There is no selling here and no order to place — the price is a function of how many
 * shares exist and the market maker is always the counterparty, so a card is a price, a
 * quantity and a Buy button.
 *
 * <p>The two figures that answer "what would this cost" come from
 * {@code MarketEngine.quoteParticipation} rather than from anything worked out here: an
 * LMSR cost is a log-sum-exp of the outstanding counts and is not the quoted price times
 * the quantity.
 */
class LmsrPane extends HBox {

    private final DesktopApp app;
    private final Card[] cards = { new Card(0), new Card(1) };

    private int eventId;

    LmsrPane(DesktopApp app) {
        super(12);
        this.app = app;
        getChildren().addAll(cards);
        for (Card card : cards) {
            HBox.setHgrow(card, Priority.ALWAYS);
        }
    }

    /** Redraws both cards for {@code status}. */
    void show(EventStatusView status) {
        this.eventId = status.eventId();
        for (int i = 0; i < cards.length; i++) {
            cards[i].show(status, status.options().get(i), !status.closed());
        }
    }

    /** One outcome: its price, how far it has moved, and the cost of buying more of it. */
    private class Card extends VBox {

        private final int optionIndex;

        private final Label name = Widgets.label("", "opt-name");
        private final Label price = Widgets.label(Widgets.NONE, "opt-price");
        private final Region fill = new Region();
        private final StackPane meter = new StackPane(fill);
        private final Label outstanding = Widgets.label(Widgets.NONE, "kv-val");
        private final Label nextCost = Widgets.label(Widgets.NONE, "kv-val");
        private final Label nextPrice = Widgets.label(Widgets.NONE, "kv-val");
        private final Label costKey = Widgets.label("Cost of next 100", "kv-key");
        private final TextField quantity = new TextField("100");
        private final Button buy = Widgets.button("Buy", "primary", "compact");

        Card(int optionIndex) {
            this.optionIndex = optionIndex;
            getStyleClass().add("opt-card");

            HBox head = Widgets.row(8, name, Widgets.grower(), price);
            head.getStyleClass().add("opt-head");
            head.setAlignment(Pos.BASELINE_LEFT);

            fill.getStyleClass().add("meter-fill");
            fill.setMinHeight(6);
            meter.getStyleClass().add("meter");
            meter.setAlignment(Pos.CENTER_LEFT);

            VBox figures = new VBox(6,
                    Widgets.row(8, Widgets.label("Shares outstanding", "kv-key"),
                            Widgets.grower(), outstanding),
                    Widgets.row(8, costKey, Widgets.grower(), nextCost),
                    Widgets.row(8, Widgets.label("Price after", "kv-key"),
                            Widgets.grower(), nextPrice));

            VBox body = new VBox(10, meter, figures);
            body.getStyleClass().add("opt-body");

            quantity.getStyleClass().add("mini");
            quantity.setPrefWidth(72);
            quantity.setMinWidth(72);
            quantity.textProperty().addListener((observable, was, now) -> requote());

            buy.setOnAction(action -> buy());

            HBox foot = Widgets.row(8, quantity, Widgets.grower(), buy);
            foot.getStyleClass().add("opt-foot");

            getChildren().addAll(head, body, foot);
        }

        void show(EventStatusView status, OptionView option, boolean open) {
            name.setText(option.name());
            price.setText(Widgets.price(option.currentPrice()));
            outstanding.setText(Widgets.shares(option.totalShares()));

            // The meter is the price itself: the two options of an event sum to 1, so the
            // filled part is literally how likely this outcome is being called.
            fill.prefWidthProperty().bind(meter.widthProperty().multiply(option.currentPrice()));
            fill.getStyleClass().removeAll("other");
            if (optionIndex == 1) {
                fill.getStyleClass().add("other");
            }

            quantity.setDisable(!open);
            buy.setDisable(!open);
            requote();
        }

        /** Re-prices the quantity in the box, quietly: a half-typed number is not an error. */
        private void requote() {
            long shares = parsed();
            costKey.setText(shares > 0 ? "Cost of next " + Widgets.shares(shares) : "Cost of next");
            if (shares <= 0 || !app.engine().isFileLoaded()) {
                nextCost.setText(Widgets.NONE);
                nextPrice.setText(Widgets.NONE);
                return;
            }
            try {
                PurchaseQuote quote = app.engine().quoteParticipation(eventId, optionIndex, shares);
                nextCost.setText(Widgets.money(quote.totalCost()));
                nextPrice.setText(Widgets.price(quote.priceAfter()));
            } catch (EngineException e) {
                nextCost.setText(Widgets.NONE);
                nextPrice.setText(Widgets.NONE);
            }
        }

        private long parsed() {
            try {
                return Long.parseLong(quantity.getText().trim().replace(",", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * The card speaks in the option the user can see; the engine counts from 0, and the
         * UI owns that conversion exactly as {@code ConsoleApp} does for the console.
         */
        private void buy() {
            app.perform(() -> {
                long shares = DesktopApp.readPositiveLong(quantity.getText(), "shares");
                PurchaseResult result = app.engine().participate(eventId, optionIndex, shares);
                return String.format("Bought %s of %s for %s (commission %s).",
                        Widgets.shares(result.sharesBought()), result.optionName(),
                        Widgets.money(result.sharesCost()), Widgets.money(result.commission()));
            });
        }
    }
}
