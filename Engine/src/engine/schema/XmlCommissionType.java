package engine.schema;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The two values {@code <comision type="...">} may take.
 *
 * <p>{@code @XmlEnumValue} carries the spelling used in the file, so JAXB matches the
 * text itself and no string comparison is needed downstream. The engine's own
 * {@code CommissionMethod} is deliberately kept out of this package: this enum describes
 * the file, and the loader maps it to the domain.
 */
@XmlEnum
public enum XmlCommissionType {

    @XmlEnumValue("on-purchase")
    ON_PURCHASE("on-purchase"),

    @XmlEnumValue("on-close")
    ON_CLOSE("on-close");

    private final String xmlValue;

    XmlCommissionType(String xmlValue) {
        this.xmlValue = xmlValue;
    }

    /** The spelling this value has in the file. */
    public String xmlValue() {
        return xmlValue;
    }

    /** The legal spellings, for error messages: {@code "on-purchase" or "on-close"}. */
    public static String legalValues() {
        return Arrays.stream(values())
                .map(type -> "'" + type.xmlValue + "'")
                .collect(Collectors.joining(" or "));
    }
}