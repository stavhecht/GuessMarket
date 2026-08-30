package ui.desktop;

import engine.dto.EventView;
import engine.dto.OptionBookView;
import engine.dto.OrderBookStatusView;
import engine.dto.OrderLineView;
import engine.dto.OrderResult;
import engine.model.OrderSide;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The body of an order-book event: both options side by side as ladders — the sell queue
 * above, the spread, the buy queue below, and a form under each for taking either side.
 *
 * <p>The two options are shown together because they are only independent up to a point:
 * each has its own queues, but a buyer of one and a buyer of the other can between them
 * cover the base value and mint a new pair, which is a thing you can only see coming by
 * looking at both at once.
 *
 * <p>A row's depth bar is drawn as a region behind the text rather than as a gradient, so
 * it stays proportional as the pane is resized and picks up the theme without a restyle.
 */
class OrderBookPane extends HBox {

    private final DesktopApp app;
    private final Ladder[] ladders = { new Ladder(0), new Ladder(1) };

    private int eventId;
    private double commissionRate;
    private boolean commissionOnPurchase;

    OrderBookPane(DesktopApp app) {
        super(12);
        this.app = app;
        getChildren().addAll(ladders);
        for (Ladder ladder : ladders) {
            HBox.setHgrow(ladder, Priority.ALWAYS);
            ladder.setMinWidth(0);
        }
    }

    void show(EventView event, OrderBookStatusView status) {
        this.eventId = status.eventId();
        this.commissionRate = event.commissionRate();
        this.commissionOnPurchase = "PER_PURCHASE".equals(event.commissionMethod());
        for (int i = 0; i < ladders.length; i++) {
            ladders[i].show(status.options().get(i), !status.closed());
        }
    }

    /** One option's market: its indicators, its two queues, and the form that joins them. */
    private class Ladder extends VBox {

        private final int optionIndex;

        private final Label name = Widgets.label("", "opt-name");
        private final Label price = Widgets.label(Widgets.NONE, "opt-price");

        /** The headline price counts to its new value, so a fill is seen to move it. */
        private final Ticker priceTicker = Ticker.price(price);

        private final Label outstanding = Widgets.faint("");
        private final Label last = Widgets.label(Widgets.NONE, "stat-val");
        private final Label bid = Widgets.label(Widgets.NONE, "stat-val", "up");
        private final Label ask = Widgets.label(Widgets.NONE, "stat-val", "down");
        private final Label mid = Widgets.label(Widgets.NONE, "stat-val");
        private final Label spread = Widgets.label(Widgets.NONE, "stat-val", "muted");

        private final Label askCount = Widgets.label("", "h-tiny");
        private final Label bidCount = Widgets.label("", "h-tiny");
        private final Label spreadValue = Widgets.figure(Widgets.NONE);
        private final VBox askRows = new VBox();
        private final VBox bidRows = new VBox();

        private final TextField quantity = new TextField("100");
        private final TextField limit = new TextField();
        private final Label estimate = Widgets.note("");
        private final Button sell = Widgets.button("Sell", "sell", "compact");
        private final Button buy = Widgets.button("Buy", "buy", "compact");

        Ladder(int optionIndex) {
            this.optionIndex = optionIndex;
            getStyleClass().add("opt-card");

            HBox head = Widgets.row(8, name, Widgets.pill("order book", "plain"),
                    Widgets.grower(), price, outstanding);
            head.getStyleClass().add("opt-head");
            head.setAlignment(Pos.BASELINE_LEFT);

            HBox stats = Widgets.row(0,
                    stat("last", last), stat("bid", bid), stat("ask", ask),
                    stat("mid", mid), stat("spread", spread));
            stats.getStyleClass().add("stat-strip");

            HBox columns = Widgets.row(0, cell(Widgets.tiny("user"), Priority.ALWAYS, 0),
                    cell(Widgets.tiny("shares"), Priority.NEVER, 56),
                    cell(Widgets.tiny("price"), Priority.NEVER, 54));
            columns.getStyleClass().add("ladder-head");

            HBox spreadRow = Widgets.row(0, Widgets.tiny("spread"), Widgets.grower(), spreadValue);
            spreadRow.getStyleClass().add("spread-row");

            quantity.getStyleClass().add("mini");
            quantity.setPrefWidth(64);
            quantity.setMinWidth(64);
            quantity.textProperty().addListener((observable, was, now) -> reprice());

            limit.getStyleClass().add("mini");
            limit.setPromptText("price");
            limit.setPrefWidth(60);
            limit.setMinWidth(60);
            limit.textProperty().addListener((observable, was, now) -> reprice());

            sell.setOnAction(action -> place(OrderSide.SELL));
            buy.setOnAction(action -> place(OrderSide.BUY));

            HBox form = Widgets.row(8, quantity, limit, estimate, Widgets.grower(), sell, buy);
            form.getStyleClass().add("trade-row");

            getChildren().addAll(head, stats, columns,
                    band("sell orders · ask", "band-ask", askCount), askRows,
                    spreadRow,
                    band("buy orders · bid", "band-bid", bidCount), bidRows,
                    form);
        }

        void show(OptionBookView option, boolean open) {
            name.setText(option.name());
            // Pointed at this event's last traded price, which reads null until something
            // trades — the ticker shows that as a dash and lands on the first real price.
            priceTicker.follow(app.live().lastPrice(eventId, optionIndex));
            outstanding.setText(Widgets.shares(option.sharesOutstanding()) + " sh");
            last.setText(Widgets.price(option.lastPrice()));
            bid.setText(Widgets.price(option.bestBid()));
            ask.setText(Widgets.price(option.bestAsk()));
            mid.setText(Widgets.price(option.midPrice()));
            spread.setText(Widgets.price(option.spread()));
            spreadValue.setText(Widgets.price(option.spread()));

            askCount.setText(count(option.asks().size()));
            bidCount.setText(count(option.bids().size()));

            long deepest = 0;
            for (OrderLineView line : option.asks()) {
                deepest = Math.max(deepest, line.remaining());
            }
            for (OrderLineView line : option.bids()) {
                deepest = Math.max(deepest, line.remaining());
            }

            // Asks read downwards to the spread, so the best one sits closest to it — the
            // engine hands them over best first, which is the other way round.
            List<OrderLineView> asks = new ArrayList<>(option.asks());
            java.util.Collections.reverse(asks);
            fill(askRows, asks, deepest, "down", "No sellers.");
            fill(bidRows, option.bids(), deepest, "up", "No buyers.");

            // The design's trade row has no price field, because it assumes you are taking
            // the quote; the engine needs a limit, so the touch price is offered as one.
            if (limit.getText().isBlank()) {
                Double touch = option.bestAsk() != null ? option.bestAsk() : option.lastPrice();
                if (touch != null) {
                    limit.setText(String.format("%.2f", touch));
                }
            }

            quantity.setDisable(!open);
            limit.setDisable(!open);
            sell.setDisable(!open);
            buy.setDisable(!open);
            reprice();
        }

        /** What the order in the boxes would cost, before it meets anybody. */
        private void reprice() {
            long shares = parseLong(quantity.getText());
            Double atPrice = parseDouble(limit.getText());
            if (shares <= 0 || atPrice == null) {
                estimate.setText("");
                return;
            }
            double cost = shares * atPrice;
            double commission = commissionOnPurchase ? cost * commissionRate : 0;
            estimate.setText(commission > 0
                    ? "≈ " + Widgets.money(cost) + " + " + Widgets.money(commission)
                    : "≈ " + Widgets.money(cost));
        }

        private void place(OrderSide side) {
            app.perform(() -> {
                double atPrice = DesktopApp.readPositiveDouble(limit.getText(), "price");
                long shares = DesktopApp.readPositiveLong(quantity.getText(), "shares");
                OrderResult result = app.engine().placeOrder(eventId, optionIndex, side, atPrice, shares);
                return describe(result);
            });
        }

        private void fill(VBox rows, List<OrderLineView> orders, long deepest,
                          String colourClass, String emptyMessage) {
            rows.getChildren().clear();
            if (orders.isEmpty()) {
                rows.getChildren().add(Widgets.label(emptyMessage, "ladder-empty"));
                return;
            }
            for (OrderLineView order : orders) {
                rows.getChildren().add(ladderRow(order, deepest, colourClass));
            }
        }

        private Node ladderRow(OrderLineView order, long deepest, String colourClass) {
            Label who = Widgets.label(order.userName(), "mono", "muted");
            Label shares = Widgets.label(Widgets.shares(order.remaining()), "mono");
            Label at = Widgets.label(String.format("%.2f", order.price()), "mono", "strong", colourClass);

            HBox content = Widgets.row(0,
                    cell(who, Priority.ALWAYS, 0),
                    cell(shares, Priority.NEVER, 56),
                    cell(at, Priority.NEVER, 54));
            content.getStyleClass().add("ladder-row");

            Region bar = new Region();
            bar.setStyle("-fx-background-color: " + Theme.current().token(colourClass + "-bg") + ";");
            bar.setMouseTransparent(true);
            double depth = deepest == 0 ? 0 : (double) order.remaining() / deepest;

            StackPane row = new StackPane(bar, content);
            row.setAlignment(Pos.CENTER_RIGHT);
            bar.maxWidthProperty().bind(row.widthProperty().multiply(depth));
            return row;
        }

        private HBox band(String title, String colourClass, Label count) {
            Label label = Widgets.tiny(title);
            label.getStyleClass().add(colourClass);
            HBox band = Widgets.row(0, label, Widgets.grower(), count);
            band.getStyleClass().add("ladder-band");
            return band;
        }

        private static String count(int orders) {
            return orders == 1 ? "1 ORDER" : orders + " ORDERS";
        }
    }

    // --- shared bits ---

    /** One cell of the three-column ladder grid: shares and price are fixed, the user grows. */
    private static HBox cell(Label label, Priority grow, double width) {
        HBox box = new HBox(label);
        box.setAlignment(width == 0 ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        if (width == 0) {
            HBox.setHgrow(box, grow);
            box.setMinWidth(0);
            label.setMaxWidth(Double.MAX_VALUE);
        } else {
            box.setMinWidth(width);
            box.setPrefWidth(width);
        }
        return box;
    }

    private static VBox stat(String key, Label value) {
        VBox box = new VBox(2, Widgets.tiny(key), value);
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMinWidth(0);
        return box;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Double parseDouble(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Says what actually happened, which is rarely just "order placed". */
    private static String describe(OrderResult result) {
        StringBuilder message = new StringBuilder();
        if (result.filled() > 0) {
            long minted = 0;
            for (var fill : result.fills()) {
                if (fill.kind().equals("MINT")) {
                    minted += fill.quantity();
                }
            }
            message.append(String.format("%s %s of %s",
                    result.side().equals("BUY") ? "Bought" : "Sold",
                    Widgets.shares(result.filled()), result.optionName()));
            if (minted > 0) {
                message.append(String.format(" (%s newly minted)", Widgets.shares(minted)));
            }
            message.append('.');
        }
        if (result.resting() > 0) {
            message.append(message.isEmpty() ? "" : " ");
            message.append(String.format("%s left waiting in the book at %s.",
                    Widgets.shares(result.resting()), Widgets.price(result.price())));
        }
        return message.toString();
    }
}
