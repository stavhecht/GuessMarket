package ui.desktop;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.function.DoubleFunction;

/**
 * A figure that rolls to its new value instead of jumping to it.
 *
 * <p>It animates the <em>value</em> and formats each step, rather than sliding digits: the
 * figures here are money and probabilities, they are grouped and rounded by {@link Widgets},
 * and a rolling odometer would have to take that formatting apart. Counting is also the
 * honest picture of what happened: the number really did pass through those values.
 *
 * <p><b>A ticker follows a property; it is never told a value.</b> That is what makes it
 * correct. The screens are polled: every command ends in {@code DesktopApp.refresh()},
 * which rereads the engine and writes the same figures again, and a tab switch, a filter and
 * a selection all do the same, so being handed a value says nothing about whether anything
 * moved. A property does: {@code set} with an unchanged value fires nothing at all. So
 * {@link #follow} subscribes, and the two cases fall out of the subscription itself:
 *
 * <ul>
 *   <li>the property <b>fires</b>, the figure genuinely moved, so roll to it;
 *   <li>the ticker is <b>re-pointed</b> at a different property, a different event's price
 *       or a different user's balance, which is not this one's next value, so land on it
 *       immediately. Rolling here would invent a move that never happened.
 * </ul>
 *
 * <p>Re-pointed at the property it is already following, it does nothing, so the selection
 * path may call {@link #follow} on every refresh without consequence, which is exactly how
 * it is used.
 *
 * <p>Each ticker owns one {@link Timeline} and restarts it from wherever the last one had
 * got to, so a burst of trades reads as one continuous run rather than a stutter.
 *
 * <p>A figure on a screen that is not in front of anyone can be told to keep its animation
 * until it is: see {@link #onlyWhile}.
 *
 * <p>Every ticker in the window can be switched off at once through {@link #animated()},
 * which the check box beside the tabs is bound to.
 */
final class Ticker {

    /** Long enough to read as a movement, short enough not to delay the next command. */
    private static final Duration ROLL = Duration.millis(420);

    /**
     * Whether figures roll to their new value or simply land on it. One switch for the whole
     * window, because it is a preference about the window and not about any one figure, and
     * the tickers are built in four different places (both screens, {@link LmsrPane} and
     * {@link OrderBookPane}) which would otherwise each have to be handed it.
     *
     * <p>It is read at the moment a figure moves, so turning it off stops the next roll
     * rather than any already running: a roll is over in {@link #ROLL}, and stopping one
     * mid-count would leave the digits at whatever they had reached until the next move.
     *
     * <p>Only the animation is switched. Every figure is written out either way, so a window
     * with this off is never wrong, only still, exactly as {@link #onlyWhile} is.
     */
    private static final BooleanProperty ANIMATED = new SimpleBooleanProperty(true);

    /** The window-wide animation switch, for the control that turns it on and off. */
    static BooleanProperty animated() {
        return ANIMATED;
    }

    private final Label label;
    private final DoubleFunction<String> format;

    /** What is on screen right now, mid-roll included. Drives the text through a listener. */
    private final DoubleProperty rolling = new SimpleDoubleProperty();

    private Timeline timeline;

    /** Where the running roll is headed, so the same target twice does not restart it. */
    private double target;

    /** Whether a number rather than a dash is showing; a dash has no value to roll from. */
    private boolean showing;

    /** The figure being followed. Held so it can be unsubscribed when the ticker moves on. */
    private ObservableValue<? extends Number> figure;

    /** One instance, so the same listener object can be removed again. */
    private final ChangeListener<Number> onMoved = (observable, was, now) -> apply(now, true);

    /** When this reads false the roll waits; {@code null} means the figure is always in view. */
    private ObservableBooleanValue inView;

    /** A move that arrived while out of view, kept back until there is someone to see it. */
    private boolean held;
    private double heldValue;

    Ticker(Label label, DoubleFunction<String> format) {
        this.label = label;
        this.format = format;
        rolling.addListener((observable, was, now) -> label.setText(format.apply(now.doubleValue())));
    }

    /** A ticker over money: {@code 1,234.50}. */
    static Ticker money(Label label) {
        return new Ticker(label, Widgets::money);
    }

    /** A ticker over a price in the [0,1] band: {@code 0.52}. */
    static Ticker price(Label label) {
        return new Ticker(label, Widgets::price);
    }

    /** A ticker over a gain or a loss, always signed: {@code +1,234.50}. */
    static Ticker signed(Label label) {
        return new Ticker(label, Widgets::signed);
    }

    /**
     * What is on screen this instant, mid-roll included, for anything drawn from the same
     * figure, such as the meter under an LMSR price, which has to move with the digits
     * rather than jump to the answer while they are still counting.
     *
     * <p>Bind to it once. It is the same property throughout the ticker's life, so a
     * binding made in a constructor survives every figure the ticker is ever pointed at.
     */
    ReadOnlyDoubleProperty value() {
        return rolling;
    }

    /**
     * Points this ticker at the figure it should show, letting go of the last one.
     *
     * <p>The wildcard is what lets a nullable figure through: an order book's last traded
     * price is an {@code ObservableValue<Double>} that reads {@code null} until something
     * trades, and a {@code null} renders as {@link Widgets#NONE}, a dash, which is not a
     * zero and so is neither rolled to nor rolled from.
     *
     * @param figure the property to follow, or {@code null} to empty the label
     */
    void follow(ObservableValue<? extends Number> figure) {
        if (this.figure == figure) {
            return;     // already following it; the selection path calls this every refresh
        }
        if (this.figure != null) {
            this.figure.removeListener(onMoved);
        }
        this.figure = figure;
        if (figure == null) {
            clear();
            return;
        }
        figure.addListener(onMoved);
        apply(figure.getValue(), false);    // a figure this ticker was not on cannot be rolled to
    }

    /**
     * Holds this figure's animation back to whenever {@code inView} is true.
     *
     * <p>The two screens are tabs, and only one of them is in front of anyone at a time. A
     * purchase made on the Events tab moves the account balance on the Users tab, which then
     * rolls to its new value with nobody watching, and by the time that tab is opened the
     * roll is long finished, so the money appears to have always been what it now is. Gated
     * like this, the movement waits and plays on arrival, which is the only moment it can
     * actually be seen.
     *
     * <p>Only the <em>animation</em> waits. A figure that would not have rolled anyway (the
     * first one shown, or one this ticker has just been re-pointed at) is written out
     * immediately, so a hidden screen is never wrong, only still.
     */
    void onlyWhile(ObservableBooleanValue inView) {
        this.inView = inView;
        inView.addListener((observable, was, now) -> {
            if (now && held) {
                held = false;
                apply(heldValue, true);
            }
        });
    }

    /**
     * Puts {@code value} on screen.
     *
     * @param mayRoll whether this is a movement of the figure already showing (the property
     *                fired) rather than a different figure arriving (the ticker was
     *                re-pointed), which is the whole of the roll-or-land decision
     */
    private void apply(Number value, boolean mayRoll) {
        if (value == null) {
            stop();
            held = false;
            showing = false;
            label.setText(Widgets.NONE);
            return;
        }
        double wanted = value.doubleValue();

        // Where the figure already is, or is already on its way to. Either way there is
        // nothing to do if that is where it has just been asked to go.
        double heading = timeline != null ? target : rolling.get();
        if (showing && heading == wanted) {
            // Nothing to do, and nothing to wait for either: a move that was being kept
            // back for this screen's next appearance has been undone by this one, and
            // playing it on arrival would count the figure up to a value it no longer has.
            held = false;
            return;
        }

        // A figure that is not a number cannot be interpolated, and neither can the first
        // one shown: there is nothing behind it to count up from. Nor does anything roll
        // while the window's animations are switched off.
        boolean wouldRoll = mayRoll && showing && ANIMATED.get()
                && Double.isFinite(wanted) && Double.isFinite(rolling.get());

        if (wouldRoll && inView != null && !inView.get()) {
            held = true;                // kept for the moment this screen is opened
            heldValue = wanted;
            return;
        }

        // Anything that was waiting to be seen is superseded by this.
        held = false;
        showing = true;
        stop();
        if (!wouldRoll) {
            land(wanted);
            return;
        }
        target = wanted;
        timeline = new Timeline(new KeyFrame(ROLL,
                new KeyValue(rolling, wanted, Interpolator.EASE_OUT)));
        // The last pulse of a Timeline lands on the key value, but only to within the
        // pulse, and this figure is the one the user reads off, so it is set exactly.
        timeline.setOnFinished(done -> {
            timeline = null;
            land(wanted);
        });
        timeline.play();
    }

    /** Empties the figure and stops following anything: nothing is selected. */
    void clear() {
        clear(Widgets.NONE);
    }

    /**
     * The same, for a figure the design leaves blank rather than dashed when there is
     * nothing to show: the delta beside a potential outcome has no dash of its own.
     */
    void clear(String placeholder) {
        if (figure != null) {
            figure.removeListener(onMoved);
            figure = null;
        }
        stop();
        held = false;
        showing = false;
        // Anything drawn from this figure, a meter bound to value(), has to empty with it,
        // so the rolling value is zeroed first and the placeholder written over the top of
        // the text that zeroing produces.
        rolling.set(0);
        label.setText(placeholder);
    }

    /**
     * Puts a value on screen with no animation, through the same listener the roll uses.
     *
     * <p>Setting the property is not enough on its own: it fires nothing when the value is
     * already there, which is the case at the end of every roll, so the text is written
     * directly as well.
     */
    private void land(double value) {
        rolling.set(value);
        label.setText(format.apply(value));
    }

    private void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }
}
