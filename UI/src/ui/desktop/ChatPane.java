package ui.desktop;

import engine.dto.ChatMessage;
import engine.dto.EventView;
import engine.dto.UserView;
import engine.service.Market;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The selected event's chat: everything everyone connected has said about it, oldest at the
 * top, and a line to add to it.
 *
 * <p>Only a window with other people in it shows one ({@link Host#hasChat()}), which is the
 * exercise-3 client: the conversation lives on the server, every client reads the same one,
 * and the poll is what brings another client's line into this window. The pane reads it
 * through {@code Market.getChatMessages} like any other figure, so it is redrawn by the
 * ordinary {@code refresh()} and owns no timer of its own.
 *
 * <p><b>It appends rather than rebuilds.</b> A refresh happens on every poll that moved
 * anything, and on every click; redrawing every line each time would reset the scroll
 * position under someone reading back. So the pane remembers the last message it drew and
 * adds only what came after it, and it scrolls to the foot only when the reader was already
 * there, so a new line does not drag away someone who has scrolled up.
 */
class ChatPane extends VBox {

    /** Tall enough for a handful of lines, short enough to leave the market above it in view. */
    private static final double LINES_HEIGHT = 180;

    /** How many author colours the sheet declares: {@code .chat-c0} to {@code .chat-c14}. */
    private static final int AUTHOR_COLOURS = 15;

    /** How many lines the pane keeps drawn; the server keeps the same number per event. */
    private static final int KEPT = 500;

    /** The moment a line was posted, in the window's own zone. The date is in the tooltip. */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final Host app;

    private final Label count = Widgets.pill("0", "count");
    private final VBox lines = new VBox(6);
    private final ScrollPane scroller = new ScrollPane(lines);
    private final Label empty = Widgets.label("Nobody has said anything about this event yet.", "ladder-empty");
    private final TextField input = new TextField();
    private final Button send = Widgets.button("Send", "small");

    private Integer eventId;
    private String eventName;
    private long lastDrawn;
    private int drawn;

    ChatPane(Host app) {
        this.app = app;
        getStyleClass().add("framed");

        HBox head = Widgets.row(8, Widgets.tiny("chat"), count, Widgets.grower(),
                Widgets.note("everyone connected sees this"));
        head.setPadding(Widgets.pad(8, 10, 6, 10));

        lines.setPadding(Widgets.pad(8, 10, 8, 10));
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPrefHeight(LINES_HEIGHT);
        scroller.setMinHeight(LINES_HEIGHT);
        scroller.getStyleClass().add("chat-lines");

        input.setPromptText("Say something about this event");
        // Only a length the engine would accept can be typed: a refusal for a message
        // already written is the worst moment to learn the limit.
        input.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= Market.CHAT_MAX_LENGTH ? change : null));
        HBox.setHgrow(input, Priority.ALWAYS);
        send.setMinWidth(Region.USE_PREF_SIZE);
        send.setOnAction(action -> post());
        input.setOnAction(action -> post());

        HBox form = Widgets.row(8, input, send);
        form.getStyleClass().add("footer-strip");

        getChildren().addAll(head, scroller, form);
        clear();
    }

    /** Shows {@code event}'s chat, adding whatever is new since the last call; nothing for null. */
    void show(EventView event) {
        if (event == null) {
            eventId = null;
            eventName = null;
            clear();
            return;
        }
        if (eventId == null || eventId != event.id()) {
            eventId = event.id();
            clear();
        }
        eventName = event.name();
        input.setDisable(false);
        send.setDisable(false);

        List<ChatMessage> newer = app.engine().getChatMessages(event.id(), lastDrawn);
        if (newer.isEmpty()) {
            return;
        }
        boolean atFoot = drawn == 0 || scroller.getVvalue() >= scroller.getVmax() - 0.01;
        if (drawn == 0) {
            lines.getChildren().clear();     // the empty-chat line
        }
        for (ChatMessage message : newer) {
            lines.getChildren().add(line(message));
            lastDrawn = message.id();
            drawn++;
        }
        while (drawn > KEPT) {
            lines.getChildren().remove(0);
            drawn--;
        }
        count.setText(String.valueOf(drawn));
        if (atFoot) {
            scroller.applyCss();
            scroller.layout();
            scroller.setVvalue(scroller.getVmax());
        }
    }

    private void clear() {
        lastDrawn = 0;
        drawn = 0;
        lines.getChildren().setAll(empty);
        count.setText("0");
        boolean none = eventId == null;
        input.setDisable(none);
        send.setDisable(none);
    }

    /**
     * Sends the line through {@code perform}, like every other command, so a refusal is
     * painted where every refusal is and the redraw that follows shows the line. The field
     * is emptied only once the server has taken it, so a refused line is still there to fix.
     */
    private void post() {
        if (eventId == null) {
            return;
        }
        int target = eventId;
        String name = eventName;
        app.perform(() -> {
            app.engine().postChatMessage(target, input.getText());
            input.clear();
            return "Sent to the chat of " + name + ".";
        });
        input.requestFocus();
    }

    /**
     * Which of the {@link #AUTHOR_COLOURS} an author is drawn in: their place in the market's
     * list of users, which is the order they joined it.
     *
     * <p>A place rather than a hash of the name, so the first fifteen people to join are
     * fifteen different colours rather than probably so; and the list is the server's, the
     * same in every client, so a person is the same colour in every window. Past fifteen
     * the colours come round again. Someone not in the list (there is no such author today,
     * since only a user can post) falls back to a hash of the name, which is still the same
     * everywhere, because {@code String.hashCode} is defined by the language.
     */
    private int colourOf(String author) {
        List<UserView> users = app.engine().getUsers();
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).name().equals(author)) {
                return i % AUTHOR_COLOURS;
            }
        }
        return Math.floorMod(author.hashCode(), AUTHOR_COLOURS);
    }

    private VBox line(ChatMessage message) {
        boolean mine = message.author().equals(app.engine().getCurrentUserName());
        Label author = Widgets.label(mine ? message.author() + " (you)" : message.author(),
                "chat-author", "chat-c" + colourOf(message.author()));
        Label when = Widgets.note(message.sentAt() == null ? "" : TIME.format(message.sentAt()));
        when.setTooltip(new Tooltip(Widgets.stamp(message.sentAt())));
        Label text = Widgets.label(message.text(), "chat-text");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        return new VBox(2, Widgets.row(8, author, when), text);
    }
}