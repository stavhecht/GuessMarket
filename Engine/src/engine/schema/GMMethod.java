
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
 *         <element ref="{}GM-LMSR"/>
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
    "gmlmsr"
})
@XmlRootElement(name = "GM-method")
public class GMMethod {

    @XmlElement(name = "GM-LMSR", required = true)
    protected GMLMSR gmlmsr;

    /**
     * Gets the value of the gmlmsr property.
     * 
     * @return
     *     possible object is
     *     {@link GMLMSR }
     *     
     */
    public GMLMSR getGMLMSR() {
        return gmlmsr;
    }

    /**
     * Sets the value of the gmlmsr property.
     * 
     * @param value
     *     allowed object is
     *     {@link GMLMSR }
     *     
     */
    public void setGMLMSR(GMLMSR value) {
        this.gmlmsr = value;
    }

}
