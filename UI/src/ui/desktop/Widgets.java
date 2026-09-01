package ui.desktop;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Function;

/**
 * The pieces every screen of the desktop app is assembled from: the design's components,
 * one factory each, so a panel head or a status pill is built the same way everywhere.
 *
 * <p>This is also where {@code %.2f} is applied for the desktop UI, the same way
 * {@link ui.console.OutputFormatter} is the only place the console applies it: the engine
 * carries full precision right up to the moment something is drawn. Figures are grouped
 * with thousands separators here because that is how the design shows them.
 *
 * <p>Small-caps labels are uppercased in Java rather than in CSS, because JavaFX has no
 * {@code text-transform}, and the design uses it for every panel header and column key.
 */
final class Widgets {

    /** What a price or a balance looks like on screen. */
    static final String MONEY = "%,.2f";

    /** Shown where a figure has no value yet: an option nobody has quoted, say. */
    static final String NONE = "–";

    /** Where {@link #followMoney} remembers what a label is already following. */
    private static final String FOLLOWING = "gm-following";

    private Widgets() {
    }

    // --- figures ---

    static String money(double amount) {
        return String.format(MONEY, amount);
    }

    /** Money that may not exist yet: a best bid with no bids behind it reads as a dash. */
    static String money(Double amount) {
        return amount == null ? NONE : money(amount.doubleValue());
    }

    /** A price in the [0,1] band the design shows to two places and never groups. */
    static String price(Double value) {
        return value == null ? NONE : String.format("%.2f", value);
    }

    static String shares(long count) {
        return String.format("%,d", count);
    }

    static String percent(double rate) {
        return String.format("%.2f%%", rate * 100);
    }

    /** A gain or a loss, always signed, using the design's minus sign rather than a hyphen. */
    static String signed(double amount) {
        if (amount > 0) {
            return "+" + money(amount);
        }
        return amount < 0 ? "−" + money(-amount) : money(0);
    }

    static String moveClass(double amount) {
        return amount > 0 ? "up" : amount < 0 ? "down" : "faint";
    }

    // --- text ---

    static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    /** A panel subject: 15 / 600. */
    static Label subject(String text) {
        return label(text, "h-panel");
    }

    /** A section title: 13 / 600. */
    static Label section(String text) {
        return label(text, "h-section");
    }

    /** A panel header: 11 / 650, upper case. */
    static Label caps(String text) {
        return label(text.toUpperCase(), "h-caps");
    }

    /** The smallest key in the design: a column key or a strip label, mono and upper case. */
    static Label tiny(String text) {
        return label(text.toUpperCase(), "h-tiny");
    }

    static Label muted(String text) {
        return label(text, "muted");
    }

    static Label faint(String text) {
        return label(text, "faint");
    }

    static Label note(String text) {
        return label(text, "note");
    }

    /** A figure, so it lines up with the tables around it. */
    static Label figure(String text) {
        return label(text, "num");
    }

    /** A status pill. {@code kind} is one of ok, off, warn, mark, plain, count. */
    static Label pill(String text, String kind) {
        return label(text, "pill", kind);
    }

    /** The pill an event's status becomes: green while it is running, grey once it is settled. */
    static Label statusPill(String status) {
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        return pill(active ? "Active" : "Closed", active ? "ok" : "off");
    }

    // --- controls ---

    static Button button(String text, String... styleClasses) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styleClasses);
        return button;
    }

    /**
     * Points a label at a live money figure, letting go of whatever it was showing before.
     *
     * <p>The label stops being written to and starts being <em>bound</em>, so a refresh that
     * did not move this figure does not touch it: the property fires only on a real change.
     * Re-pointing is why the unbind comes first: the figure belongs to whichever event or
     * user is selected, and the selection moves.
     *
     * @param figure the property to follow, or {@code null} for nothing selected
     */
    static void followMoney(Label label, ObservableValue<? extends Number> figure) {
        // Re-pointing at the same figure has to be free, because the selection path calls
        // this on every refresh: unbinding and rebinding would write the text out again and
        // undo the whole point of binding it.
        if (label.getProperties().get(FOLLOWING) == figure) {
            return;
        }
        label.textProperty().unbind();
        if (figure == null) {
            label.getProperties().remove(FOLLOWING);
            label.setText(NONE);
            return;
        }
        label.getProperties().put(FOLLOWING, figure);
        label.textProperty().bind(figure.map(value -> money(value.doubleValue())));
    }

    static Button tip(Button button, String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(350));
        button.setTooltip(tooltip);
        return button;
    }

    static TextField quantityField(String prompt, String value, double width) {
        TextField field = new TextField(value);
        field.getStyleClass().add("qty");
        field.setPromptText(prompt);
        field.setPrefWidth(width);
        field.setMinWidth(width);
        return field;
    }

    /** The design's filter control: a small labelled drop-down in the sub-bar. */
    static <T> HBox filter(String name, ComboBox<T> choices, double width) {
        choices.setPrefWidth(width);
        choices.setMinWidth(width);
        return row(6, Pos.CENTER_LEFT, tiny(name), choices);
    }

    // --- layout ---

    static HBox row(double spacing, Node... children) {
        return row(spacing, Pos.CENTER_LEFT, children);
    }

    static HBox row(double spacing, Pos alignment, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(alignment);
        return box;
    }

    static VBox column(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    static Region grower() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** A fixed gap, for the places the design spaces things without a spring. */
    static Region gap(double width) {
        Region spacer = new Region();
        spacer.setMinWidth(width);
        spacer.setPrefWidth(width);
        return spacer;
    }

    static Region hairline() {
        Region line = new Region();
        line.getStyleClass().add("divider");
        return line;
    }

    /** One bordered panel: a header row, then whatever fills the rest of it. */
    static VBox panel(Node head, Node body) {
        VBox panel = new VBox(head, body);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(body, Priority.ALWAYS);
        return panel;
    }

    /** The header strip of a panel: an upper-case subject, a count, then whatever else. */
    static HBox panelHead(String title, String count, Node... trailing) {
        HBox head = row(8, caps(title));
        if (count != null) {
            head.getChildren().add(pill(count, "count"));
        }
        head.getChildren().add(grower());
        head.getChildren().addAll(trailing);
        head.getStyleClass().add("panel-head");
        return head;
    }

    /** A key over its value, the shape the design uses for every small statistic. */
    static VBox stacked(String key, Label value) {
        VBox box = new VBox(2, tiny(key), value);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** A key beside its value, on one line, for the detail grids. */
    static VBox keyValue(String key, Node value) {
        VBox box = new VBox(3, tiny(key), value);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(0);
        return box;
    }

    static VBox card(Node... children) {
        VBox box = new VBox(6, children);
        box.getStyleClass().add("card");
        return box;
    }

    /** A block with the design's card border but no fill of its own; tables live in these. */
    static VBox framed(Node... children) {
        VBox box = new VBox(children);
        box.getStyleClass().add("framed");
        return box;
    }

    static Insets pad(double top, double right, double bottom, double left) {
        return new Insets(top, right, bottom, left);
    }

    // --- tables ---

    static <S> TableView<S> table(String emptyMessage) {
        TableView<S> table = new TableView<>();
        table.setPlaceholder(faint(emptyMessage));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    /**
     * A read-only column that pulls its text straight out of the row.
     *
     * <p>Written as a lambda rather than {@code PropertyValueFactory} because every row in
     * this app is a record from {@code engine.dto}, and records have no JavaBean getters
     * for it to reflect on.
     */
    static <S> TableColumn<S, String> col(String title, double width, Function<S, String> text) {
        return styledCol(title, width, text, row -> null);
    }

    /** A column of figures: right-aligned and monospaced, so the decimal points line up. */
    static <S> TableColumn<S, String> numCol(String title, double width, Function<S, String> text) {
        return overFigures(styledCol(title, width, text, row -> "numeric"));
    }

    /**
     * Puts a column's <em>heading</em> over the right edge of its figures, where the cells
     * themselves already sit.
     *
     * <p>A cell's alignment comes from the class the cell factory hangs on it, which the
     * header knows nothing about, so a numeric column drew right-aligned figures under a
     * left-aligned title. This marks the column itself, and JavaFX copies a
     * {@code TableColumn}'s style classes onto its {@code TableColumnHeader}, which is what
     * the {@code .column-header.numeric .label} rule in {@code guessmarket.css} then catches.
     *
     * <p>{@link #numCol} does it for itself. Call it by hand for a {@link #styledCol} whose
     * per-row classes are numeric, as the P/L column on the Users screen is: its cells are
     * right-aligned <em>and</em> coloured by sign.
     */
    static <S, T> TableColumn<S, T> overFigures(TableColumn<S, T> column) {
        column.getStyleClass().add("numeric");
        return column;
    }

    /** The narrow left-hand id column: mono, and quieter than the rest of the row. */
    static <S> TableColumn<S, String> idCol(String title, double width, Function<S, String> text) {
        return styledCol(title, width, text, row -> "ident");
    }

    /**
     * A column whose cells carry a style class chosen per row: how a profit turns green
     * and a loss red without the row itself being coloured.
     */
    static <S> TableColumn<S, String> styledCol(String title, double width,
                                                Function<S, String> text,
                                                Function<S, String> cellClass) {
        TableColumn<S, String> column = new TableColumn<>(title.toUpperCase());
        column.setPrefWidth(width);
        column.setMinWidth(Math.min(width, 48));
        column.setSortable(false);
        column.setReorderable(false);
        column.setCellValueFactory(row -> new SimpleStringProperty(text.apply(row.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("numeric", "ident", "up", "down", "faint", "accented", "strong");
                setText(empty ? null : value);
                if (!empty) {
                    String extra = cellClass.apply(getTableRow() == null ? null : getTableRow().getItem());
                    if (extra != null) {
                        getStyleClass().addAll(extra.split(" "));
                    }
                }
            }
        });
        return column;
    }

    /** A column whose cell is a node: a status pill in a table, say. */
    static <S> TableColumn<S, S> nodeCol(String title, double width, Function<S, Node> node) {
        TableColumn<S, S> column = new TableColumn<>(title.toUpperCase());
        column.setPrefWidth(width);
        column.setMinWidth(Math.min(width, 48));
        column.setSortable(false);
        column.setReorderable(false);
        column.setCellValueFactory(row -> new SimpleObjectProperty<>(row.getValue()));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(S value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                setGraphic(empty || value == null ? null : node.apply(value));
            }
        });
        return column;
    }
}
