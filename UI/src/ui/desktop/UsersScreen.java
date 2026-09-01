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
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen two: every user on the left, one user's account on the right.
 *
 * <p>The account is the whole point of the screen (what they hold, what it is worth now,
 * and what it pays if the sides they are on come in) so the panels descend in that order:
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

    /** The list column is fixed; the rest of the screen is what the window's width moves. */
    private static final double LIST_WIDTH = 300;

    /**
     * The smallest the account column and the screen are still worth drawing at.
     *
     * <p>The width is the account header's own minimum (a name, then the two cards) plus
     * the room its scroll bar takes out of the column, so the panels inside never have to
     * be drawn narrower than they can be read at.
     */
    private static final double ACCOUNT_MIN_WIDTH = 592;

    /** Both columns can be emptied of rows, so the screen's height has its own floor. */
    private static final double MIN_HEIGHT = 470;

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

    /**
     * The three figures of the account header count to their new values rather than
     * replacing themselves, so a purchase is seen to move the money. Each follows a property
     * belonging to one user, so switching accounts re-points it and lands rather than rolling,
     * because a different person's balance is not this one's next value.
     */
    private final Ticker balanceTicker = Ticker.money(balance);
    private final Ticker potentialTicker = Ticker.money(potential);
    private final Ticker potentialDeltaTicker = Ticker.signed(potentialDelta);

    /**
     * The potential outcome of each account, and how far it sits from the cash in it.
     *
     * <p>The same shape as {@code LiveMarket.prices}, kept here rather than there because
     * this is the one figure on the screen the engine does not publish: it is worked out in
     * {@link MarketData#potentialOutcome} from the positions of whichever user is selected,
     * and computing it for everybody on every refresh would be a walk of every holding of
     * every user to answer a question about one of them.
     *
     * <p>Index 0 is the outcome, index 1 the delta. Only the selected user's is ever set;
     * the rest hold zero until they are looked at, which is the moment they get a real value
     * and the ticker lands on it.
     */
    private final Map<String, DoubleProperty[]> outcomes = new LinkedHashMap<>();

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

    /**
     * The figures this screen owns rather than the engine, each backing a bound label,
     * the same arrangement as {@code EventsScreen}, and for the same reason: a refresh that
     * moved nothing must not rewrite anything.
     */
    private final IntegerProperty userTotal = new SimpleIntegerProperty();
    private final StringProperty totalHeldText = new SimpleStringProperty(Widgets.NONE);
    private final StringProperty balanceMovesText = new SimpleStringProperty("");

    private List<EventView> allEvents = List.of();
    private List<MarketData.Position> allPositions = List.of();
    private String selectedUser;
    private Integer tradeEventId;
    private boolean redrawing;

    UsersScreen(DesktopApp app) {
        super(14);
        this.app = app;
        this.limitRow = Widgets.row(6, Widgets.tiny("limit price"), limit);

        userCount.textProperty().bind(userTotal.asString());
        totalHeld.textProperty().bind(totalHeldText);
        balanceMoves.textProperty().bind(balanceMovesText);

        VBox left = buildUserList();
        // The account is four stacked panels and does not shrink gracefully, so below a tall
        // window the column scrolls rather than crushing the tables inside it.
        ScrollPane right = new ScrollPane(buildAccount());
        right.setFitToWidth(true);
        right.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        left.setMinWidth(LIST_WIDTH);
        left.setPrefWidth(LIST_WIDTH);
        left.setMaxWidth(LIST_WIDTH);
        HBox.setHgrow(right, Priority.ALWAYS);
        right.setMinWidth(ACCOUNT_MIN_WIDTH);
        setMinHeight(MIN_HEIGHT);
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

        HBox footer = Widgets.row(8, Widgets.tiny("total held"), Widgets.grower(), totalHeld);
        footer.getStyleClass().add("footer-strip");

        VBox body = new VBox(users, footer);
        VBox.setVgrow(users, Priority.ALWAYS);
        return Widgets.panel(Widgets.panelHead("Users", userCount), body);
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
        // Whose account this is outranks the line under it, so the block keeps enough room
        // for a name and the summary is what truncates when the window is narrow.
        who.setMinWidth(150);

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
        positions.getColumns().add(Widgets.overFigures(Widgets.styledCol("P/L", 84,
                position -> position.profit() == null ? Widgets.NONE : Widgets.signed(position.profit()),
                position -> position == null || position.profit() == null
                        ? "numeric" : "numeric " + Widgets.moveClass(position.profit()))));
        positions.getColumns().add(Widgets.nodeCol("Status", 72,
                position -> Widgets.statusPill(position.status())));
        positions.getSelectionModel().selectedItemProperty().addListener(
                (observable, was, now) -> showTrade(now == null ? null : now.eventId()));
        positions.setPrefHeight(232);
        positions.setMinHeight(140);

        VBox body = new VBox(positions);
        VBox.setVgrow(positions, Priority.ALWAYS);
        return Widgets.panel(
                Widgets.panelHead("Events: participation & ownership",
                        Widgets.filter("role", roleFilter, 118)),
                body);
    }

    private VBox barsPanel() {
        bars.setPadding(Widgets.pad(12, 12, 12, 12));
        bars.setMinHeight(90);
        return Widgets.panel(
                Widgets.panelHead("Position value by event",
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
            userTotal.set(0);
            totalHeldText.set(Widgets.NONE);
            showAccount(null);
            return;
        }

        allEvents = app.engine().getEvents();
        List<UserView> everyone = app.engine().getUsers();
        userTotal.set(everyone.size());
        totalHeldText.set(Widgets.money(MarketData.totalHeld(everyone)));

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
            balanceTicker.clear();
            potentialTicker.clear();
            potentialDeltaTicker.clear("");     // the delta has no dash of its own
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
        // Set first, follow second: setting a property that is already being followed is
        // the movement, and pointing at one that is not is the arrival. Both end with the
        // right figure on screen, and only the first of them animates.
        DoubleProperty[] figures = outcomeOf(user.name());
        figures[0].set(outcome);
        figures[1].set(outcome - user.balance());

        balanceTicker.follow(app.live().balance(user.name()));
        potentialTicker.follow(figures[0]);
        potentialDeltaTicker.follow(figures[1]);

        // The colour is what the delta *is*, not a step on the way there, so it is set at
        // once and the digits catch up to it.
        potentialDelta.getStyleClass().removeAll("up", "down", "faint");
        potentialDelta.getStyleClass().add(Widgets.moveClass(outcome - user.balance()));

        bindBalanceTo(user.name());
        showPositions();
    }

    /**
     * Holds the account header's three figures still until this screen is the one on show.
     *
     * <p>Most of the money on this screen is moved from the other one: a purchase made on
     * the Events tab changes the balance here, and the roll that follows it would play out
     * behind a tab nobody is looking at. Gated on the tab's own selection, it waits and runs
     * when the tab is opened, which is the only moment there is anyone to see it. Called by
     * {@code DesktopApp.start} once the layout has handed it the tab.
     */
    void animateOnlyWhile(ObservableBooleanValue inView) {
        balanceTicker.onlyWhile(inView);
        potentialTicker.onlyWhile(inView);
        potentialDeltaTicker.onlyWhile(inView);
    }

    /** The outcome and delta properties of one account, made on first sight of it. */
    private DoubleProperty[] outcomeOf(String userName) {
        return outcomes.computeIfAbsent(userName,
                name -> new DoubleProperty[] { new SimpleDoubleProperty(), new SimpleDoubleProperty() });
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
     * balance and no ledger. What it does store is the cash the file gave the account, so the
     * line starts there, the first tick being {@code start}, and steps once per move after it.
     * A session restored from a {@code .gm} file therefore arrives with two points, its
     * initial cash and the balance it was saved at, since the moves between them were never
     * recorded anywhere.
     */
    private void redrawBalance() {
        if (charted == null) {
            balanceMovesText.set("");
            balanceChart.show(List.of(), value -> "", List.of());
            return;
        }

        List<Double> history = app.live().balanceHistory(charted);
        List<String> ticks = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ticks.add(i == 0 ? "start" : "c" + i);
        }
        int moves = Math.max(0, history.size() - 1);
        balanceMovesText.set(moves == 1 ? "1 change" : moves + " changes");
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
     * ledger (balances are current, and an LMSR trade does not record who made it) so
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

        // Shown to the one user who can act on it: the event's Market Maker.
        boolean closable = MarketData.canClose(app.engine(), event);
        closeEvent.setVisible(closable);
        closeEvent.setManaged(closable);
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
            Widgets.followMoney(tradeAccount, null);
            optionButtons[0].setText(Widgets.NONE);
            optionButtons[1].setText(Widgets.NONE);
            limitRow.setVisible(false);
            limitRow.setManaged(false);
            reprice();
            return;
        }

        tradeTitle.setText(event.name() + " · details & trade");
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

        // The account belongs to the event, so it follows that event's property and is
        // re-pointed when the position selection moves to another one.
        Widgets.followMoney(tradeAccount, app.live().account(event.id()));

        boolean lmsr = MarketData.isLmsr(event);
        limitRow.setVisible(!lmsr);
        limitRow.setManaged(!lmsr);

        if (lmsr) {
            EventStatusView status = app.engine().getEventStatus(event.id());
            for (int i = 0; i < optionRows.length; i++) {
                optionRows[i].show(status.options().get(i).name(),
                        app.live().price(event.id(), i),
                        status.options().get(i).totalShares(),
                        "b = " + (status.b() == Math.rint(status.b())
                                ? String.valueOf((long) status.b()) : Widgets.money(status.b())));
            }
        } else {
            OrderBookStatusView status = app.engine().getOrderBookStatus(event.id());
            for (int i = 0; i < optionRows.length; i++) {
                OptionBookView option = status.options().get(i);
                optionRows[i].show(option.name(), app.live().price(event.id(), i),
                        option.sharesOutstanding(),
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

    /** Prices what is in the form, quietly: a half-typed number is not an error yet. */
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
        private final Ticker priceTicker = Ticker.price(price);
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

            // Bound once to the rolling figure, the same way LmsrPane's meter is, so the bar
            // travels with the digits instead of jumping to the answer ahead of them. It was
            // rebound to a constant on every show before, which is why it used to jump.
            fill.maxWidthProperty().bind(meter.widthProperty().multiply(
                    Bindings.createDoubleBinding(
                            () -> Math.max(0, Math.min(1, priceTicker.value().get())),
                            priceTicker.value())));

            HBox head = Widgets.row(8, name, Widgets.grower(), price);
            head.setAlignment(Pos.BASELINE_LEFT);
            getChildren().addAll(head, meter, detail);
        }

        /**
         * @param optionPrice the event's own live price, followed rather than read, so this
         *                    row rolls when the option reprices and lands when the panel is
         *                    re-used for a different event
         */
        void show(String optionName, ObservableValue<? extends Number> optionPrice,
                  long shares, String note) {
            name.setText(optionName);
            priceTicker.follow(optionPrice);
            detail.setText(Widgets.shares(shares) + " sh · " + note);
        }

        void clear() {
            name.setText(Widgets.NONE);
            priceTicker.clear();    // empties the meter with it; the binding stays
            detail.setText("");
        }
    }
}
