package engine.schema;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

/**
 * Tidies text as it comes out of the file: trims the ends and squeezes every run of
 * whitespace down to a single space.
 *
 * <p>Applied with {@code @XmlJavaTypeAdapter}, it takes care of the indentation and line
 * breaks an XML author is free to add — so a name split across lines in the file still
 * reads as one line in the engine, and no caller has to remember to trim.
 */
public class NormalizedTextAdapter extends XmlAdapter<String, String> {

    private static final String WHITESPACE_RUN = "\\s+";

    @Override
    public String unmarshal(String fromFile) {
        return fromFile == null ? null : fromFile.trim().replaceAll(WHITESPACE_RUN, " ");
    }

    @Override
    public String marshal(String value) {
        return value;
    }
}