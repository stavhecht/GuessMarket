/**
 * The shape of an events file, written out as annotated Java classes.
 *
 * <p>One class per element of {@code GM-EX1-Schema.xsd}, each field carrying the JAXB
 * annotation that ties it to its element or attribute. Reading {@link engine.schema.XmlGuessMarket}
 * and {@link engine.schema.XmlEvent} tells you what a file looks like without opening the XSD.
 *
 * <p>These classes describe the file and nothing else: they hold no rules, do no
 * checking, and know nothing about the engine's own model. Whether a file makes sense —
 * a commission within range, exactly two options, unique ids — is
 * {@code engine.service.XmlEventLoader}'s decision, which is also the only place that
 * builds domain objects from what is read here.
 */
package engine.schema;