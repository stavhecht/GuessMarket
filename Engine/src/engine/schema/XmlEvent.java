package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code <GM-event>}: a question, the two answers it can settle on, and the
 * market-maker settings the engine trades it with.
 *
 * <pre>{@code
 * <GM-event name="Earth Quake on Dead Sea">
 *   <id>3</id>
 *   <description>Will there be an earth quake ...</description>
 *   <comision type="on-purchase">50</comision>
 *   <GM-options>
 *     <GM-option>Yes</GM-option>
 *     <GM-option>No</GM-option>
 *   </GM-options>
 *   <GM-method>
 *     <GM-LMSR><b>100</b></GM-LMSR>
 *   </GM-method>
 * </GM-event>
 * }</pre>
 *
 * <p>The fields mirror the file, nothing more: values arrive exactly as written and are
 * judged by {@code XmlEventLoader}, which owns the rules the schema cannot state.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlEvent {

    /**
     * The schema types this attribute as {@code xs:list}, i.e. whitespace-separated
     * tokens. The adapter rejoins them, so a name reads back the way the file wrote it.
     */
    @XmlAttribute(name = "name", required = true)
    @XmlJavaTypeAdapter(NormalizedTextAdapter.class)
    private String name;

    /** A primitive, so a missing {@code <id>} arrives as 0 — the loader rejects that. */
    @XmlElement(name = "id", required = true)
    private int id;

    @XmlElement(name = "description", required = true)
    @XmlJavaTypeAdapter(NormalizedTextAdapter.class)
    private String description;

    /** Spelled as the schema spells it. */
    @XmlElement(name = "comision", required = true)
    private XmlCommission commission;

    /** The adapter applies to each {@code <GM-option>} in the list, not to the list. */
    @XmlElementWrapper(name = "GM-options", required = true)
    @XmlElement(name = "GM-option")
    @XmlJavaTypeAdapter(NormalizedTextAdapter.class)
    private List<String> optionNames = new ArrayList<>();

    @XmlElement(name = "GM-method", required = true)
    private XmlMarketMethod method;

    public String getName() {
        return name;
    }

    public int getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public XmlCommission getCommission() {
        return commission;
    }

    /** Never {@code null}: an absent or empty {@code <GM-options>} reads as no options. */
    public List<String> getOptionNames() {
        return optionNames == null ? List.of() : optionNames;
    }

    public XmlMarketMethod getMethod() {
        return method;
    }
}