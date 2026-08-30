package ui.desktop;

import engine.dto.EventView;
import engine.model.CommissionMethod;
import engine.service.MarketEngine;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

/**
 * The form behind the Events screen's Create button: an event described the way the XSD
 * describes one, for the user who is acting to add to the market already loaded.
 *
 * <p>The dialog hands back a <em>command</em> rather than a filled-in object, and that is
 * deliberate: nothing here decides whether an event is legal. It reads what was typed, turns
 * the numbers into numbers, and calls the engine, which owns every rule — the same rules
 * {@code XmlEventLoader} holds a file to. So a bad commission or two options with the same
 * name are refused in one place, and the reason lands in the status bar through
 * {@code DesktopApp.perform} like any other rejected command.
 *
 * <p>Whoever creates an event becomes its Market Maker. For an order book that means paying
 * the initial investment out of their own balance, so the form says so rather than letting
 * the money leave without warning.
 */
class CreateEventDialog extends Dialog<Function<MarketEngine, String>> {

    /**
     * How tall the form may grow before it scrolls instead. Chosen to leave the header and
     * the button bar on screen on a short laptop display, where the whole dialog has perhaps
     * 700 points to live in.
     */
    private static final double FORM_MAX_HEIGHT = 430;

    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final TextField commission = Widgets.quantityField("0", "5", 70);

    private final ToggleGroup commissionWhen = new ToggleGroup();
    private final ToggleButton onPurchase = new ToggleButton("On purchase");
    private final ToggleButton onClose = new ToggleButton("On close");

    private final TextField optionA = new TextField();
    private final TextField optionB = new TextField();

    private final ToggleGroup method = new ToggleGroup();
    private final ToggleButton lmsrMethod = new ToggleButton("LMSR");
    private final ToggleButton bookMethod = new ToggleButton("Order book");

    private final TextField b = Widgets.quantityField("100", "100", 90);
    private final TextField initialInvestment = Widgets.quantityField("100", "100", 90);
    private final TextField baseValue = Widgets.quantityField("1", "1", 90);
    private final CheckBox allowMint = new CheckBox("Allow minting");

    private final VBox lmsrFields;
    private final VBox bookFields;

    CreateEventDialog(String creator) {
        setTitle("Create event");
        setHeaderText("A new market, run by " + creator);

        name.setPromptText("What is being predicted");
        description.setPromptText("A sentence about it");
        optionA.setPromptText("First outcome");
        optionB.setPromptText("Second outcome");

        pair(onPurchase, onClose, commissionWhen, onPurchase);
        pair(lmsrMethod, bookMethod, method, lmsrMethod);

        lmsrFields = fieldBlock(
                field("liquidity b", b,
                        "Larger b, flatter prices: a purchase moves them less."));
        bookFields = fieldBlock(
                field("initial investment", initialInvestment,
                        "Paid by " + creator + ", and returned as the opening shares."),
                field("base value d", baseValue,
                        "What one winning share pays. The book opens at half of it a side."),
                Widgets.row(8, Widgets.gap(0), allowMint));
        allowMint.setSelected(true);

        // Only the chosen method's fields are on screen, and unmanaged as well as hidden so
        // the dialog does not keep a hole where the other one would have been. pair() has
        // already made sure one of the two methods is always chosen.
        method.selectedToggleProperty().addListener((observable, was, now) -> {
            showBlock(lmsrFields, now == lmsrMethod);
            showBlock(bookFields, now == bookMethod);
        });
        showBlock(lmsrFields, true);
        showBlock(bookFields, false);

        VBox body = Widgets.column(12,
                field("event name", name, null),
                field("description", description, null),
                Widgets.row(18,
                        field("commission %", commission, null),
                        field("charged", Widgets.row(6, onPurchase, onClose), null)),
                Widgets.row(18, field("option 1", optionA, null), field("option 2", optionB, null)),
                field("traded by", Widgets.row(6, lmsrMethod, bookMethod), null),
                lmsrFields,
                bookFields);
        body.setPadding(Widgets.pad(4, 4, 4, 4));
        body.setMinWidth(460);

        // An order book asks three more questions than an LMSR event does, which is enough
        // to push the dialog past the bottom of a laptop screen and take the Create button
        // with it. Capped and scrolled, the buttons stay where they are and the form moves
        // instead. maxHeight rather than a fixed one, so the shorter LMSR form still sizes
        // to itself rather than opening with empty space under it.
        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setMaxHeight(FORM_MAX_HEIGHT);
        scroller.setMinWidth(0);

        getDialogPane().setContent(scroller);
        getDialogPane().getButtonTypes().setAll(
                new ButtonType("Create", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);

        setResultConverter(button -> button == null
                || button.getButtonData() != ButtonBar.ButtonData.OK_DONE ? null : this::create);
    }

    /**
     * Reads the form and issues the command. Runs inside {@code DesktopApp.perform}, so a
     * number that will not parse and an event the engine refuses are reported the same way.
     */
    private String create(MarketEngine engine) {
        int commissionPercent =
                DesktopApp.readWholeNumber(commission.getText(), "commission percentage", 0);
        CommissionMethod when = onClose.isSelected()
                ? CommissionMethod.ON_CLOSE : CommissionMethod.PER_PURCHASE;
        List<String> options = List.of(optionA.getText(), optionB.getText());

        EventView made = lmsrMethod.isSelected()
                ? engine.createLmsrEvent(name.getText(), description.getText(), commissionPercent,
                        when, options, DesktopApp.readPositiveDouble(b.getText(), "liquidity b"))
                : engine.createOrderBookEvent(name.getText(), description.getText(), commissionPercent,
                        when, options,
                        DesktopApp.readWholeNumber(initialInvestment.getText(), "initial investment", 0),
                        DesktopApp.readWholeNumber(baseValue.getText(), "base value", 1),
                        allowMint.isSelected());

        return String.format("Created event %d, '%s' — you are its market maker.",
                made.id(), made.name());
    }

    /** One labelled field, with the design's small caps key above it. */
    private static VBox field(String key, javafx.scene.Node control, String note) {
        VBox stacked = Widgets.column(4, Widgets.tiny(key), control);
        if (note != null) {
            stacked.getChildren().add(Widgets.note(note));
        }
        if (control instanceof Region region) {
            region.setMinWidth(0);
        }
        return stacked;
    }

    private static VBox fieldBlock(javafx.scene.Node... fields) {
        VBox block = Widgets.column(10, fields);
        block.getStyleClass().add("card");
        return block;
    }

    private static void showBlock(VBox block, boolean visible) {
        block.setVisible(visible);
        block.setManaged(visible);
    }

    /**
     * Two toggles that behave as one choice.
     *
     * <p>A {@code ToggleGroup} lets its selected button be clicked off again, which would
     * leave the form with no answer to a question that has to have one — so a deselection is
     * put straight back, the same way the trade form on the Users screen does it.
     */
    private static void pair(ToggleButton first, ToggleButton second, ToggleGroup group,
                             ToggleButton fallback) {
        for (ToggleButton toggle : List.of(first, second)) {
            toggle.setToggleGroup(group);
            toggle.getStyleClass().add("small");
        }
        fallback.setSelected(true);
        group.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });
    }
}
