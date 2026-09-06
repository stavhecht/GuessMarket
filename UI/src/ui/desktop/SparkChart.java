package ui.desktop;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * The design's line chart: a gridded plot of one or two series against the trade that
 * produced each point, every point dotted and the latest one marked.
 *
 * <p>Drawn on a {@link Canvas} rather than assembled from a JavaFX {@code LineChart},
 * because what the design asks for is a handful of hairlines and two polylines. A
 * {@code LineChart} would have to have most of its furniture styled away first, and its
 * axes insist on tick marks and padding this design does not have.
 *
 * <p>A gap in a series is a {@code NaN}: an order-book option that has not traded yet has
 * no price to draw, and the line simply starts later.
 *
 * <p><b>Every point is a dot, and the pointer can read one.</b> Each change in a series is
 * marked the way the opening point is, and the nearest dot within {@link #HIT} pixels of
 * the pointer is ringed and named in a small popup: the series, what it moved to, and when
 * it happened. Where two series hold the same value their dots are the same dot, so both
 * are ringed and both are named in the one popup. A canvas has no children to hang a {@code Tooltip} on, so the popup is a
 * node of this pane's own ({@link #tip}), unmanaged and mouse-transparent, placed beside
 * the dot; and what can be hovered is worked out from the drawing rather than from the
 * data, by keeping every plotted point's position as a {@link Spot} while it is painted.
 * That way the hit test cannot drift from what is on screen: both come out of the same
 * pass.
 */
class SparkChart extends Pane {

    /** One line on the chart. {@code colorToken} is a design token name, e.g. {@code accent}. */
    record Series(String name, String colorToken, List<Double> points) {
    }

    /** Where one point of one series landed, so the pointer can be asked what it is over. */
    private record Spot(int series, int index, double x, double y, double value) {
    }

    private static final double LEFT = 40;
    private static final double RIGHT = 10;
    private static final double TOP = 10;
    private static final double BOTTOM = 18;
    private static final int GRID_LINES = 5;

    /** The radius of a point's own dot, and of the ring the hovered one wears. */
    private static final double DOT = 2.4;
    private static final double RING = 5;

    /** How near the pointer has to come to a dot to read it. */
    private static final double HIT = 14;

    private final Canvas canvas = new Canvas();

    /** The popup: a line per series sharing the hovered dot, then the moment under them. */
    private final VBox tipValues = new VBox(1);
    private final Label tipWhen = new Label();
    private final VBox tip = new VBox(1, tipValues, tipWhen);

    private List<Series> series = List.of();
    private DoubleFunction<String> yLabel = value -> String.format("%.2f", value);
    private List<String> xLabels = List.of();
    private List<String> stamps = List.of();
    private String emptyMessage = "Nothing has traded yet.";

    /** Every dot of the last drawing, and which of them the pointer is on. */
    private final List<Spot> spots = new ArrayList<>();
    private int hoverSeries = -1;
    private int hoverIndex = -1;

    SparkChart(double height) {
        setMinHeight(height);
        setPrefHeight(height);

        tipWhen.getStyleClass().add("when");
        tip.getStyleClass().add("chart-tip");
        // Unmanaged so this pane never lays it out or sizes itself around it: the popup is
        // placed by hand beside a dot. Mouse-transparent so it cannot come between the
        // pointer and the dot that put it there, which would flicker it away and back.
        tip.setManaged(false);
        tip.setMouseTransparent(true);
        tip.setVisible(false);

        getChildren().addAll(canvas, tip);
        widthProperty().addListener((observable, was, now) -> redraw());
        heightProperty().addListener((observable, was, now) -> redraw());

        setOnMouseMoved(event -> hover(event.getX(), event.getY()));
        setOnMouseExited(event -> hover(Double.NaN, Double.NaN));
    }

    /**
     * Replaces what is plotted.
     *
     * @param yLabel how a value on the vertical axis reads: prices to two places, money
     *               grouped, whatever the panel above is measured in
     * @param xLabels one per point, of which only a few are drawn; empty for none
     */
    void show(List<Series> series, DoubleFunction<String> yLabel, List<String> xLabels) {
        show(series, yLabel, xLabels, List.of());
    }

    /**
     * The same, with a time against each point for the hover popup to read.
     *
     * @param stamps one per point, in the same order as {@code xLabels}, empty where a point
     *               has no time: a market's opening price is nobody's trade, and a session
     *               saved before trades were stamped has no time to give. Empty altogether
     *               for a chart whose points are not events in time.
     */
    void show(List<Series> series, DoubleFunction<String> yLabel,
              List<String> xLabels, List<String> stamps) {
        this.series = series;
        this.yLabel = yLabel;
        this.xLabels = xLabels;
        this.stamps = stamps;
        // What was under the pointer belonged to the old data; point 3 of the event just
        // deselected is not point 3 of this one.
        hoverSeries = -1;
        hoverIndex = -1;
        redraw();
    }

    void setEmptyMessage(String message) {
        this.emptyMessage = message;
    }

    private void redraw() {
        double width = getWidth();
        double height = getHeight();
        canvas.setWidth(width);
        canvas.setHeight(height);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        spots.clear();
        tip.setVisible(false);
        if (width <= LEFT + RIGHT || height <= TOP + BOTTOM) {
            return;
        }

        Theme theme = Theme.current();
        Color line = Color.web(theme.token("line"));
        Color faint = Color.web(theme.token("tx-3"));
        // One family name, and an installed one: Font.font falls back to the System font
        // for anything it does not have, exactly as the stylesheet does. See Theme.
        Font tick = Font.font(theme.token("mono"), 9);

        int points = longest();
        if (points == 0) {
            gc.setFill(faint);
            gc.setFont(Font.font(null, 12));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(emptyMessage, width / 2, height / 2);
            gc.setTextAlign(TextAlignment.LEFT);
            return;
        }

        double[] bounds = bounds();
        double low = bounds[0];
        double high = bounds[1];

        double plotWidth = width - LEFT - RIGHT;
        double plotHeight = height - TOP - BOTTOM;

        // --- grid and vertical scale ---
        gc.setFont(tick);
        gc.setLineWidth(1);
        for (int i = 0; i < GRID_LINES; i++) {
            double value = low + (high - low) * i / (GRID_LINES - 1.0);
            double y = Math.round(TOP + plotHeight - plotHeight * i / (GRID_LINES - 1.0)) + 0.5;
            gc.setStroke(line);
            gc.strokeLine(LEFT, y, width - RIGHT, y);
            gc.setFill(faint);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(yLabel.apply(value), LEFT - 6, y + 3);
        }

        // --- horizontal scale: every other label, so they never collide ---
        gc.setTextAlign(TextAlignment.CENTER);
        int step = Math.max(1, (int) Math.ceil(points / 8.0));
        for (int i = 0; i < points && i < xLabels.size(); i += step) {
            gc.setFill(faint);
            gc.fillText(xLabels.get(i), x(i, points, plotWidth), height - 5);
        }
        gc.setTextAlign(TextAlignment.LEFT);

        // --- the series themselves ---
        Color surface = Color.web(theme.token("surface"));
        Spot hovered = null;
        for (int s = 0; s < series.size(); s++) {
            Series one = series.get(s);
            Color colour = Color.web(theme.token(one.colorToken()));
            gc.setStroke(colour);
            gc.setLineWidth(1.6);

            boolean drawing = false;
            double lastX = 0;
            double lastY = 0;
            gc.beginPath();
            for (int i = 0; i < one.points().size(); i++) {
                Double value = one.points().get(i);
                if (value == null || value.isNaN()) {
                    drawing = false;
                    continue;
                }
                double px = x(i, points, plotWidth);
                double py = TOP + plotHeight - plotHeight * ratio(value, low, high);
                if (drawing) {
                    gc.lineTo(px, py);
                } else {
                    gc.moveTo(px, py);
                    drawing = true;
                }
                // Kept as the line is walked rather than in a second pass, so a dot cannot
                // sit anywhere but on the point that was actually drawn.
                Spot spot = new Spot(s, i, px, py, value);
                spots.add(spot);
                if (s == hoverSeries && i == hoverIndex) {
                    hovered = spot;
                }
                lastX = px;
                lastY = py;
            }
            gc.stroke();

            // Every change gets the mark the opening point has; the latest one keeps the
            // larger ringed dot that says where the series has got to.
            for (Spot spot : spots) {
                if (spot.series() == s) {
                    gc.setFill(colour);
                    gc.fillOval(spot.x() - DOT, spot.y() - DOT, DOT * 2, DOT * 2);
                }
            }
            if (drawing) {
                gc.setFill(colour);
                gc.fillOval(lastX - 3, lastY - 3, 6, 6);
                gc.setFill(surface);
                gc.fillOval(lastX - 1.4, lastY - 1.4, 2.8, 2.8);
            }
        }

        // --- what the pointer is on ---
        if (hovered != null) {
            // Two options at the same price put their dots in the same place, and one
            // window has to answer for both: what is ringed and what is named is every
            // series that shares the dot, not only the series the pointer was nearest to.
            List<Spot> together = sharing(hovered);
            for (Spot spot : together) {
                Color colour = Color.web(theme.token(series.get(spot.series()).colorToken()));
                gc.setFill(surface);
                gc.fillOval(spot.x() - RING, spot.y() - RING, RING * 2, RING * 2);
                gc.setStroke(colour);
                gc.setLineWidth(1.6);
                gc.strokeOval(spot.x() - RING, spot.y() - RING, RING * 2, RING * 2);
                gc.setFill(colour);
                gc.fillOval(spot.x() - DOT, spot.y() - DOT, DOT * 2, DOT * 2);
            }
            placeTip(together, hovered);
        }
    }

    /**
     * Every point drawn on top of {@code spot}: the same point of the series beside it,
     * landed within a dot's width of the same place. In series order, so the popup reads
     * the way the legend does.
     */
    private List<Spot> sharing(Spot spot) {
        List<Spot> together = new ArrayList<>();
        for (Spot other : spots) {
            if (other.index() == spot.index() && Math.abs(other.y() - spot.y()) <= DOT) {
                together.add(other);
            }
        }
        return together;
    }

    // --- hovering ---

    /**
     * Reads the dot nearest {@code (x, y)}, if the pointer has come close enough to one.
     *
     * <p>A redraw is what shows it, because the ring is painted on the canvas with
     * everything else, so this only records what is under the pointer and asks for one.
     * Nothing is redrawn while the pointer moves within the same dot, or across empty
     * plot, which is what keeps a mouse-move handler off the paint path.
     *
     * <p>{@code NaN} coordinates mean the pointer has left: no dot is near either.
     */
    private void hover(double x, double y) {
        Spot nearest = null;
        double best = HIT * HIT;
        for (Spot spot : spots) {
            double dx = spot.x() - x;
            double dy = spot.y() - y;
            double distance = dx * dx + dy * dy;
            if (distance <= best) {
                best = distance;
                nearest = spot;
            }
        }
        int series = nearest == null ? -1 : nearest.series();
        int index = nearest == null ? -1 : nearest.index();
        if (series == hoverSeries && index == hoverIndex) {
            return;
        }
        hoverSeries = series;
        hoverIndex = index;
        redraw();
    }

    /**
     * Writes the popup and puts it beside {@code spot}: above and to the right, and on
     * whichever other side it would otherwise hang off the plot.
     *
     * <p>It is sized before it is placed ({@code applyCss} then {@code autosize}), because
     * an unmanaged node has no size until something asks for one, and where it goes depends
     * on how wide the figures made it.
     */
    private void placeTip(List<Spot> together, Spot spot) {
        tipValues.getChildren().clear();
        for (Spot one : together) {
            Label line = new Label(series.get(one.series()).name() + "   " + yLabel.apply(one.value()));
            line.getStyleClass().add("value");
            tipValues.getChildren().add(line);
        }
        String when = spot.index() < stamps.size() ? stamps.get(spot.index()) : "";
        tipWhen.setText(when);
        // A point with no time is the market's opening price, or a trade from a session
        // saved before trades carried one: the figure alone, rather than an empty line.
        tipWhen.setVisible(!when.isEmpty());
        tipWhen.setManaged(!when.isEmpty());

        tip.setVisible(true);
        tip.applyCss();
        tip.autosize();
        double width = tip.getWidth();
        double height = tip.getHeight();

        double px = spot.x() + 10;
        if (px + width > getWidth()) {
            px = spot.x() - 10 - width;
        }
        double py = spot.y() - height - 8;
        if (py < 0) {
            py = spot.y() + 10;
        }
        tip.relocate(clamp(px, getWidth() - width), clamp(py, getHeight() - height));
    }

    private static double clamp(double value, double most) {
        return Math.max(0, Math.min(value, Math.max(0, most)));
    }

    /** The legend the design puts in the panel header: a dot and the series name. */
    static javafx.scene.Node legend(String name, String colorToken) {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5);
        dot.setFill(Color.web(Theme.current().token(colorToken)));
        return Widgets.row(5, dot, Widgets.faint(name));
    }

    private static double x(int index, int points, double plotWidth) {
        return points == 1 ? LEFT + plotWidth / 2 : LEFT + plotWidth * index / (points - 1.0);
    }

    private static double ratio(double value, double low, double high) {
        return high - low < 1e-12 ? 0.5 : (value - low) / (high - low);
    }

    private int longest() {
        int longest = 0;
        for (Series one : series) {
            longest = Math.max(longest, one.points().size());
        }
        return longest;
    }

    /**
     * The band to draw: the data's own range, padded by a tenth of itself so a line never
     * runs along the very top of the plot, and never flat when every point is equal.
     */
    private double[] bounds() {
        List<Double> values = new ArrayList<>();
        for (Series one : series) {
            for (Double value : one.points()) {
                if (value != null && !value.isNaN()) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            return new double[] { 0, 1 };
        }
        double low = values.get(0);
        double high = values.get(0);
        for (double value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        if (high - low < 1e-9) {
            double nudge = Math.max(Math.abs(high) * 0.05, 0.05);
            return new double[] { low - nudge, high + nudge };
        }
        double padding = (high - low) * 0.1;
        return new double[] { low - padding, high + padding };
    }
}
