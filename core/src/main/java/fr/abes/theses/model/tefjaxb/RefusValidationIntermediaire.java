//
// Ce fichier a été généré par l'implémentation de référence JavaTM Architecture for XML Binding (JAXB), v2.3.0 
// Voir <a href="https://javaee.github.io/jaxb-v2/">https://javaee.github.io/jaxb-v2/</a> 
// Toute modification apportée à ce fichier sera perdue lors de la recompilation du schéma source. 
// Généré le : 2019.12.19 à 03:54:21 PM CET 
//


package fr.abes.theses.model.tefjaxb;

import java.math.BigInteger;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Classe Java pour anonymous complex type.
 * 
 * <p>Le fragment de schéma suivant indique le contenu attendu figurant dans cette classe.
 * 
 * <pre>
 * &lt;complexType&gt;
 *   &lt;complexContent&gt;
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType"&gt;
 *       &lt;sequence&gt;
 *         &lt;element ref="{http://www.abes.fr/abes/documents/tef}refus"/&gt;
 *       &lt;/sequence&gt;
 *       &lt;attribute name="nbRefus" use="required" type="{http://www.w3.org/2001/XMLSchema}integer" /&gt;
 *     &lt;/restriction&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "refus"
})
@XmlRootElement(name = "refusValidationIntermediaire")
public class RefusValidationIntermediaire {

    @XmlElement(required = true)
    protected Refus refus;
    @XmlAttribute(name = "nbRefus", required = true)
    protected BigInteger nbRefus;

    /**
     * Obtient la valeur de la propriété refus.
     * 
     * @return
     *     possible object is
     *     {@link Refus }
     *     
     */
    public Refus getRefus() {
        return refus;
    }

    /**
     * Définit la valeur de la propriété refus.
     * 
     * @param value
     *     allowed object is
     *     {@link Refus }
     *     
     */
    public void setRefus(Refus value) {
        this.refus = value;
    }

    /**
     * Obtient la valeur de la propriété nbRefus.
     * 
     * @return
     *     possible object is
     *     {@link BigInteger }
     *     
     */
    public BigInteger getNbRefus() {
        return nbRefus;
    }

    /**
     * Définit la valeur de la propriété nbRefus.
     * 
     * @param value
     *     allowed object is
     *     {@link BigInteger }
     *     
     */
    public void setNbRefus(BigInteger value) {
        this.nbRefus = value;
    }

}
