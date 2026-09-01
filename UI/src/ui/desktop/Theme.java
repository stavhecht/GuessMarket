package ui.desktop;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three looks the window can wear, and the one stylesheet all of them share.
 *
 * <p>The sheet is {@code guessmarket.css}, sitting next to this class. Every colour in the
 * design is a token declared there as a JavaFX looked-up colour ({@code -gm-accent} and
 * friends), and a theme is one block that redefines the set, so switching a look is
 * switching a single style class on the scene root, not loading a second stylesheet.
 *
 * <p>A few things are painted from Java rather than styled: {@link SparkChart} draws on a
 * {@code Canvas}, {@link OrderBookPane} tints a depth bar inline, and {@code DesktopApp}
 * fills a couple of {@code Circle}s. Those read their colours through {@link #token}, which
 * is parsed out of the same stylesheet at class-load time, so the CSS file is the only
 * place a colour is written down, and a Java-painted node cannot drift from a styled one.
 *
 * <p>The exception is the two font families, which are not colours and so cannot be looked
 * up. Only Neon changes them, and only {@link SparkChart} reads one, so they are held here
 * rather than parsed.
 *
 * <p>JavaFX has no {@code text-transform} and no {@code letter-spacing}, so the two places
 * the design leans on them (the small caps labels, and Neon's shouting buttons) are
 * uppercased in Java by {@link Widgets} instead.
 */
enum Theme {

    LIGHT("Light", "theme-light", "'JetBrains Mono','SF Mono',Menlo,Consolas,monospace"),

    DARK("Dark", "theme-dark", "'JetBrains Mono','SF Mono',Menlo,Consolas,monospace"),

    NEON("Neon", "theme-neon", "'IBM Plex Mono','JetBrains Mono',Menlo,Consolas,monospace");

    private static final String SHEET_NAME = "guessmarket.css";

    /** The whole stylesheet, read once so the token blocks can be parsed out of it. */
    private static final String SHEET_TEXT = read();

    private static final String SHEET_URL = resource().toExternalForm();

    /** Every theme's class, so switching one can clear whichever was on before. */
    private static final List<String> ALL_CLASSES =
            List.of("theme-light", "theme-dark", "theme-neon");

    private static Theme current = LIGHT;

    private final String label;
    private final String styleClass;
    private final String mono;

    /**
     * Read on first use, not in the constructor: an enum's constants are built before its
     * static fields are assigned, so {@link #SHEET_TEXT} does not exist yet down there.
     */
    private Map<String, String> tokens;

    Theme(String label, String styleClass, String mono) {
        this.label = label;
        this.styleClass = styleClass;
        this.mono = mono;
    }

    /** Whichever theme the window is wearing now; inline styles read their colours off it. */
    static Theme current() {
        return current;
    }

    /**
     * The theme one of these names, or {@code null} for a name that is not one of them.
     *
     * <p>What a saved session stores is {@link #name()}, so this is how it comes back. It
     * has to tolerate anything: the string arrives out of a file the app cannot vouch for,
     * and a session written by a build with a theme this one does not have is a wrong look
     * rather than a broken session, so an unknown name leaves the window as it is instead
     * of throwing, which is what {@code Enum.valueOf} would do.
     */
    static Theme named(String name) {
        for (Theme theme : values()) {
            if (theme.name().equals(name)) {
                return theme;
            }
        }
        return null;
    }

    /** The value of one design token, e.g. {@code token("up-bg")}. */
    String token(String name) {
        if (tokens == null) {
            // Light is the base block, exactly as the stylesheet lays it out; the other two
            // are read as overrides on top of it.
            Map<String, String> read = colours(".root");
            read.putAll(colours("." + styleClass));
            read.put("mono", mono);
            tokens = read;
        }
        String value = tokens.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No such design token: " + name);
        }
        return value;
    }

    /** What the title-bar toggle says it will switch to next. */
    String label() {
        return label;
    }

    Theme next() {
        Theme[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Dresses {@code scene} in this theme, replacing whatever it was wearing. */
    void applyTo(Scene scene) {
        current = this;
        scene.getStylesheets().setAll(SHEET_URL);
        wear(scene.getRoot());
    }

    /**
     * Also dresses a dialog, which JavaFX gives a scene of its own; without this a
     * {@code ChoiceDialog} opens in the platform's default grey next to a dark window.
     *
     * <p>The theme class goes on the pane rather than on that scene's root: a looked-up
     * colour resolves at the nearest ancestor that declares it, so everything inside the
     * dialog finds this theme's palette before it reaches the root's default one.
     */
    void applyTo(DialogPane pane) {
        pane.getStylesheets().setAll(SHEET_URL);
        if (!pane.getStyleClass().contains("gm-dialog")) {
            pane.getStyleClass().add("gm-dialog");
        }
        wear(pane);
    }

    /** Puts this theme's class on {@code node}, taking off whichever one was there. */
    private void wear(Node node) {
        node.getStyleClass().removeAll(ALL_CLASSES);
        node.getStyleClass().add(styleClass);
    }

    // --- reading the stylesheet ---

    private static URL resource() {
        URL url = Theme.class.getResource(SHEET_NAME);
        if (url == null) {
            throw new IllegalStateException(SHEET_NAME + " is missing from the build. It sits "
                    + "beside Theme.java in ui/desktop and has to be copied to the class output "
                    + "as a resource.");
        }
        return url;
    }

    private static String read() {
        try (InputStream in = resource().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SHEET_NAME + ".", e);
        }
    }

    /**
     * The {@code -gm-name: value} declarations of one selector's block.
     *
     * <p>Only the block whose selector stands alone at the start of a line is read, which is
     * what separates {@code .theme-neon { ... }}, the token block, from the rules further
     * down the sheet that begin {@code .theme-neon .button, ...}.
     */
    private static Map<String, String> colours(String selector) {
        Matcher block = Pattern.compile("^" + Pattern.quote(selector) + "\\s*\\{([^}]*)}",
                Pattern.MULTILINE).matcher(SHEET_TEXT);
        if (!block.find()) {
            throw new IllegalStateException("No " + selector + " block in " + SHEET_NAME
                    + ": the design tokens are read out of it.");
        }
        Map<String, String> found = new LinkedHashMap<>();
        Matcher declaration = Pattern.compile("-gm-([a-z0-9-]+)\\s*:\\s*([^;]+);")
                .matcher(block.group(1));
        while (declaration.find()) {
            found.put(declaration.group(1), declaration.group(2).trim());
        }
        return found;
    }
}