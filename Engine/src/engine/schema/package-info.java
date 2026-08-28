/**
 * The shape of an events file, written out as annotated Java classes.
 *
 * <p>One class per element of {@code GM-EX2-Schema.xsd}, each field carrying the JAXB
 * annotation that ties it to its element or attribute. Reading {@link engine.schema.XmlGuessMarket},
 * {@link engine.schema.XmlEvent} and {@link engine.schema.XmlUser} tells you what a file
 * looks like without opening the XSD.
 *
 * <p>Element and attribute names are copied from the schema letter for letter, typos
 * included — {@code comision}, {@code GM-mareket-maker}, the order book's {@code inital}.
 * A file is matched by the name it actually uses, so correcting the spelling here would
 * only stop it being read.
 *
 * <p>These classes describe the file and nothing else: they hold no rules, do no
 * checking, and know nothing about the engine's own model. Whether a file makes sense —
 * a commission within range, exactly two options, unique ids, a market maker for an event
 * that exists — is {@code engine.loader.XmlEventLoader}'s decision, which is also the
 * only place that builds domain objects from what is read here.
 */
package engine.schema;