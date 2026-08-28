package ui.desktop;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OptionBookView;
import engine.dto.OrderBookStatusView;
import engine.dto.PurchaseQuote;
import engine.dto.PurchaseResult;
import engine.dto.OrderResult;
import engine.dto.UserView;
import engine.exception.EngineException;
import engine.model.OrderSide;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen two: every user on the left, one user's account on the right.
 *
 * <p>The account is the whole point of the screen — what they hold, what it is worth now,
 * and what it pays if the sides they are on come in — so the panels descend in that order:
 * the balance, then every position behind it, then the one event a position points at,
 * with a form for adding to it.
 *
 * <p>Picking a user here is the same command the toolbar issues: the screen acts as
 * whoever is selected, so the trade form at the bottom is always that person's.
 *
 * <p>Two of the design's columns are blank for an LMSR event on purpose. An LMSR trade
 * records no buyer, so what a particular user invested in one cannot be recovered from the
 * history, and the screen says {@link Widgets#NONE} rather than guessing.
 */
class UsersScreen extends HBox {

    private static final String ALL = "All";

    private final DesktopApp app;

    // left
    private final Label userCount = Widgets.pill("0", "count");
    private final TableView<UserView> users = Widgets.table("No events file loaded.");
    private final Label totalHeld = Widgets.figure(Widgets.NONE);

    // account header
    private final Label name = Widgets.subject(Widgets.NONE);
    private final Label ownerOf = Widgets.pill("", "mark");
    private final Label summary = Widgets.faint("");
    private final Label balance = Widgets.label(Widgets.NONE, "num-big");
    private final Label potential = Widgets.label(Widgets.NONE, "num-big");
    private final Label potentialDelta = Widgets.label("", "mono");

    // positions
    private final ComboBox<String> roleFilter = new ComboBox<>();
    private final TableView<MarketData.Position> positions = Widgets.table("Holds nothing yet.");
    private final VBox bars = new VBox(8);

    // the balance chart
    private final SparkChart balanceChart = new SparkChart(150);
    private final Label balanceMoves = Widgets.note("");

    /**
     * The chart's subscription to the selected user's balance.
     *
     * <p>Same idea as the price binding on the Events screen: the line is redrawn because
     * the account moved, not because something happened to call {@link #refresh()}. The
     * property belongs to whoever is selected, so it is swapped over in
     * {@link #bindBalanceTo}.
     */
    private final ChangeListener<Number> onBalanceMoved = (observable, was, now) -> redrawBalance();
    private String charted;

    // the trade panel
    private final Label tradeTitle = Widgets.label("No event selected", "h-section");
    private final Label tradeStatus = Widgets.pill("", "off");
    private final Button closeEvent = Widgets.button("Close event…", "danger", "small");
    private final Label tradeDescription = Widgets.muted("");
    private final OptionRow[] optionRows = { new OptionRow(), new OptionRow() };
    private final Label tradeCommission = Widgets.label(Widgets.NONE, "kv-val");
    private final Label tradeMethod = Widgets.label(Widgets.NONE, "kv-val");
    private final Label tradeAccount = Widgets.label(Widgets.NONE, "kv-val");
    private final Label formTitle = Widgets.label("Buy shares", "h-tiny");
    private final ToggleGroup optionChoice = new ToggleGroup();
    private final ToggleButton[] optionButtons = { new ToggleButton(), new ToggleButton() };
    private final TextField quantity = new TextField("100");
    private final TextField limit = new TextField();
    private final HBox limitRow;
    private final Label sharesCost = Widgets.label(Widgets.NONE, "kv-val");
    private final Label commission = Widgets.label(Widgets.NONE, "kv-val");
    private final Label totalCost = Widgets.label(Widgets.NONE, "kv-val");
    private final Label balanceAfter = Widgets.label(Widgets.NONE, "kv-val");
    private final Button buy = Widgets.button("Buy", "primary");

    private List<EventView> allEvents = List.of();
    private List<MarketData.Position> allPositions = List.of();
    private String selectedUser;
    private Integer tradeEventId;
    private boolean redrawing;

    UsersScreen(DesktopApp app) {
        super(14);
        this.app = app;
        this.limitRow = Widgets.row(6, Widgets.tiny("limit price"), limit);

        VBox left = buildUserList();
        // The account is four stacked panels and does not shrink gracefully — below a tall
        // window the column scrolls rather than crushing the tables inside it.
        ScrollPane right = new ScrollPane(buildAccount());
        right.setFitToWidth(true);
        right.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        left.setMinWidth(300);
        left.setPrefWidth(300);
        left.setMaxWidth(300);
        HBox.setHgrow(right, Priority.ALWAYS);
        right.setMinWidth(0);
        getChildren().addAll(left, right);
    }

    // --- the left panel ---

    private VBox buildUserList() {
        users.getColumns().add(Widgets.col("User", 150, UserView::name));
        users.getColumns().add(Widgets.numCol("Balance", 110, user -> Widgets.money(user.balance())));
        users.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            // Repopulating the table moves the selection too; only a real change is a command.
            if (!redrawing && now != null && !now.name().equals(app.engine().getCurrentUserName())) {
                app.perform(() -> "Now acting as " + app.engine().selectUser(now.name()).name() + ".");
            }
        });
        VBox.setVgrow(users, Priority.ALWAYS);

        HBox footer = Widgets.row(8, Widgets.tiny("total held"), Widgets.grower(), totalHeld);
        footer.getStyleClass().add("footer-strip");

        VBox body = new VBox(users, footer);
        VBox.setVgrow(users, Priority.ALWAYS);
        return Widgets.panel(Widgets.panelHead("Users", null, userCount), body);
    }

    // --- the right column ---

    private VBox buildAccount() {
        return new VBox(12, accountHeader(), balancePanel(), positionsPanel(), barsPanel(), tradePanel());
    }

    /**
     * The account's own timeline: what this user's balance has been, one point per move.
     *
     * <p>It sits directly under the balance it is the history of, and above the positions
     * that explain the moves.
     */
    private VBox balancePanel() {
        HBox head = Widgets.row(10, Widgets.tiny("account balance after each change"),
                SparkChart.legend("balance", "accent"), Widgets.grower(), balanceMoves);
        head.setPadding(Widgets.pad(8, 10, 6, 10));
        balanceChart.setEmptyMessage("No balance changes yet this session.");
        return Widgets.framed(head, balanceChart);
    }

    private VBox accountHeader() {
        HBox heading = Widgets.row(8, name, ownerOf);
        heading.setAlignment(Pos.BASELINE_LEFT);

        VBox who = new VBox(4, heading, summary);
        HBox.setHgrow(who, Priority.ALWAYS);
        who.setMinWidth(0);

        VBox balanceCard = Widgets.card(Widgets.tiny("account balance"), balance);
        balanceCard.setMinWidth(170);

        VBox potentialCard = Widgets.card(Widgets.tiny("potential outcome"),
                Widgets.row(8, potential, potentialDelta),
                Widgets.note("If every open trade wins"));
        potentialCard.setMinWidth(210);

        HBox row = Widgets.row(12, who, balanceCard, potentialCard);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("panel");
        row.setPadding(Widgets.pad(12, 12, 12, 12));
        return new VBox(row);
    }

    private VBox positionsPanel() {
        roleFilter.getItems().setAll(ALL, "Owner", "Participant");
        roleFilter.setValue(ALL);
        roleFilter.setOnAction(action -> showPositions());

        positions.getColumns().add(Widgets.col("Event", 130, MarketData.Position::eventName));
        positions.getColumns().add(Widgets.nodeCol("Role", 92, position -> position.owner()
                ? Widgets.pill("Owner", "mark")
                : Widgets.pill("Participant", "plain")));
        positions.getColumns().add(Widgets.col("Option", 120, MarketData.Position::optionName));
        positions.getColumns().add(Widgets.numCol("Shares", 68,
                position -> Widgets.shares(position.shares())));
        positions.getColumns().add(Widgets.numCol("Invested", 88,
                position -> position.invested() == null ? Widgets.NONE : Widgets.money(position.invested())));
        positions.getColumns().add(Widgets.numCol("Value", 84,
                position -> Widgets.money(position.value())));
        positions.getColumns().add(Widgets.numCol("If wins", 84,
                position -> position.ifWins() == null ? Widgets.NONE : Widgets.money(position.ifWins())));
        positions.getColumns().add(Widgets.styledCol("P/L", 84,
                position -> position.profit() == null ? Widgets.NONE : Widgets.signed(position.profit()),
                position -> position == null || position.profit() == null
                        ? "numeric" : "numeric " + Widgets.moveClass(position.profit())));
        positions.getColumns().add(Widgets.nodeCol("Status", 72,
                position -> Widgets.statusPill(position.status())));
        positions.getSelectionModel().selectedItemProperty().addListener(
                (observable, was, now) -> showTrade(now == null ? null : now.eventId()));
        positions.setPrefHeight(232);
        positions.setMinHeight(140);

        VBox body = new VBox(positions);
        VBox.setVgrow(positions, Priority.ALWAYS);
        return Widgets.panel(
                Widgets.panelHead("Events — participation & ownership", null,
                        Widgets.filter("role", roleFilter, 118)),
                body);
    }

    private VBox barsPanel() {
        bars.setPadding(Widgets.pad(12, 12, 12, 12));
        bars.setMinHeight(90);
        return Widgets.panel(
                Widgets.panelHead("Position value by event", null,
                        SparkChart.legend("value now", "accent"),
                        SparkChart.legend("if that side wins", "accent-line")),
                bars);
    }

    private VBox tradePanel() {
        HBox head = Widgets.row(8, tradeTitle, tradeStatus, Widgets.grower(), closeEvent);
        head.getStyleClass().add("panel-head");
        closeEvent.setOnAction(action -> {
            EventView event = eventById(tradeEventId);
            if (event == null) {
                app.report("Pick one of this user's events first.", true);
            } else {
                app.closeEvent(event);
            }
        });

        tradeDescription.setWrapText(true);
        HBox facts = Widgets.row(18,
                Widgets.keyValue("commission", tradeCommission),
                Widgets.keyValue("market method", tradeMethod),
                Widgets.keyValue("event account", tradeAccount));

        VBox details = new VBox(10, tradeDescription,
                Widgets.row(10, optionRows[0], optionRows[1]), facts);
        HBox.setHgrow(details, Priority.ALWAYS);
        details.setMinWidth(0);
        HBox.setHgrow(optionRows[0], Priority.ALWAYS);
        HBox.setHgrow(optionRows[1], Priority.ALWAYS);

        for (int i = 0; i < optionButtons.length; i++) {
            optionButtons[i].setToggleGroup(optionChoice);
            optionButtons[i].setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(optionButtons[i], Priority.ALWAYS);
        }
        optionButtons[0].setSelected(true);
        optionChoice.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);       // one of the two is always chosen
            } else {
                reprice();
            }
        });

        quantity.getStyleClass().add("qty");
        quantity.setPrefWidth(90);
        quantity.textProperty().addListener((observable, was, now) -> reprice());
        limit.getStyleClass().add("qty");
        limit.setPrefWidth(80);
        limit.textProperty().addListener((observable, was, now) -> reprice());

        buy.setMaxWidth(Double.MAX_VALUE);
        buy.setOnAction(action -> submit());

        VBox breakdown = new VBox(6,
                Widgets.row(8, Widgets.label("Shares cost", "kv-key"), Widgets.grower(), sharesCost),
                Widgets.row(8, Widgets.label("Commission", "kv-key"), Widgets.grower(), commission),
                Widgets.row(8, Widgets.label("Total", "kv-key"), Widgets.grower(), totalCost),
                Widgets.row(8, Widgets.label("Balance after", "kv-key"), Widgets.grower(), balanceAfter));

        VBox form = Widgets.card(formTitle,
                Widgets.row(6, optionButtons[0], optionButtons[1]),
                Widgets.row(6, quantity, Widgets.faint("shares")),
                limitRow,
                Widgets.hairline(),
                breakdown,
                buy);
        form.setMinWidth(260);
        form.setPrefWidth(260);
        form.setMaxWidth(260);

        HBox body = Widgets.row(12, details, form);
        body.setAlignment(Pos.TOP_LEFT);
        body.getStyleClass().add("panel-body");
        return Widgets.panel(head, body);
    }

    // --- redrawing ---

    void refresh() {
        redrawing = true;
        try {
            redraw();
        } finally {
            redrawing = false;
        }
        // Nobody chosen yet: the screen opens on the first user, which is a real command.
        if (app.engine().isFileLoaded() && app.engine().getCurrentUserName() == null
                && selectedUser != null) {
            String chosen = selectedUser;
            app.perform(() -> "Now acting as " + app.engine().selectUser(chosen).name() + ".");
        }
    }

    private void redraw() {
        if (!app.engine().isFileLoaded()) {
            allEvents = List.of();
            allPositions = List.of();
            users.getItems().clear();
            userCount.setText("0");
            totalHeld.setText(Widgets.NONE);
            showAccount(null);
            return;
        }

        allEvents = app.engine().getEvents();
        List<UserView> everyone = app.engine().getUsers();
        userCount.setText(String.valueOf(everyone.size()));
        totalHeld.setText(Widgets.money(MarketData.totalHeld(everyone)));

        String current = app.engine().getCurrentUserName();
        users.getItems().setAll(everyone);
        UserView selected = null;
        for (UserView user : everyone) {
            if (user.name().equals(current == null ? selectedUser : current)) {
                selected = user;
            }
        }
        if (selected == null && !everyone.isEmpty()) {
            selected = everyone.get(0);
        }
        if (selected != null) {
            users.getSelectionModel().select(selected);
        }
        showAccount(selected);
    }

    private void showAccount(UserView user) {
        selectedUser = user == null ? null : user.name();
        if (user == null) {
            name.setText(Widgets.NONE);
            ownerOf.setVisible(false);
            summary.setText("Load an events file to see accounts.");
            balance.setText(Widgets.NONE);
            potential.setText(Widgets.NONE);
            potentialDelta.setText("");
            allPositions = List.of();
            bindBalanceTo(null);
            showPositions();
            showTrade(null);
            return;
        }

        allPositions = MarketData.positions(app.engine(), user, allEvents);
        double outcome = MarketData.potentialOutcome(user, allPositions);

        name.setText(user.name());
        int owned = user.marketMakerEventIds().size();
        ownerOf.setVisible(owned > 0);
        ownerOf.setText("Owner of " + owned);
        summary.setText(String.format("%d event%s · %d position%s · %s reserved",
                user.holdings().size(), user.holdings().size() == 1 ? "" : "s",
                allPositions.size(), allPositions.size() == 1 ? "" : "s",
                Widgets.money(user.reservedCash())));
        balance.setText(Widgets.money(user.balance()));
        potential.setText(Widgets.money(outcome));
        potentialDelta.setText(Widgets.signed(outcome - user.balance()));
        potentialDelta.getStyleClass().removeAll("up", "down", "faint");
        potentialDelta.getStyleClass().add(Widgets.moveClass(outcome - user.balance()));

        bindBalanceTo(user.name());
        showPositions();
    }

    /** Points the balance chart at one user's account, letting go of the last one's. */
    private void bindBalanceTo(String user) {
        if (charted != null) {
            app.live().balance(charted).removeListener(onBalanceMoved);
        }
        charted = user;
        if (charted != null) {
            app.live().balance(charted).addListener(onBalanceMoved);
        }
        redrawBalance();
    }

    /**
     * Draws the balance timeline of whoever the chart is bound to.
     *
     * <p>Unlike the price chart, this history is the UI's own: the engine stores a current
     * balance and no ledger, so the line starts when the window opened rather than when the
     * account did. A session restored from a {@code .gm} file arrives with one point — the
     * balance it was saved at — because the moves behind it were never recorded anywhere.
     */
    private void redrawBalance() {
        if (charted == null) {
            balanceMoves.setText("");
            balanceChart.show(List.of(), value -> "", List.of());
            return;
        }

        List<Double> history = app.live().balanceHistory(charted);
        List<String> ticks = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ticks.add(i == 0 ? "start" : "c" + i);
        }
        int moves = Math.max(0, history.size() - 1);
        balanceMoves.setText(moves == 1 ? "1 change" : moves + " changes");
        balanceChart.show(
                List.of(new SparkChart.Series("balance", "accent", history)),
                Widgets::money, ticks);
    }

    private void showPositions() {
        List<MarketData.Position> visible = new ArrayList<>();
        String role = roleFilter.getValue();
        for (MarketData.Position position : allPositions) {
            boolean wanted = role == null || ALL.equals(role)
                    || ("Owner".equals(role) && position.owner())
                    || ("Participant".equals(role) && !position.owner());
            if (wanted) {
                visible.add(position);
            }
        }

        Integer wanted = tradeEventId;
        positions.getItems().setAll(visible);
        MarketData.Position reselect = null;
        for (MarketData.Position position : visible) {
            if (wanted != null && position.eventId() == wanted) {
                reselect = position;
            }
        }
        if (reselect != null) {
            positions.getSelectionModel().select(reselect);
        } else if (!visible.isEmpty()) {
            positions.getSelectionModel().selectFirst();
        } else {
            showTrade(null);
        }
        drawBars(visible);
    }

    /**
     * Where this user's money is: one row per position, the filled part what it is worth
     * now against the pale part it would be worth if that side wins.
     *
     * <p>The design puts a balance-over-time chart here. The engine keeps no per-user
     * ledger — balances are current, and an LMSR trade does not record who made it — so
     * there is no timeline to draw, and this answers the same question from what is known.
     */
    private void drawBars(List<MarketData.Position> visible) {
        bars.getChildren().clear();
        double widest = 0;
        for (MarketData.Position position : visible) {
            widest = Math.max(widest, position.ifWins() == null ? position.value() : position.ifWins());
        }
        if (visible.isEmpty() || widest <= 0) {
            bars.getChildren().add(Widgets.faint("Nothing held yet."));
            return;
        }
        for (MarketData.Position position : visible) {
            double ceiling = position.ifWins() == null ? position.value() : position.ifWins();
            bars.getChildren().add(bar(position, ceiling / widest, position.value() / widest));
        }
    }

    private Node bar(MarketData.Position position, double ceiling, double now) {
        Region ghost = new Region();
        ghost.getStyleClass().addAll("bar-fill", "ghost");
        Region filled = new Region();
        filled.getStyleClass().add("bar-fill");

        StackPane track = new StackPane(ghost, filled);
        track.getStyleClass().add("bar-track");
        track.setAlignment(Pos.CENTER_LEFT);
        ghost.maxWidthProperty().bind(track.widthProperty().multiply(ceiling));
        filled.maxWidthProperty().bind(track.widthProperty().multiply(now));
        HBox.setHgrow(track, Priority.ALWAYS);

        Label label = Widgets.muted(position.eventName() + " · " + position.optionName());
        label.setMinWidth(190);
        label.setPrefWidth(190);
        Label value = Widgets.figure(Widgets.money(position.value()));
        value.setMinWidth(78);
        value.setAlignment(Pos.CENTER_RIGHT);
        return Widgets.row(10, label, track, value);
    }

    // --- the trade panel ---

    private void showTrade(Integer eventId) {
        tradeEventId = eventId;
        EventView event = eventById(eventId);
        boolean open = event != null && MarketData.isActive(event) && selectedUser != null;

        closeEvent.setDisable(event == null || !MarketData.isActive(event));
        for (ToggleButton button : optionButtons) {
            button.setDisable(!open);
        }
        quantity.setDisable(!open);
        limit.setDisable(!open);
        buy.setDisable(!open);

        if (event == null) {
            tradeTitle.setText("No event selected");
            tradeStatus.setVisible(false);
            tradeDescription.setText("Pick one of this user's positions to trade its event.");
            for (OptionRow row : optionRows) {
                row.clear();
            }
            tradeCommission.setText(Widgets.NONE);
            tradeMethod.setText(Widgets.NONE);
            tradeAccount.setText(Widgets.NONE);
            optionButtons[0].setText(Widgets.NONE);
            optionButtons[1].setText(Widgets.NONE);
            limitRow.setVisible(false);
            limitRow.setManaged(false);
            reprice();
            return;
        }

        tradeTitle.setText(event.name() + " — details & trade");
        tradeStatus.setVisible(true);
        tradeStatus.setText(MarketData.isActive(event) ? "Active" : "Closed");
        tradeStatus.getStyleClass().removeAll("ok", "off");
        tradeStatus.getStyleClass().add(MarketData.isActive(event) ? "ok" : "off");
        tradeDescription.setText(event.description() == null ? "" : event.description());
        tradeCommission.setText(MarketData.commissionLabel(event));
        tradeMethod.setText(MarketData.methodLabel(event));
        formTitle.setText(("Buy shares as " + selectedUser).toUpperCase());

        for (int i = 0; i < optionButtons.length; i++) {
            optionButtons[i].setText(event.optionNames().get(i));
        }

        boolean lmsr = MarketData.isLmsr(event);
        limitRow.setVisible(!lmsr);
        limitRow.setManaged(!lmsr);

        if (lmsr) {
            EventStatusView status = app.engine().getEventStatus(event.id());
            tradeAccount.setText(Widgets.money(status.accountBalance()));
            for (int i = 0; i < optionRows.length; i++) {
                optionRows[i].show(status.options().get(i).name(),
                        status.options().get(i).currentPrice(),
                        status.options().get(i).totalShares(),
                        "b = " + (status.b() == Math.rint(status.b())
                                ? String.valueOf((long) status.b()) : Widgets.money(status.b())));
            }
        } else {
            OrderBookStatusView status = app.engine().getOrderBookStatus(event.id());
            tradeAccount.setText(Widgets.money(status.accountBalance()));
            for (int i = 0; i < optionRows.length; i++) {
                OptionBookView option = status.options().get(i);
                Double shown = option.lastPrice() != null ? option.lastPrice() : option.midPrice();
                optionRows[i].show(option.name(), shown == null ? 0 : shown, option.sharesOutstanding(),
                        "bid " + Widgets.price(option.bestBid()) + " / ask " + Widgets.price(option.bestAsk()));
            }
            if (limit.getText().isBlank()) {
                OptionBookView option = status.options().get(chosenOption());
                Double touch = option.bestAsk() != null ? option.bestAsk() : option.lastPrice();
                limit.setText(touch == null ? "" : String.format("%.2f", touch));
            }
        }
        reprice();
    }

    /** Prices what is in the form, quietly — a half-typed number is not an error yet. */
    private void reprice() {
        EventView event = eventById(tradeEventId);
        long shares = parseLong(quantity.getText());
        if (event == null || shares <= 0 || selectedUser == null) {
            blankBreakdown();
            return;
        }
        try {
            double cost;
            double fee;
            if (MarketData.isLmsr(event)) {
                PurchaseQuote quote = app.engine().quoteParticipation(event.id(), chosenOption(), shares);
                cost = quote.sharesCost();
                fee = quote.commission();
            } else {
                Double atPrice = parseDouble(limit.getText());
                if (atPrice == null) {
                    blankBreakdown();
                    return;
                }
                cost = shares * atPrice;
                fee = "PER_PURCHASE".equals(event.commissionMethod()) ? cost * event.commissionRate() : 0;
            }
            sharesCost.setText(Widgets.money(cost));
            commission.setText(Widgets.money(fee));
            totalCost.setText(Widgets.money(cost + fee));
            balanceAfter.setText(Widgets.money(currentBalance() - cost - fee));
            buy.setText("Buy " + Widgets.shares(shares) + " of " + event.optionNames().get(chosenOption()));
        } catch (EngineException e) {
            blankBreakdown();
        }
    }

    private void submit() {
        EventView event = eventById(tradeEventId);
        if (event == null) {
            return;
        }
        int optionIndex = chosenOption();
        app.perform(() -> {
            long shares = DesktopApp.readPositiveLong(quantity.getText(), "shares");
            if (MarketData.isLmsr(event)) {
                PurchaseResult result = app.engine().participate(event.id(), optionIndex, shares);
                return String.format("Bought %s of %s for %s (commission %s).",
                        Widgets.shares(result.sharesBought()), result.optionName(),
                        Widgets.money(result.sharesCost()), Widgets.money(result.commission()));
            }
            double atPrice = DesktopApp.readPositiveDouble(limit.getText(), "price");
            OrderResult result = app.engine()
                    .placeOrder(event.id(), optionIndex, OrderSide.BUY, atPrice, shares);
            return String.format("Order #%d: %s filled, %s resting at %s.",
                    result.sequence(), Widgets.shares(result.filled()),
                    Widgets.shares(result.resting()), Widgets.price(result.price()));
        });
    }

    private void blankBreakdown() {
        sharesCost.setText(Widgets.NONE);
        commission.setText(Widgets.NONE);
        totalCost.setText(Widgets.NONE);
        balanceAfter.setText(Widgets.NONE);
        buy.setText("Buy");
    }

    private int chosenOption() {
        return optionButtons[1].isSelected() ? 1 : 0;
    }

    private double currentBalance() {
        UserView user = users.getSelectionModel().getSelectedItem();
        return user == null ? 0 : user.balance();
    }

    private EventView eventById(Integer eventId) {
        if (eventId == null) {
            return null;
        }
        for (EventView event : allEvents) {
            if (event.id() == eventId) {
                return event;
            }
        }
        return null;
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

    /** One outcome as the trade panel shows it: name, price, a meter, and a line of detail. */
    private static class OptionRow extends VBox {

        private final Label name = Widgets.label("", "opt-name");
        private final Label price = Widgets.label(Widgets.NONE, "opt-price");
        private final Region fill = new Region();
        private final StackPane meter = new StackPane(fill);
        private final Label detail = Widgets.note("");

        OptionRow() {
            super(6);
            getStyleClass().add("card");
            setMinWidth(0);
            fill.getStyleClass().add("meter-fill");
            meter.getStyleClass().add("meter");
            meter.setAlignment(Pos.CENTER_LEFT);
            HBox head = Widgets.row(8, name, Widgets.grower(), price);
            head.setAlignment(Pos.BASELINE_LEFT);
            getChildren().addAll(head, meter, detail);
        }

        void show(String optionName, double optionPrice, long shares, String note) {
            name.setText(optionName);
            price.setText(Widgets.price(optionPrice));
            fill.maxWidthProperty().bind(meter.widthProperty()
                    .multiply(Math.max(0, Math.min(1, optionPrice))));
            detail.setText(Widgets.shares(shares) + " sh · " + note);
        }

        void clear() {
            name.setText(Widgets.NONE);
            price.setText(Widgets.NONE);
            fill.maxWidthProperty().unbind();
            fill.setMaxWidth(0);
            detail.setText("");
        }
    }
}
