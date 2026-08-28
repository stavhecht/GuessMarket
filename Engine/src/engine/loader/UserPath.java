package engine.loader;

import java.io.File;

/**
 * Turns a path as the user pasted it into one {@link File} can open.
 *
 * <p>The two platforms put different things on the clipboard for the same file, and both
 * arrive here through the same prompt:
 *
 * <ul>
 *   <li><b>Windows</b> — Explorer's "Copy as path" (Shift+right-click) wraps the path in
 *       double quotes: {@code "C:\Users\me\events.xml"}. Those quotes are part of the
 *       string Java sees, so the file is never found — and the {@code .xml}/{@code .gm}
 *       extension checks end up looking at a trailing {@code "} instead of an extension.</li>
 *   <li><b>macOS</b> — Cmd+Option+C pastes the path bare, but dragging the file into the
 *       terminal escapes every space with a backslash: {@code /Users/me/my\ events.xml}.</li>
 * </ul>
 *
 * <p>Unquoting is safe on both, so it is done on both. Unescaping is not: on Windows the
 * backslash is the separator, and {@code C:\Users} would come out as {@code C:Users}.
 * That is the one step that has to know which platform it is running on.
 */
public final class UserPath {

    /** On Windows {@code \} is the separator; everywhere else it is the shell's escape character. */
    private static final boolean WINDOWS = File.separatorChar == '\\';

    private UserPath() {
    }

    /** @return the path the user meant, or {@code null} if {@code raw} was {@code null} */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.trim();
        if (isQuoted(path)) {
            // Quoted means literal: whatever sits between the quotes is the path,
            // backslashes and all. This is the Windows "Copy as path" case.
            return path.substring(1, path.length() - 1);
        }
        // Bare, so on a Unix shell it may still carry the escapes a drag-and-drop added.
        return WINDOWS ? path : unescape(path);
    }

    /** Both ends, same quote character — one stray quote inside a name is not a wrapper. */
    private static boolean isQuoted(String path) {
        char first = path.isEmpty() ? 0 : path.charAt(0);
        return path.length() >= 2
                && (first == '"' || first == '\'')
                && path.charAt(path.length() - 1) == first;
    }

    /**
     * Drops the backslash from every {@code \x} pair, keeping {@code x}; a lone trailing
     * backslash is left as it is.
     *
     * <p>A macOS file name may itself contain a backslash, and dragging such a file in
     * produces it doubled ({@code \\}), which this collapses back to one — so the
     * round-trip is right for anything the shell escaped. Only a path typed by hand with
     * a single literal backslash in a name loses it.
     */
    private static String unescape(String path) {
        if (path.indexOf('\\') < 0) {
            return path;
        }
        StringBuilder unescaped = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' && i + 1 < path.length()) {
                c = path.charAt(++i);
            }
            unescaped.append(c);
        }
        return unescaped.toString();
    }
}