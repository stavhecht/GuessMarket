package ui.desktop;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OrderBookStatusView;
import engine.dto.UserView;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Screen one: every event on the left, the selected one traded on the right.
 *
 * <p>The left panel is a filterable list with a preview of whatever is highlighted; the
 * right is that event's market (an LMSR pair of price cards or an order book's two
 * ladders, whichever the event uses) with the price history under it and the full
 * participation log under that.
 *
 * <p>The filters are the screen's own and never reach the engine: {@code getEvents()}
 * returns everything and this class decides what to show, so filtering can never be
 * mistaken for a command.
 */
class EventsScreen extends HBox {

    private static final String ALL = "All";

    /** Markets are binary by construction, the same way {@code Event.OPTION_COUNT} says. */
    private static final int OPTIONS = 2;

    private final DesktopApp app;

    // left
    private final Label eventCount = Widgets.pill("0", "count");
    private final ComboBox<String> methodFilter = new ComboBox<>();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final ComboBox<String> commissionFilter = new ComboBox<>();
    private final Label shown = Widgets.note("");
    private final TableView<EventView> events = Widgets.table("No events file loaded.");
    private final Label previewName = Widgets.section(Widgets.NONE);
    private final Label previewDescription = Widgets.muted("");
    private final Label previewOwner = Widgets.figure(Widgets.NONE);
    private final Label previewOptions = Widgets.muted(Widgets.NONE);
    private final Label previewCommission = Widgets.muted(Widgets.NONE);
    private final Label previewParticipants = Widgets.figure(Widgets.NONE);

    // right
    private final Label title = Widgets.subject("No event selected");
    private final Label number = Widgets.label("", "mono", "faint");
    private final HBox titleRow;
    private final Label statusSlot = Widgets.pill("", "off");
    private final Button closeEvent = Widgets.button("Close event…", "danger", "small");

    /** Built in {@link #buildEventList()}, which the constructor runs. */
    private Button create;
    private final StackPane market = new StackPane();
    private final Label marketPlaceholder = Widgets.faint("Select an event on the left.");
    private final LmsrPane lmsrPane;
    private final OrderBookPane orderBookPane;
    private final SparkChart chart = new SparkChart(150);
    private final HBox chartLegend = Widgets.row(12);
    private final Label tradeCount = Widgets.note("");
    private final Label participationCount = Widgets.pill("0", "count");
    private final TableView<MarketData.Line> participations = Widgets.table("Nothing has traded yet.");
    private final Label accountBalance = Widgets.figure(Widgets.NONE);
    private final Label commissionCollected = Widgets.figure(Widgets.NONE);
    private final Label methodNote = Widgets.note("");

    /**
     * The figures this screen owns, rather than the engine.
     *
     * <p>The label each one backs is <em>bound</em> to it in the constructor and never
     * written directly, so a refresh that did not change a count leaves it alone: a property
     * set to the value it already holds fires nothing. The composite two are strings rather
     * than numbers because the design gives them a form ("3 of 5", "1 trade") and an empty
     * state that a count cannot express.
     */
    private final IntegerProperty eventTotal = new SimpleIntegerProperty();
    private final StringProperty shownText = new SimpleStringProperty("");
    private final IntegerProperty participationTotal = new SimpleIntegerProperty();
    private final StringProperty tradeCountText = new SimpleStringProperty("");

    private List<EventView> all = List.of();
    private List<UserView> users = List.of();
    private Integer selectedId;

    /**
     * The chart's subscription to the selected event's two prices.
     *
     * <p>The chart redraws because a price moved, not because something happened to have
     * called {@link #refresh()}, so it cannot be left behind by a screen that updates by
     * another route. The properties are the selected event's, so they are swapped over in
     * {@link #bindChartTo} whenever the selection changes.
     */
    private final ChangeListener<Number> onPriceMoved = (observable, was, now) -> redrawChart();
    private EventView charted;

    EventsScreen(DesktopApp app) {
        super(14);
        this.app = app;
        this.lmsrPane = new LmsrPane(app);
        this.orderBookPane = new OrderBookPane(app);
        this.titleRow = Widgets.row(8, title, number, statusSlot, Widgets.grower(), closeEvent);

        eventCount.textProperty().bind(eventTotal.asString());
        shown.textProperty().bind(shownText);
        participationCount.textProperty().bind(participationTotal.asString());
        tradeCount.textProperty().bind(tradeCountText);

        VBox left = buildEventList();
        VBox right = buildEventPanel();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMinWidth(0);
        right.setMinWidth(0);
        getChildren().addAll(left, right);
    }

    // --- the left panel ---

    private VBox buildEventList() {
        create = Widgets.tip(Widgets.button("Create event", "small"),
                "Adds an event to the loaded market. You become its market maker, "
                        + "so an order book's initial investment comes out of your balance.");
        create.setOnAction(action -> app.createEvent());
        create.setDisable(true);    // until a market is loaded and somebody is acting

        events.getColumns().add(Widgets.idCol("id", 40, event -> String.format("%02d", event.id())));
        events.getColumns().add(Widgets.col("Event", 150, EventView::name));
        events.getColumns().add(Widgets.col("Method", 90, MarketData::methodLabel));
        events.getColumns().add(Widgets.col("Commission", 130, MarketData::commissionLabel));
        events.getColumns().add(Widgets.nodeCol("Status", 72,
                event -> Widgets.statusPill(event.status())));
        events.getSelectionModel().selectedItemProperty()
                .addListener((observable, was, now) -> select(now));
        VBox.setVgrow(events, Priority.ALWAYS);

        methodFilter.getItems().setAll(ALL, "LMSR", "Order book");
        statusFilter.getItems().setAll(ALL, "Active", "Closed");
        commissionFilter.getItems().setAll(ALL, "On purchase", "On close");
        for (ComboBox<String> filter : List.of(methodFilter, statusFilter, commissionFilter)) {
            filter.setValue(ALL);
            filter.setOnAction(action -> applyFilters());
        }

        HBox filters = Widgets.row(8,
                Widgets.tiny("filter"),
                Widgets.filter("method", methodFilter, 108),
                Widgets.filter("status", statusFilter, 90),
                Widgets.filter("commission", commissionFilter, 118),
                Widgets.grower(), shown);
        filters.getStyleClass().add("subbar");

        previewDescription.setWrapText(true);
        previewDescription.setMinHeight(32);

        HBox facts = Widgets.row(18,
                Widgets.keyValue("owner", previewOwner),
                Widgets.keyValue("options", previewOptions),
                Widgets.keyValue("commission", previewCommission),
                Widgets.keyValue("participants", previewParticipants));
        facts.setAlignment(Pos.TOP_LEFT);

        VBox preview = new VBox(8, Widgets.tiny("selected event"), previewName,
                previewDescription, facts);
        preview.setPadding(Widgets.pad(12, 12, 12, 12));
        preview.getStyleClass().add("subbar");

        VBox body = new VBox(filters, events, preview);
        VBox.setVgrow(events, Priority.ALWAYS);
        return Widgets.panel(Widgets.panelHead("Events", null, eventCount, Widgets.gap(4), create), body);
    }

    // --- the right panel ---

    private VBox buildEventPanel() {
        closeEvent.setOnAction(action -> {
            EventView event = events.getSelectionModel().getSelectedItem();
            if (event == null) {
                app.report("Select the event to close first.", true);
            } else {
                app.closeEvent(event);
            }
        });

        market.getChildren().addAll(marketPlaceholder, lmsrPane, orderBookPane);
        market.setAlignment(Pos.TOP_LEFT);

        HBox chartHead = Widgets.row(10, Widgets.tiny("option price from the opening"),
                chartLegend, Widgets.grower(), tradeCount);
        chartHead.setPadding(Widgets.pad(8, 10, 6, 10));
        VBox chartPanel = Widgets.framed(chartHead, chart);
        chart.setEmptyMessage("No transactions yet.");

        participations.getColumns().add(Widgets.idCol("#", 44, line -> String.valueOf(line.sequence())));
        participations.getColumns().add(Widgets.col("User", 110, MarketData.Line::user));
        participations.getColumns().add(Widgets.col("Option", 130, MarketData.Line::optionName));
        participations.getColumns().add(Widgets.numCol("Shares", 74,
                line -> Widgets.shares(line.shares())));
        participations.getColumns().add(Widgets.numCol("Price", 66,
                line -> String.format("%.2f", line.price())));
        participations.getColumns().add(Widgets.numCol("Comm.", 74,
                line -> Widgets.money(line.commission())));
        participations.getColumns().add(Widgets.numCol("Total", 86,
                line -> Widgets.money(line.total())));

        HBox participationHead = Widgets.row(8, Widgets.tiny("participations"), participationCount);
        participationHead.setPadding(Widgets.pad(8, 10, 6, 10));

        HBox footer = Widgets.row(8,
                Widgets.tiny("mm balance"), accountBalance,
                Widgets.gap(10),
                Widgets.tiny("commission"), commissionCollected,
                Widgets.grower(), methodNote);
        footer.getStyleClass().add("footer-strip");

        participations.setPrefHeight(212);
        participations.setMinHeight(120);
        VBox participationPanel = Widgets.framed(participationHead, participations, footer);
        VBox.setVgrow(participations, Priority.ALWAYS);

        VBox body = new VBox(12, market, chartPanel, participationPanel);
        body.getStyleClass().add("panel-body");

        // An order-book ladder is a good deal taller than a pair of LMSR cards, and the
        // three blocks below it must stay legible either way, so the panel scrolls rather
        // than squeezing whichever of them happens to be last.
        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        titleRow.getStyleClass().add("panel-head");
        return Widgets.panel(titleRow, scroller);
    }

    // --- redrawing ---

    /** Rereads the whole screen from the engine, keeping whatever event was selected. */
    void refresh() {
        // An event is created by somebody, and it needs a market to go into.
        create.setDisable(!app.engine().isFileLoaded() || app.engine().getCurrentUserName() == null);

        if (!app.engine().isFileLoaded()) {
            all = List.of();
            users = List.of();
            events.getItems().clear();
            eventTotal.set(0);
            shownText.set("");
            select(null);
            return;
        }
        all = app.engine().getEvents();
        users = app.engine().getUsers();
        eventTotal.set(all.size());
        applyFilters();
    }

    private void applyFilters() {
        List<EventView> visible = all.stream().filter(this::matches).toList();
        shownText.set(visible.size() + " of " + all.size());

        Integer wanted = selectedId;
        EventView before = events.getSelectionModel().getSelectedItem();

        if (!events.getItems().equals(visible)) {
            events.getItems().setAll(visible);
        }

        // Ids are unique, so the first match is the only one.
        EventView reselect = wanted == null ? null
                : visible.stream().filter(event -> event.id() == wanted).findFirst().orElse(null);
        if (reselect != null) {
            events.getSelectionModel().select(reselect);
        } else if (!visible.isEmpty()) {
            // Nothing chosen yet, or the choice was filtered away, so open on the first row
            // rather than on an empty panel.
            events.getSelectionModel().selectFirst();
        } else {
            select(null);
            return;
        }

        // The figures behind an event move without the event itself changing: a trade
        // reprices it but leaves its EventView equal, so when the table was left alone and
        // the listener never fired, the panel is redrawn from here instead. Compared by
        // identity: a selection that really moved hands back a different object.
        EventView after = events.getSelectionModel().getSelectedItem();
        if (after == before) {
            select(after);
        }
    }

    private boolean matches(EventView event) {
        String method = methodFilter.getValue();
        String status = statusFilter.getValue();
        String commission = commissionFilter.getValue();
        if (method != null && !ALL.equals(method) && !method.equals(MarketData.methodLabel(event))) {
            return false;
        }
        if (status != null && !ALL.equals(status)
                && !status.equalsIgnoreCase(MarketData.isActive(event) ? "Active" : "Closed")) {
            return false;
        }
        String when = "PER_PURCHASE".equals(event.commissionMethod()) ? "On purchase" : "On close";
        return commission == null || ALL.equals(commission) || commission.equals(when);
    }

    /** Draws one event into both halves of the screen, or empties them when there is none. */
    private void select(EventView event) {
        selectedId = event == null ? null : event.id();

        show(marketPlaceholder, event == null);
        show(lmsrPane, false);
        show(orderBookPane, false);
        // Only the event's Market Maker can close it, so nobody else is shown the button.
        show(closeEvent, MarketData.canClose(app.engine(), event));

        if (event == null) {
            title.setText("No event selected");
            number.setText("");
            statusSlot.setVisible(false);
            previewName.setText(Widgets.NONE);
            previewDescription.setText("");
            previewOwner.setText(Widgets.NONE);
            previewOptions.setText(Widgets.NONE);
            previewCommission.setText(Widgets.NONE);
            previewParticipants.setText(Widgets.NONE);
            participations.getItems().clear();
            participationTotal.set(0);
            Widgets.followMoney(accountBalance, null);
            Widgets.followMoney(commissionCollected, null);
            methodNote.setText("");
            tradeCountText.set("");
            bindChartTo(null);
            return;
        }

        title.setText(event.name());
        number.setText(String.format("#%02d", event.id()));
        statusSlot.setVisible(true);
        statusSlot.setText(MarketData.isActive(event) ? "Active" : "Closed");
        statusSlot.getStyleClass().removeAll("ok", "off");
        statusSlot.getStyleClass().add(MarketData.isActive(event) ? "ok" : "off");

        previewName.setText(event.name());
        previewDescription.setText(event.description() == null ? "" : event.description());
        previewOwner.setText(event.marketMaker() == null ? Widgets.NONE : event.marketMaker());
        previewOptions.setText(String.join(" / ", event.optionNames()));
        previewCommission.setText(MarketData.commissionLabel(event));
        previewParticipants.setText(String.valueOf(MarketData.participants(users, event)));

        List<MarketData.Line> lines;
        if (MarketData.isLmsr(event)) {
            EventStatusView status = app.engine().getEventStatus(event.id());
            lmsrPane.show(status);
            show(lmsrPane, true);
            lines = MarketData.lines(status);
            methodNote.setText("b = " + trimmed(status.b()));
        } else {
            OrderBookStatusView status = app.engine().getOrderBookStatus(event.id());
            orderBookPane.show(event, status);
            show(orderBookPane, true);
            lines = MarketData.lines(status);
            methodNote.setText("d = " + trimmed(status.baseValue())
                    + (status.allowMint() ? " · minting allowed" : " · no minting"));
        }

        participations.getItems().setAll(lines);
        // The account and its commission belong to the event, so they follow that event's
        // properties and are re-pointed here rather than rewritten on every refresh.
        Widgets.followMoney(accountBalance, app.live().account(event.id()));
        Widgets.followMoney(commissionCollected, app.live().commission(event.id()));

        participationTotal.set(lines.size());
        tradeCountText.set(lines.size() == 1 ? "1 trade" : lines.size() + " trades");
        bindChartTo(event);
    }

    /**
     * Points the chart at one event's prices, letting go of the last one's.
     *
     * <p>Only the selected event is watched: subscribing to every event would redraw this
     * chart on trades that are not on it.
     */
    private void bindChartTo(EventView event) {
        if (charted != null) {
            for (int option = 0; option < OPTIONS; option++) {
                app.live().price(charted.id(), option).removeListener(onPriceMoved);
            }
        }
        charted = event;
        if (charted != null) {
            for (int option = 0; option < OPTIONS; option++) {
                app.live().price(charted.id(), option).addListener(onPriceMoved);
            }
        }
        redrawChart();
    }

    /**
     * Draws the price history of whichever event the chart is bound to.
     *
     * <p>The series is the engine's rather than anything accumulated here: an LMSR event is
     * replayed through the scoring rule and an order book's is read off its trade log, so
     * the line covers trades made before this window opened and survives a session being
     * loaded from disk. The binding decides <em>when</em> this runs; the engine decides what
     * it draws.
     */
    private void redrawChart() {
        if (charted == null) {
            chartLegend.getChildren().clear();
            chart.show(List.of(), value -> "", List.of());
            return;
        }
        List<double[]> series;
        boolean fromOpen;
        if (MarketData.isLmsr(charted)) {
            series = app.engine().getPriceHistory(charted.id());
            fromOpen = true;    // an LMSR event always has an opening price: 0.5 a side
        } else {
            OrderBookStatusView status = app.engine().getOrderBookStatus(charted.id());
            series = MarketData.priceSeries(status);
            // An order book only opened at a price if its Market Maker was given something
            // to open it with; without that the series still starts at the first trade.
            fromOpen = status.openingPrice() != null;
        }
        drawChart(charted, series, fromOpen);
    }

    /**
     * @param fromOpen whether the first point is the market's opening price rather than a
     *                 transaction, which is what the axis calls it
     */
    private void drawChart(EventView event, List<double[]> series, boolean fromOpen) {
        List<Double> first = new ArrayList<>();
        List<Double> second = new ArrayList<>();
        List<String> ticks = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            first.add(series.get(i)[0]);
            second.add(series.get(i)[1]);
            ticks.add(fromOpen && i == 0 ? "open" : "t" + (fromOpen ? i : i + 1));
        }

        chartLegend.getChildren().setAll(
                SparkChart.legend(event.optionNames().get(0), "accent"),
                SparkChart.legend(event.optionNames().get(1), "tx-3"));

        DoubleFunction<String> axis = value -> String.format("%.2f", value);
        chart.show(List.of(
                new SparkChart.Series(event.optionNames().get(0), "accent", first),
                new SparkChart.Series(event.optionNames().get(1), "tx-3", second)), axis, ticks);
    }

    /**
     * Hides a market body from the layout as well as from view: a {@code StackPane} sizes
     * itself to every managed child, so an invisible ladder would still make room for
     * itself under a pair of LMSR cards.
     */
    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** {@code 100} rather than {@code 100.00}; b and d are whole numbers in every file. */
    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : Widgets.money(value);
    }
}
