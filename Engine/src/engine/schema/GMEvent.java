
package engine.schema;

import java.util.ArrayList;
import java.util.List;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
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
 *         <element ref="{}id"/>
 *         <element ref="{}description"/>
 *         <element ref="{}comision"/>
 *         <element ref="{}GM-options"/>
 *         <element ref="{}GM-method"/>
 *       </sequence>
 *       <attribute name="name" use="required">
 *         <simpleType>
 *           <list itemType="{http://www.w3.org/2001/XMLSchema}string" />
 *         </simpleType>
 *       </attribute>
 *     </restriction>
 *   </complexContent>
 * </complexType>
 * }</pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "id",
    "description",
    "comision",
    "gmOptions",
    "gmMethod"
})
@XmlRootElement(name = "GM-event")
public class GMEvent {

    protected int id;
    @XmlElement(required = true)
    protected String description;
    @XmlElement(required = true)
    protected Comision comision;
    @XmlElement(name = "GM-options", required = true)
    protected GMOptions gmOptions;
    @XmlElement(name = "GM-method", required = true)
    protected GMMethod gmMethod;
    @XmlAttribute(name = "name", required = true)
    protected List<String> name;

    /**
     * Gets the value of the id property.
     * 
     */
    public int getId() {
        return id;
    }

    /**
     * Sets the value of the id property.
     * 
     */
    public void setId(int value) {
        this.id = value;
    }

    /**
     * Gets the value of the description property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the value of the description property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setDescription(String value) {
        this.description = value;
    }

    /**
     * Gets the value of the comision property.
     * 
     * @return
     *     possible object is
     *     {@link Comision }
     *     
     */
    public Comision getComision() {
        return comision;
    }

    /**
     * Sets the value of the comision property.
     * 
     * @param value
     *     allowed object is
     *     {@link Comision }
     *     
     */
    public void setComision(Comision value) {
        this.comision = value;
    }

    /**
     * Gets the value of the gmOptions property.
     * 
     * @return
     *     possible object is
     *     {@link GMOptions }
     *     
     */
    public GMOptions getGMOptions() {
        return gmOptions;
    }

    /**
     * Sets the value of the gmOptions property.
     * 
     * @param value
     *     allowed object is
     *     {@link GMOptions }
     *     
     */
    public void setGMOptions(GMOptions value) {
        this.gmOptions = value;
    }

    /**
     * Gets the value of the gmMethod property.
     * 
     * @return
     *     possible object is
     *     {@link GMMethod }
     *     
     */
    public GMMethod getGMMethod() {
        return gmMethod;
    }

    /**
     * Sets the value of the gmMethod property.
     * 
     * @param value
     *     allowed object is
     *     {@link GMMethod }
     *     
     */
    public void setGMMethod(GMMethod value) {
        this.gmMethod = value;
    }

    /**
     * Gets the value of the name property.
     * 
     * <p>This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the name property.</p>
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * </p>
     * <pre>
     * getName().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link String }
     * </p>
     * 
     * 
     * @return
     *     The value of the name property.
     */
    public List<String> getName() {
        if (name == null) {
            name = new ArrayList<>();
        }
        return this.name;
    }

}
