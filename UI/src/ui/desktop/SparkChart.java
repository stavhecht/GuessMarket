package ui.desktop;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * The design's line chart: a gridded plot of one or two series against the trade that
 * produced each point, with the latest point marked.
 *
 * <p>Drawn on a {@link Canvas} rather than assembled from a JavaFX {@code LineChart},
 * because what the design asks for is a handful of hairlines and two polylines. A
 * {@code LineChart} would have to have most of its furniture styled away first, and its
 * axes insist on tick marks and padding this design does not have.
 *
 * <p>A gap in a series is a {@code NaN}: an order-book option that has not traded yet has
 * no price to draw, and the line simply starts later.
 */
class SparkChart extends Pane {

    /** One line on the chart. {@code colorToken} is a design token name, e.g. {@code accent}. */
    record Series(String name, String colorToken, List<Double> points) {
    }

    private static final double LEFT = 40;
    private static final double RIGHT = 10;
    private static final double TOP = 10;
    private static final double BOTTOM = 18;
    private static final int GRID_LINES = 5;

    private final Canvas canvas = new Canvas();

    private List<Series> series = List.of();
    private DoubleFunction<String> yLabel = value -> String.format("%.2f", value);
    private List<String> xLabels = List.of();
    private String emptyMessage = "Nothing has traded yet.";

    SparkChart(double height) {
        setMinHeight(height);
        setPrefHeight(height);
        getChildren().add(canvas);
        widthProperty().addListener((observable, was, now) -> redraw());
        heightProperty().addListener((observable, was, now) -> redraw());
    }

    /**
     * Replaces what is plotted.
     *
     * @param yLabel how a value on the vertical axis reads: prices to two places, money
     *               grouped, whatever the panel above is measured in
     * @param xLabels one per point, of which only a few are drawn; empty for none
     */
    void show(List<Series> series, DoubleFunction<String> yLabel, List<String> xLabels) {
        this.series = series;
        this.yLabel = yLabel;
        this.xLabels = xLabels;
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
        if (width <= LEFT + RIGHT || height <= TOP + BOTTOM) {
            return;
        }

        Theme theme = Theme.current();
        Color line = Color.web(theme.token("line"));
        Color faint = Color.web(theme.token("tx-3"));
        Font tick = Font.font(theme.token("mono").split(",")[0].replace("'", ""), 9);

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
        for (Series one : series) {
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
                lastX = px;
                lastY = py;
            }
            gc.stroke();

            if (drawing) {
                gc.setFill(colour);
                gc.fillOval(lastX - 3, lastY - 3, 6, 6);
                gc.setFill(Color.web(theme.token("surface")));
                gc.fillOval(lastX - 1.4, lastY - 1.4, 2.8, 2.8);
            }
        }
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
