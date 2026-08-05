
package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;


/**
 * <p>Java class for anonymous complex type</p>.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.</p>
 * 
 * <pre>{@code
 * <complexType>
 *   <complexContent>
 *     <restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       <sequence>
 *         <element ref="{}GM-events"/>
 *       </sequence>
 *     </restriction>
 *   </complexContent>
 * </complexType>
 * }</pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "gmEvents"
})
@XmlRootElement(name = "Guess-Market")
public class GuessMarket {

    @XmlElement(name = "GM-events", required = true)
    protected GMEvents gmEvents;

    /**
     * Gets the value of the gmEvents property.
     * 
     * @return
     *     possible object is
     *     {@link GMEvents }
     *     
     */
    public GMEvents getGMEvents() {
        return gmEvents;
    }

    /**
     * Sets the value of the gmEvents property.
     * 
     * @param value
     *     allowed object is
     *     {@link GMEvents }
     *     
     */
    public void setGMEvents(GMEvents value) {
        this.gmEvents = value;
    }

}
