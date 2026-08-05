
package engine.schema;

import javax.xml.namespace.QName;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlRegistry;


/**
 * This object contains factory methods for each 
 * Java content interface and Java element interface 
 * generated in the engine.schema package. 
 * <p>An ObjectFactory allows you to programmatically 
 * construct new instances of the Java representation 
 * for XML content. The Java representation of XML 
 * content can consist of schema derived interfaces 
 * and classes representing the binding of schema 
 * type definitions, element declarations and model 
 * groups.  Factory methods for each of these are 
 * provided in this class.
 * 
 */
@XmlRegistry
public class ObjectFactory {

    private static final QName _Id_QNAME = new QName("", "id");
    private static final QName _Description_QNAME = new QName("", "description");
    private static final QName _B_QNAME = new QName("", "b");
    private static final QName _GMOption_QNAME = new QName("", "GM-option");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: engine.schema
     * 
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link Comision }
     * 
     * @return
     *     the new instance of {@link Comision }
     */
    public Comision createComision() {
        return new Comision();
    }

    /**
     * Create an instance of {@link GuessMarket }
     * 
     * @return
     *     the new instance of {@link GuessMarket }
     */
    public GuessMarket createGuessMarket() {
        return new GuessMarket();
    }

    /**
     * Create an instance of {@link GMEvents }
     * 
     * @return
     *     the new instance of {@link GMEvents }
     */
    public GMEvents createGMEvents() {
        return new GMEvents();
    }

    /**
     * Create an instance of {@link GMEvent }
     * 
     * @return
     *     the new instance of {@link GMEvent }
     */
    public GMEvent createGMEvent() {
        return new GMEvent();
    }

    /**
     * Create an instance of {@link GMOptions }
     * 
     * @return
     *     the new instance of {@link GMOptions }
     */
    public GMOptions createGMOptions() {
        return new GMOptions();
    }

    /**
     * Create an instance of {@link GMMethod }
     * 
     * @return
     *     the new instance of {@link GMMethod }
     */
    public GMMethod createGMMethod() {
        return new GMMethod();
    }

    /**
     * Create an instance of {@link GMLMSR }
     * 
     * @return
     *     the new instance of {@link GMLMSR }
     */
    public GMLMSR createGMLMSR() {
        return new GMLMSR();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Integer }{@code >}
     * 
     * @param value
     *     Java instance representing xml element's value.
     * @return
     *     the new instance of {@link JAXBElement }{@code <}{@link Integer }{@code >}
     */
    @XmlElementDecl(namespace = "", name = "id")
    public JAXBElement<Integer> createId(Integer value) {
        return new JAXBElement<>(_Id_QNAME, Integer.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link String }{@code >}
     * 
     * @param value
     *     Java instance representing xml element's value.
     * @return
     *     the new instance of {@link JAXBElement }{@code <}{@link String }{@code >}
     */
    @XmlElementDecl(namespace = "", name = "description")
    public JAXBElement<String> createDescription(String value) {
        return new JAXBElement<>(_Description_QNAME, String.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Integer }{@code >}
     * 
     * @param value
     *     Java instance representing xml element's value.
     * @return
     *     the new instance of {@link JAXBElement }{@code <}{@link Integer }{@code >}
     */
    @XmlElementDecl(namespace = "", name = "b")
    public JAXBElement<Integer> createB(Integer value) {
        return new JAXBElement<>(_B_QNAME, Integer.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link String }{@code >}
     * 
     * @param value
     *     Java instance representing xml element's value.
     * @return
     *     the new instance of {@link JAXBElement }{@code <}{@link String }{@code >}
     */
    @XmlElementDecl(namespace = "", name = "GM-option")
    public JAXBElement<String> createGMOption(String value) {
        return new JAXBElement<>(_GMOption_QNAME, String.class, null, value);
    }

}
