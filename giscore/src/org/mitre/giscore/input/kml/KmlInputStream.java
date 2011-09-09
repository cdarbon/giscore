/****************************************************************************************
 *  KmlInputStream.java
 *
 *  Created: Jan 26, 2009
 *
 *  (C) Copyright MITRE Corporation 2009
 *
 *  The program is provided "as is" without any warranty express or implied, including
 *  the warranty of non-infringement and the implied warranties of merchantability and
 *  fitness for a particular purpose.  The Copyright owner will not be liable for any
 *  damages suffered by you as a result of using the Program.  In no event will the
 *  Copyright owner be liable for any special, indirect or consequential damages or
 *  lost profits even if the Copyright owner has been advised of the possibility of
 *  their occurrence.
 *
 ***************************************************************************************/
package org.mitre.giscore.input.kml;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.lang.StringUtils;
import org.mitre.giscore.DocumentType;
import org.mitre.giscore.events.*;
import org.mitre.giscore.geometry.*;
import org.mitre.giscore.geometry.Point;
import org.mitre.giscore.geometry.Polygon;
import org.mitre.giscore.input.XmlInputStream;
import org.mitre.giscore.utils.NumberStreamTokenizer;
import org.mitre.itf.geodesy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.*;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Read a Google Earth Keyhole Markup Language (KML) file in as an input stream
 * one event at a time.
 *
 * <P>Supports KML 2.0 through KML 2.2 data formats with allowance
 * for sloppy or lax KML files as would be allowed in Google Earth. Limited
 * support is provided for Google's KML Extensions with gx prefix and
 * <tt>http://www.google.com/kml/ext/2.2</tt> namespace .
 * Strict conformance to the KML 2.2 specification
 * is maintained in output in the associated {@link org.mitre.giscore.output.kml.KmlOutputStream}
 * class. In doing so some "legacy" KML 2.0 or KML 2.1 conventions may be
 * normalized into the equivalent 2.2 form or dropped if not supported.
 *
 * <P>Each time the read method is called,
 * the code tries to read a single event's worth of data. Generally that is one
 * of the following events:
 * <ul>
 * <li>A new container returning a <tt>ContainerStart</tt> object
 * <li>Exiting a container returning a <tt>ContainerEnd</tt> object
 * <li>A new feature returning a <tt>Feature</tt> object
 * </ul>
 * <p>
 * Supports KML Placemark, GroundOverlay, NetworkLink, Document, and Folder elements with
 * limited/partial support for the lesser used NetworkLinkControl(<A href="#NetworkLinkControl">*</A>),
 * ScreenOverlay, PhotoOverlay(<A href="#PhotoOverlay">*</A>) elements.
 * <p>
 * Geometry support includes: Point, LineString, LinearRing, Polygon, MultiGeometry, and Model(<A href="#Model">*</A>).
 * <p>
 * Supported KML properties include: name, description, open, visibility,
 * Camera/LookAt, atom:author, atom:link, xal:AddressDetails, styleUrl,
 * inline/shared Styles, Region, Snippet, snippet, ExtendedData(<A href="#ExtendedData">*</A>),
 * Schema, TimeStamp/TimeSpan elements in addition to the geometry are parsed
 * and set on the Feature object.
 * <P>
 * Style and StyleMap supported on Features (Placemarks, Documents, Folders, etc.)
 * with IconStyle, PolyStyle, ListStyle, etc.
 * {@code StyleMaps} with inline Styles are supported.
 * StyleMaps must specify {@code styleUrl} and/or inline Style. Nested StyleMaps are not supported.
 * <p>
 * If elements (e.g. {@code Style} or {@code StyleMap}) are out of order on input
 * that same order is preserved in the order of elements returned in {@link #read()}.
 * For example, if Style/StyleMap elements appear incorrectly out of order such as
 * after the firstFeature element then it will likewise be out of order. This will
 * not be corrected by KmlWriter or KmlOutputStream, which assumes the caller writes
 * those elements in a valid order. It should still work with Google Earth but it
 * will not conform to the KML 2.2 spec.
 * <p>
 * <h4>Notes/Limitations:</h4>
 * <p>
 * The actual handling of containers and other features has some uniform
 * methods. Every feature in KML can have a set of common attributes and
 * additional elements.
 * Geometry is handled by common code as well. All coordinates in KML are
 * transmitted as tuples of two or three elements. The formatting of these is
 * consistent and is handled by {@link #parseCoordinates(QName)}. {@code Tessellate},
 * {@code extrude}, and {@code altitudeMode} properties are maintained on the
 * associated Geometry.
 * <p>
 * <a name="ExtendedData">
 * Handles ExtendedData with Data/Value or SchemaData/SimpleData elements but does not handle the non-KML namespace
 * form of extended data (see http://code.google.com/apis/kml/documentation/extendeddata.html#opaquedata).
 * Only a single {@code Data/SchemaData/Schema ExtendedData} mapping is assumed
 * per Feature but note that although uncommon, KML allows features to define multiple
 * Schemas. Features with mixed {@code Data} and/or multiple {@code SchemaData} elements
 * will be associated only with the last {@code Schema} referenced.
 * </a>
 * <p>
 * Unsupported tags include the following:
 *  {@code address, Metadata, phoneNumber}.
 * These tags are consumed but discarded.
 * <p>
 * Some support for gx KML extensions (e.g. Track, MultiTrack, Tour, etc.). Also {@code gx:altitudeMode}
 * is handle specially and stored as a value of the {@code altitudeMode} in LookAt, Camera, Geometry,
 * and GroundOverlay.
 * <p>
 * <a name="PhotoOverlay">
 * Limited support for {@code PhotoOverlay} which creates an basic overlay object
 * with Point and rotation without retaining other PhotoOverlay-specific properties
 * (ViewVolume, ImagePyramid, or shape).</a>
 * <p>
 * <a name="Model">
 * Limited support for {@code Model} geometry type. Keeps only location and altitude
 * properties.</a>
 * <p>
 * <a name="NetworkLinkControl">
 * Limited support for {@code NetworkLinkControl} which creates an object wrapper for the link
 * with the top-level info but the update details (i.e. Create, Delete, and Change) are discarded.</a>
 * <p>
 * Allows timestamps to omit seconds field as does Google Earth. Strict XML schema validation requires
 * seconds field in the dateTime ({@code YYYY-MM-DDThh:mm:ssZ}) format but Google Earth is lax in its rules.
 * Likewise allow the 'Z' suffix to be omitted in which case it defaults to UTC.
 *
 * @author J.Mathews
 */
public class KmlInputStream extends XmlInputStream implements IKml {
    public static final Logger log = LoggerFactory.getLogger(KmlInputStream.class);

    private static final Set<String> ms_kml_ns = new HashSet<String>(7);

    static {
    	ms_kml_ns.add("http://earth.google.com/kml/2.1");
    	ms_kml_ns.add("http://earth.google.com/kml/2.2");
    	ms_kml_ns.add("http://earth.google.com/kml/2.3");
    	ms_kml_ns.add("http://earth.google.com/kml/3.0");

    	ms_kml_ns.add("http://www.opengis.net/kml/2.2");
    	ms_kml_ns.add("http://www.opengis.net/kml/2.3");
    	ms_kml_ns.add("http://www.opengis.net/kml/3.0");
    }
	private static final Set<String> ms_features = new HashSet<String>();
	private static final Set<String> ms_containers = new HashSet<String>();
	private static final Set<String> ms_attributes = new HashSet<String>();
	private static final Set<String> ms_geometries = new HashSet<String>();

	private static final List<SimpleDateFormat> ms_dateFormats = new ArrayList<SimpleDateFormat>(6);
	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
	private static DatatypeFactory fact;
	private static final Longitude COORD_ERROR = new Longitude();
	private static final QName ID_ATTR = new QName(ID);

	private Map<String, String> schemaAliases;
	private final Map<String, Schema> schemata = new HashMap<String, Schema>();

    static {
		// all non-container elements that extend kml:AbstractFeatureType base type in KML Schema
		ms_features.add(PLACEMARK);
		ms_features.add(NETWORK_LINK);
		ms_features.add(GROUND_OVERLAY);
		ms_features.add(PHOTO_OVERLAY);
		ms_features.add(SCREEN_OVERLAY);

		// all elements that extend kml:AbstractContainerType in KML Schema
		ms_containers.add(FOLDER);
		ms_containers.add(DOCUMENT);

		// basic tags in Feature that are skipped but consumed
		ms_attributes.add(OPEN); // note special handling for Folders, Documents or NetworkLinks
		ms_attributes.add(ADDRESS);
		ms_attributes.add(PHONE_NUMBER);
		ms_attributes.add(METADATA);

		// all possible elements that extend kml:AbstractGeometryType base type in KML Schema
		ms_geometries.add(POINT);
		ms_geometries.add(LINE_STRING);
		ms_geometries.add(LINEAR_RING);
		ms_geometries.add(POLYGON);
		ms_geometries.add(MULTI_GEOMETRY);
		ms_geometries.add(MODEL);

        // Reference states: dateTime (YYYY-MM-DDThh:mm:ssZ) in KML states that T is the separator
        // between the calendar and the hourly notation of time, and Z indicates UTC. (Seconds are required.)
        // however, we will also check time w/o seconds since it is accepted by Google Earth.
        // Thus allowing the form: YYYY-MM-DDThh:mm[:ss][Z]
        // http://code.google.com/apis/kml/documentation/kmlreference.html#timestamp

		ms_dateFormats.add(new SimpleDateFormat(ISO_DATE_FMT)); // default: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
		ms_dateFormats.add(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"));
		ms_dateFormats.add(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")); // dateTime format w/o seconds
		ms_dateFormats.add(new SimpleDateFormat("yyyy-MM-dd")); // date (YYYY-MM-DD)
		ms_dateFormats.add(new SimpleDateFormat("yyyy-MM"));    // gYearMonth (YYYY-MM)
		ms_dateFormats.add(new SimpleDateFormat("yyyy"));       // gYear (YYYY)
		for (DateFormat fmt : ms_dateFormats) {
			fmt.setTimeZone(UTC);
		}
	}

	/**
	 * Creates a <code>KmlInputStream</code>
	 * and saves its argument, the input stream
	 * <code>input</code>, for later use.
	 *
	 * @param input
	 *            input stream for the kml file, never <code>null</code>
	 * @throws IOException if an I/O or parsing error occurs
	 * @throws IllegalArgumentException if input is null
	 */
	public KmlInputStream(InputStream input) throws IOException {
		super(input);
		DocumentStart ds = new DocumentStart(DocumentType.KML);
		addLast(ds);
		try {
			XMLEvent ev = stream.peek();
            // Find first StartElement in stream
			while (ev != null && ! ev.isStartElement()) {
				ev = stream.nextEvent(); // Actually advance
                if (ev != null) {
                     if (ev.isStartDocument()) {
                        // first element will be the XML header as StartDocument whether explicit or not
                        StartDocument doc = (StartDocument)ev;
                        if (doc.encodingSet())
                            setEncoding(doc.getCharacterEncodingScheme()); // default UTF-8
                    }
                    ev = stream.peek();
                }
			}
            if (ev == null) return;
			// The first start element may be a KML element, which isn't
			// handled by the rest of the code. We'll handle it here to obtain the
			// namespaces
			StartElement first = ev.asStartElement();
			QName qname = first.getName();
			String nstr = qname.getNamespaceURI();
            final String localPart = qname.getLocalPart();
            if ("kml".equals(localPart)) {
                if (StringUtils.isNotBlank(nstr) && !ms_kml_ns.contains(nstr)) {
                    // KML namespace not registered
                    log.info("Registering unrecognized KML namespace: " + nstr);
                    ms_kml_ns.add(nstr);
                }
				stream.nextEvent(); // Consume event
			} else if (StringUtils.isNotBlank(nstr) && !ms_kml_ns.contains(nstr)
                    && (ms_features.contains(localPart) || ms_containers.contains(localPart))) {
                // root element non-kml (e.g. GroundOverlay) and namespace is not registered.
                // Add it otherwise will be parsed as foreign elements
                log.info("Registering unrecognized KML namespace: " + nstr);
                ms_kml_ns.add(nstr);
            }
			@SuppressWarnings("unchecked")
			Iterator<Namespace> niter = first.getNamespaces();
			while(niter.hasNext()) {
				Namespace ns = niter.next();
                String prefix = ns.getPrefix();
                if (StringUtils.isBlank(prefix)) continue;
                // assuming that namespace prefixes are unique in the source KML document since it would violate
                // the XML unique attribute constraint and not even load in Google Earth.
                try {
                    org.mitre.giscore.Namespace gnamespace =
                        org.mitre.giscore.Namespace.getNamespace(prefix, ns.getNamespaceURI());
                    ds.getNamespaces().add(gnamespace);
                } catch (IllegalArgumentException e) {
                    // ignore invalid namespaces since often namespaces may not even be used in the document itself
                    log.warn("ignore invalid namespace " + prefix + "=" + ns.getNamespaceURI());
                }
			}
		} catch (XMLStreamException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Reads the next <code>IGISObject</code> from the InputStream.
	 *
	 * @return next <code>IGISObject</code>,
     *           or <code>null</code> if the end of the stream is reached.
	 * @throws IOException if an I/O error occurs or if there
	 * 			is a fatal error with the underlying XML
	 */
    @CheckForNull
	public IGISObject read() throws IOException {
		if (hasSaved()) {
			return readSaved();
		} else {
			try {
				while (true) {
					XMLEvent e = stream.nextEvent();
					if (e == null) {
						return null;
					}
					int type = e.getEventType();
                    if (XMLStreamReader.START_ELEMENT == type) {
						IGISObject se = handleStartElement(e);
						if (se == NullObject.getInstance())
							continue;
						return se; // start element is GISObject or null (indicating EOF)
                    } else if (XMLStreamReader.END_ELEMENT == type) {
						IGISObject rval = handleEndElement(e);
						if (rval != null)
							return rval;
                    }
                    /*
                    // saving comments messes up the junit tests so comment out for now
                    } else if (XMLStreamReader.COMMENT == type) {
                        IGISObject comment = handleComment(e);
						if (comment != null)
							return comment;
					*/
				}
			} catch (ArrayIndexOutOfBoundsException e) {
				// if have wrong encoding can end up here
				//log.warn("Unexpected parse error", e);
				throw new IOException(e);
			} catch (NoSuchElementException e) {
				return null;
			} catch (XMLStreamException e) {
				throw new IOException(e);
			}
		}
	}

    /*
    private IGISObject handleComment(XMLEvent e) throws XMLStreamException {
        if (e instanceof javax.xml.stream.events.Comment) {
            String text = ((javax.xml.stream.events.Comment)e).getText();
            if (StringUtils.isNotBlank(text))
                return new Comment(text);
        }
        return null;
    }
    */

    /**
	 * Read elements until we find a feature or a schema element. Use the name
	 * and description data to set the equivalent data on the container start.
	 *
	 * @param e
	 * @return
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private IGISObject handleContainer(XMLEvent e) throws XMLStreamException {
		StartElement se = e.asStartElement();
		QName name = se.getName();
		String containerTag = name.getLocalPart();
		ContainerStart cs = new ContainerStart(containerTag); // Folder or Document
		addLast(cs);
		Attribute id = se.getAttributeByName(ID_ATTR);
		if (id != null) cs.setId(id.getValue());

		while (true) {
			XMLEvent ne = stream.peek();

			// Found end tag, sometimes a container has no other content
			if (foundEndTag(ne, name)) {
				break;
			}
			if (ne.getEventType() == XMLStreamReader.START_ELEMENT) {
				StartElement nextel = ne.asStartElement();
				String tag = nextel.getName().getLocalPart();
				// check if element has been aliased in Schema
				// only used for old-style KML 2.0 Schema defs with "parent" attribute/element.
				if (schemaAliases != null) {
					String newName = schemaAliases.get(tag);
					if (newName != null) {
						// log.info("Alias " + tag +" -> " + newName);
						tag = newName;
					}
				}
				if (ms_containers.contains(tag) || ms_features.contains(tag)
						|| SCHEMA.equals(tag)) {
					break;
				}
			}

			XMLEvent ee = stream.nextEvent();
			if (ee == null) {
				break;
			}
			if (ee.getEventType() == XMLStreamReader.START_ELEMENT) {
				StartElement sl = ee.asStartElement();
				QName qname = sl.getName();
				if (OPEN.equals(qname.getLocalPart())) {
					if (isTrue(getElementText(qname)))
						cs.setOpen(true);
				} else if (!handleProperties(cs, ee, qname)) {
					// Ignore other container elements
					log.debug("ignore " + qname);
				}
            }
		}

		return readSaved();
	}

	/**
	 * Handle the elements found in all features
	 *
	 * @param feature
	 * @param ee
	 * @param name  the qualified name of this event
	 * @return <code>true</code> if the event has been handled
	 */
	private boolean handleProperties(Common feature, XMLEvent ee,
			QName name) {
		String localname = name.getLocalPart(); // never null
        try {
            if (localname.equals(NAME)) {
				// sometimes markup found in names (e.g. <name><B>place name</B></name>)
				// where is should be in the description and/or BalloonStyle
                feature.setName(getElementText(name));
                return true;
            } else if (localname.equals(DESCRIPTION)) {
                // description content with markup not enclosed in CDATA is invalid and cannot be parsed
                feature.setDescription(getElementText(name));
				return true;
            } else if (localname.equals(VISIBILITY)) {
				if (isTrue(stream.getElementText()))
                	feature.setVisibility(Boolean.TRUE);
                return true;
            } else if (localname.equals(STYLE)) {
                handleStyle(feature, ee, name);
                return true;
            } else if (ms_attributes.contains(localname)) {
				// basic tags in Feature that are skipped but consumed
				// e.g. open, address, phoneNumber, Metadata
                // Skip, but consumed
				skipNextElement(stream, name);
                return true;
			} else if (localname.equals(STYLE_URL)) {
                feature.setStyleUrl(stream.getElementText()); // value trimmed to null
                return true;
            } else if (localname.equals(TIME_SPAN) || localname.equals(TIME_STAMP)) {
                handleTimePrimitive(feature, ee);
                return true;
			} else if (localname.equals(REGION)) {
                handleRegion(feature, name);
                return true;
            } else if (localname.equals(STYLE_MAP)) {
                handleStyleMap(feature, ee, name);
                return true;
			} else if (localname.equals(LOOK_AT) || localname.equals(CAMERA)) {
                handleAbstractView(feature, name);
                return true;
			} else if (localname.equals(EXTENDED_DATA)) {
                handleExtendedData(feature, name);
                return true;
			} else if (localname.equals("Snippet")) { // kml:Snippet (deprecated)
				feature.setSnippet(getElementText(name));
                return true;
			} else if (localname.equals("snippet")) { // kml:snippet
				// http://code.google.com/apis/kml/documentation/kmlreference.html#snippet
				feature.setSnippet(getElementText(name));
                return true;
            } else {
				//StartElement sl = ee.asStartElement();
				//QName name = sl.getName();
				// handle atom:link and atom:author elements (e.g. http://www.w3.org/2005/Atom)
                // and google earth extensions as ForeignElements
				// skip other non-KML namespace elements.
				String ns = name.getNamespaceURI();
                if (StringUtils.isNotEmpty(ns) && !ms_kml_ns.contains(ns)) {
                    if (localname.equals(ADDRESS_DETAILS) || ns.startsWith("http://www.w3.org/")
                            || ns.startsWith(NS_GOOGLE_KML_EXT_PREFIX)) {
                        try {
                            Element el = (Element) getForeignElement(ee.asStartElement());
                            feature.getElements().add(el);
                        } catch (Exception e) {
                            log.error("Problem getting element", e);
                        }
                    } else {
                        // TODO: should we add all-non KML elements as-is or only expected ones ??
                        log.debug("Skip unknown namespace " + name);
                        skipNextElement(stream, name);
                    }
                    return true;
                }
            }
		} catch (XMLStreamException e) {
            log.error("Failed to handle: " + localname, e);
            // TODO: do we have any situation where need to skip over failed localname element??
            // skipNextElement(stream, localname);
        }
        return false;
	}

    /**
	 * @param cs
	 * @param name  the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleExtendedData(Common cs, QName name)
			throws XMLStreamException {
		XMLEvent next;
		String rootNS = name.getNamespaceURI();
		while (true) {
			next = stream.nextEvent();
			if (foundEndTag(next, name)) {
				return;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				QName qname = se.getName();
				String tag = qname.getLocalPart();
				/*
				 * xmlns:prefix handling. skips namespaces other than parent namespace (e.g. http://www.opengis.net/kml/2.2)
				 */
				if (rootNS != null && !rootNS.equals(qname.getNamespaceURI())) {
					// ignore extended data elements other namespace other than root namespace
					// external namespace contents in ExtendedData not supported
					// http://code.google.com/apis/kml/documentation/extendeddata.html##opaquedata
					/*
						<ExtendedData xmlns:camp="http://campsites.com">
						  <camp:number>14</camp:number>
						  <camp:parkingSpaces>2</camp:parkingSpaces>
						  <camp:tentSites>4</camp:tentSites>
						</ExtendedData>
					 */
					log.debug("skip " + qname);
                	skipNextElement(stream, qname);
				} else if (tag.equals(DATA)) {
					Attribute nameAttr = se.getAttributeByName(new QName(NAME));
					if (nameAttr != null) {
						String value = parseValue(qname);
                        if (value != null)
    						cs.putData(new SimpleField(nameAttr.getValue()), value);
                        // NOTE: if feature has mixed Data and SchemaData then Data fields will be associated with last SchemaData schema processed
					} else {
						// no name skip any value element
						// TODO: if Data has id attr but no name can we use still the value ??
						log.debug("No name attribute for Data. Skip element");
						skipNextElement(stream, qname);
					}
				} else if (tag.equals(SCHEMA_DATA)) {
					Attribute url = se.getAttributeByName(new QName(SCHEMA_URL));
					if (url != null) {
                        // NOTE: reference and schema id must be handled exactly the same. See handleSchema()
                        String uri = UrlRef.escapeUri(url.getValue());
						handleSchemaData(uri, cs, qname);
						try {
							cs.setSchema(new URI(uri));
						} catch (URISyntaxException e) {
                            // is URI properly encoded??
							log.error("Failed to handle SchemaData schemaUrl=" + uri , e);
						}
					} else {
						// no schemaUrl skip SchemaData element
						// TODO: if SchemaData has SimpleData but no schemaUrl attr can we use still the value ??
						log.debug("No schemaUrl attribute for Data. Skip element");
						skipNextElement(stream, qname);
					}
                } else {
                    log.debug("ignore " + qname);
                	skipNextElement(stream, qname);
                }
			}
		}
	}

	/**
	 * @param uri a reference to a schema, if local then use that schema's
	 * simple field objects instead of creating ones on the fly
	 * @param cs Feature/Container for ExtendedData tag
	 * @param qname the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleSchemaData(String uri, Common cs, QName qname)
			throws XMLStreamException {
		XMLEvent next;
		if (uri.startsWith("#")) uri = uri.substring(1);
		Schema schema = schemata.get(uri);

		while (true) {
			next = stream.nextEvent();
			if (foundEndTag(next, qname)) {
				return;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				if (foundStartTag(se, SIMPLE_DATA)) {
					Attribute name = se.getAttributeByName(new QName(NAME));
					if (name != null) {
						String value = stream.getElementText();
						SimpleField field = null;
						if (schema != null) {
							field = schema.get(name.getValue());
						}
						if (field == null) {
							// Either we don't know the schema or it isn't local
							field = new SimpleField(name.getValue());
						}
                        // NOTE: if feature has multiple SchemaData elements (multi-schemas) then fields will be associated with last SchemaData schema processed
						cs.putData(field, value);
					}
				}
			}
		}
	}

	/**
	 * @param name  the qualified name of this event
	 * @return the value associated with the element
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private String parseValue(QName name) throws XMLStreamException {
		XMLEvent next;
		String rval = null;
		while (true) {
			next = stream.nextEvent();
			if (foundEndTag(next, name)) {
				return rval;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				if (foundStartTag(se, VALUE)) {
					rval = stream.getElementText();
				}
			}
			// otherwise next=END_ELEMENT(2) | CHARACTERS(4)
		}
	}

	/**
	 * Handle AbstractView (Camera or LookAt) element
	 * @param feature
	 * @param name the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleAbstractView(Common feature, QName name)
			throws XMLStreamException {
		TaggedMap viewGroup = handleTaggedData(name); // Camera or LookAt
		if (viewGroup != null) feature.setViewGroup(viewGroup);
	}

	/**
	 * Handle KML Region
	 * @param feature
	 * @param name the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleRegion(Common feature, QName name)
			throws XMLStreamException {
		TaggedMap region = new TaggedMap(LAT_LON_ALT_BOX);
		while (true) {
			XMLEvent next = stream.nextEvent();
			if (foundEndTag(next, name)) {
				// must have either Lod or LatLonAltBox name-value pairs
				if (!region.isEmpty()) {
					feature.setRegion(region);
				}
				return;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				if (foundStartTag(se, LAT_LON_ALT_BOX)) {
					handleTaggedData(se.getName(), region); // LatLonAltBox
				} else if (foundStartTag(se, LOD)) {
					handleTaggedData(se.getName(), region); // Lod
				}
			}
		}
	}

	/**
	 * @param cs
	 * @param ee
	 * @param name the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private StyleMap handleStyleMap(Common cs, XMLEvent ee, QName name)
			throws XMLStreamException {
		XMLEvent next;
		StyleMap sm = new StyleMap();
		if (cs != null) {
			if (cs instanceof Feature) {
				// inline StyleMap for Placemark, NetworkLink, GroundOverlay, etc.
				((Feature)cs).setStyle(sm);
			} else if (cs instanceof ContainerStart) {
				// add style to container
				((ContainerStart)cs).addStyle(sm);
			} else addLast(sm);
		}
		// otherwise out of order StyleMap

		StartElement sl = ee.asStartElement();
		Attribute id = sl.getAttributeByName(ID_ATTR);
		if (id != null) {
			sm.setId(id.getValue());
		}

		while (true) {
			next = stream.nextEvent();
			if (foundEndTag(next, name)) {
				return sm;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement ie = next.asStartElement();
				if (foundStartTag(ie, PAIR)) {
					handleStyleMapPair(sm, ie.getName());
				}
			}
		}
	}

	/**
	 * @param sm
	 * @param name the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleStyleMapPair(StyleMap sm, QName name) throws XMLStreamException {
		String key = null, value = null;
		Style style = null;
		while (true) {
			XMLEvent ce = stream.nextEvent();
			if (ce == null) {
				return;
			}
			if (ce.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = ce.asStartElement();
				if (foundStartTag(se, KEY)) {
                    // key: type="kml:styleStateEnumType" default="normal"/>
                    // styleStateEnumType: [normal] or highlight
					key = getNonEmptyElementText();
				} else if (foundStartTag(se, STYLE_URL)) {
                    value = getNonEmptyElementText(); // type=anyURI
				} else if (foundStartTag(se, STYLE)) {
                    style = handleStyle(null, se, se.getName());
					// inline Styles within StyleMap
				} else if (foundStartTag(se, STYLE_MAP)) {
					 // nested StyleMaps are not supported nor does it even make sense
					log.debug("skip nested StyleMap");
					skipNextElement(stream, se.getName());
				}
			}
			XMLEvent ne = stream.peek();
			if (foundEndTag(ne, name)) {
				if (key != null || value != null || style != null) {
                    if (key == null) {
                        key = StyleMap.NORMAL; // default
                    } else if (key.equalsIgnoreCase(StyleMap.NORMAL))
                        key = StyleMap.NORMAL;
                    else if (key.equalsIgnoreCase(StyleMap.HIGHLIGHT))
                        key = StyleMap.HIGHLIGHT;
                    else
                        log.warn("Unknown StyleMap key: " + key);

					if (sm.containsKey(key)) {
						if (value != null) {
							log.warn("StyleMap already has " + key + " definition. Ignore styleUrl=" + value);
						} else {
							log.warn("StyleMap already has " + key + " definition. Ignore inline Style");
						}
						// Google Earth keeps the first pair for a given key
					} else {
						// note if styleUrl is "local reference" and does not have '#' prefix
						// then it will be pre-pended to the URL.
						sm.add(new Pair(key, value, style));
					}
				}
				return;
			}
		}
	}

	/**
     * Handle timePrimitives (TimeStamp or timeSpan elements)
	 * @param cs feature to set with time
	 * @param ee
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleTimePrimitive(Common cs, XMLEvent ee)
			throws XMLStreamException {
		XMLEvent next;
		StartElement sl = ee.asStartElement();
		QName tag = sl.getName();
		while (true) {
			next = stream.nextEvent();
			if (next == null)
				return;
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				String time = null;
                try {
                    if (foundStartTag(se, WHEN)) {
                        time = getNonEmptyElementText();
                        if (time != null) {
                            Date date = parseDate(time);
                            cs.setStartTime(date);
                            cs.setEndTime(date);
                        }
                    } else if (foundStartTag(se, BEGIN)) {
                        time = getNonEmptyElementText();
                        cs.setStartTime(parseDate(time));
                    } else if (foundStartTag(se, END)) {
                        time = getNonEmptyElementText();
                        cs.setEndTime(parseDate(time));
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring bad time: " + time + ": " + e);
                } catch (ParseException e) {
                    log.warn("Ignoring bad time: " + time + ": " + e);
                }
            }
            if (foundEndTag(next, tag)) {
                return;
            }
        }
	}

	/**
	 * Parse kml:dateTimeType XML date/time field and convert to Date object.
	 *
	 * @param datestr  Lexical representation for one of XML Schema date/time datatypes.
	 *                  Must be non-null and non-blank string.
	 * @return <code>Date</code> created from the <code>lexicalRepresentation</code>, never null.
	 * @throws ParseException If the <code>lexicalRepresentation</code> is not a valid <code>Date</code>.
	 */
    @NonNull
	public static Date parseDate(String datestr) throws ParseException {
        if (StringUtils.isBlank(datestr)) throw new ParseException("Empty or null date string", 0);
		try {
			if (fact == null) fact = DatatypeFactory.newInstance();
			XMLGregorianCalendar o = fact.newXMLGregorianCalendar(datestr);
			GregorianCalendar cal = o.toGregorianCalendar();
			String type = o.getXMLSchemaType().getLocalPart();
			boolean useUTC = true;
			if ("dateTime".equals(type)) {
				// dateTime (YYYY-MM-DDThh:mm:ssZ)
				// dateTime (YYYY-MM-DDThh:mm:sszzzzzz)
				// Second form gives the local time and then the +/- conversion to UTC.
				// Set timezone to UTC if other than dateTime formats with explicit timezones
				int ind = datestr.lastIndexOf('T') + 1; // index should never be -1 if type is dateTime
				if (ind > 0 && (datestr.indexOf('+', ind) > 0 || datestr.indexOf('-', ind) > 0)) {
                    // e.g. 2009-03-14T18:10:46+03:00 or 2009-03-14T18:10:46-05:00
					useUTC = false;
                }
				// if timeZone is missing (e.g. 2009-03-14T21:10:50) then 'Z' is assumed and UTC is used
			}
			if (useUTC) cal.setTimeZone(UTC);
			//else datestr += "*";
			//System.out.format("%-10s\t%s%n", type, datestr);
			/*
			  possible dateTime types: { dateTime, date, gYearMonth, gYear }
			  if other than dateTime then must adjust the time to 0

			  1997                      gYear        (YYYY)						1997-01-01T00:00:00.000Z
			  1997-07                   gYearMonth   (YYYY-MM)					1997-07-01T00:00:00.000Z
			  1997-07-16                date         (YYYY-MM-DD)				1997-07-16T00:00:00.000Z
			  1997-07-16T07:30:15Z      dateTime (YYYY-MM-DDThh:mm:ssZ)			1997-07-16T07:30:15.000Z
			  1997-07-16T07:30:15.30Z   dateTime     							1997-07-16T07:30:15.300Z
			  1997-07-16T10:30:15+03:00 dateTime (YYYY-MM-DDThh:mm:sszzzzzz)	1997-07-16T07:30:15.000Z
			 */
			if (!"dateTime".equals(type)) {
				cal.set(Calendar.HOUR_OF_DAY, 0);
			}
			return cal.getTime();
		} catch (IllegalArgumentException iae) {
            // try individual date formats
            int ind = datestr.indexOf('T');
            int i;
			/*
				date formats:
				0: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
				1: yyyy-MM-dd'T'HH:mm:ss'Z'
				2: yyyy-MM-dd'T'HH:mm'Z' (dateTime format w/o seconds)
				3: yyyy-MM-dd
				4: yyyy-MM  (gYearMonth)
				5: yyyy		(gYear)
			*/
            if (ind == -1) {
                i = 3; // if no 'T' in date then skip to date (YYYY-MM-DD) format @ index=3
            } else {
                i = 0;
                // Sloppy KML might drop the 'Z' suffix or for dates. Google Earth defaults to UTC.
                // Likewise KML might drop the seconds field in timestamp.
                // Note these forms are not valid with respect to KML Schema and kml:dateTimeType
                // definition but Google Earth has lax parsing for such cases so we attempt
                // to parse as such.
                // This will NOT handle alternate time zones format with missing second field: e.g. 2009-03-14T16:10-05:00
                if (!datestr.endsWith("Z") && datestr.indexOf(':', ind + 1) > 0
                        && datestr.indexOf('-', ind + 1) == -1
                        && datestr.indexOf('+', ind + 1) == -1) {
                    log.debug("Append Z suffix to date");
                    datestr += 'Z'; // append 'Z' to date
                }
            }
            while (i < ms_dateFormats.size()) {
                SimpleDateFormat fmt = ms_dateFormats.get(i++);
				try {
                    Date date = fmt.parse(datestr);
                    if (log.isDebugEnabled())
                        log.debug(String.format("Failed to parse date %s with DatatypeFactory. Parsed using dateFormat: %s",
                                datestr, fmt.toPattern()));
					return date;
				} catch (ParseException pe) {
                    // ignore
				}
			}
            // give up
			final ParseException e2 = new ParseException(iae.getMessage(), 0);
			e2.initCause(iae);
			throw e2;
		} catch (DatatypeConfigurationException ce) {
            // NOTE: maybe JODA time would be be better generic time parser but would be a new dependency
			// if unable to create factory then try brute force
			log.error("Failed to get DatatypeFactory", ce);
			// note: this does not correctly handle dateTime (YYYY-MM-DDThh:mm:sszzzzzz) format
			ParseException e = null;
			for (DateFormat fmt : ms_dateFormats) {
				try {
					return fmt.parse(datestr);
				} catch (ParseException pe) {
					e = pe;
				}
			}
			throw e;
		}
	}

	/**
	 * Get the style data and push the style onto the buffer so it is returned
	 * first, before its container or placemark
	 *
	 * @param cs
	 * @param ee
	 * @param name the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 * @return
	 */
	private Style handleStyle(Common cs, XMLEvent ee, QName name)
			throws XMLStreamException {
		XMLEvent next;

		Style style = new Style();
		StartElement sse = ee.asStartElement();
		Attribute id = sse.getAttributeByName(ID_ATTR);
		if (id != null) {
            // escape invalid characters in id field?
            // if so must be consistent in feature.setStyleUrl() and handleStyleMapPair(), etc.
			style.setId(id.getValue());
		}
		if (cs != null) {
			if (cs instanceof Feature) {
				// inline Style for Placemark, NetworkLink, GroundOverlay, etc.
				((Feature)cs).setStyle(style);
			} else if (cs instanceof ContainerStart) {
				// add style to container
				((ContainerStart)cs).addStyle(style);
			} else addLast(style);
		}
		// otherwise out of order style or inline style in StyleMap

		while (true) {
			next = stream.nextEvent();
			if (foundEndTag(next, name)) {
				return style;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				QName qname = se.getName();
				String localPart = qname.getLocalPart();
				if (localPart.equals(ICON_STYLE)) {
					handleIconStyle(style, qname);
				} else if (localPart.equals(LINE_STYLE)) {
					handleLineStyle(style, qname);
				} else if (localPart.equals(BALLOON_STYLE)) {
					handleBalloonStyle(style, qname);
				} else if (localPart.equals(LABEL_STYLE)) {
					handleLabelStyle(style, qname);
				} else if (localPart.equals(POLY_STYLE)) {
					handlePolyStyle(style, qname);
                } else if (localPart.equals(LIST_STYLE)) {
					handleListStyle(style, qname);
				}
			}
		}
	}

    private void handleListStyle(Style style, QName qname) throws XMLStreamException {
        Color bgColor = null; // default color="ffffffff" (white)
        Style.ListItemType listItemType = null;
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, qname)) {
				style.setListStyle(bgColor, listItemType);
				return;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				String localPart = se.getName().getLocalPart();
				if (localPart.equals(LIST_ITEM_TYPE)) {
                    String text = stream.getElementText();
                    try {
                        listItemType = Style.ListItemType.valueOf(text);
                    } catch (IllegalArgumentException e2) {
                        log.warn("Invalid ListItemType value: " + text);
                    }
				} else if (localPart.equals(BG_COLOR)) {
					bgColor = parseColor(stream.getElementText());
				}
			}
		}
    }

    /**
	 * @param style
	 * @param qname the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handlePolyStyle(Style style, QName qname) throws XMLStreamException {
		Color color = null; // default color="ffffffff" (white)
		Boolean fill = null; // default = true
		Boolean outline = null;	// default = true
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, qname)) {
				style.setPolyStyle(color, fill, outline);
				return;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				String localPart = se.getName().getLocalPart();
				if (localPart.equals(FILL)) {
					fill = isTrue(stream.getElementText());
				} else if (localPart.equals(OUTLINE)) {
					outline = isTrue(stream.getElementText());
				} else if (localPart.equals(COLOR)) {
					color = parseColor(stream.getElementText());
				}
			}
		}
	}

	/**
	 * Determine if an element value is true or false
	 *
	 * @param val
	 *            the value, may be <code>null</code>
	 * @return <code>true</code> if the value is the single character "1" or "true".
	 */
	private boolean isTrue(String val) {
		// xsd:boolean� can have the following legal literals {true, false, 1, 0}.
		if (val != null) {
			val = val.trim();
			//if ("1".equals(val)) return true;
			//else if ("0".equals(val)) return false;
			return "1".equals(val) || val.equalsIgnoreCase("true");
		}
		return false;
	}

	/**
	 * @param style
	 * @param qname the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleLabelStyle(Style style, QName qname) throws XMLStreamException {
		double scale = 1;
		Color color = null; // Color.black;
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, qname)) {
				style.setLabelStyle(color, scale);
				return;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				String name = se.getName().getLocalPart();
				if (name.equals(SCALE)) {
                    String value = getNonEmptyElementText();
                    if (value != null)
                        try {
                            scale = Double.parseDouble(value);
                        } catch (NumberFormatException nfe) {
                            log.warn("Invalid scale value: " + value);
                        }
				} else if (name.equals(COLOR)) {
					color = parseColor(stream.getElementText());
				}
			}
		}
	}

	/**
	 * @param style
	 * @param qname the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleLineStyle(Style style, QName qname) throws XMLStreamException {
		double width = 1;
		Color color = Color.white;
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, qname)) {
				style.setLineStyle(color, width);
				return;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				String name = se.getName().getLocalPart();
				if (name.equals(WIDTH)) {
					String value = getNonEmptyElementText();
                    if (value != null)
                        try {
                            width = Double.parseDouble(value);
                        } catch (NumberFormatException nfe) {
                            log.warn("Invalid width value: " + value);
                        }
				} else if (name.equals(COLOR)) {
					String value = stream.getElementText();
					color = parseColor(value);
					if (color == null) {
						//log.warn("Invalid LineStyle color: " + value);
						color = Color.white; // use default
					}
				}
			}
		}
	}

	/**
	 * @param style
	 * @param qname the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleBalloonStyle(Style style, QName qname) throws XMLStreamException {
		String text = null;
		Color color = null;        // default 0xffffffff
		Color textColor = null;    // default 0xff000000
		String displayMode = null; // [default] | hide
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, qname)) {
				style.setBalloonStyle(color, text, textColor, displayMode);
				return;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				String name = se.getName().getLocalPart();
				if (name.equals(TEXT)) {
                    // Note: Google Earth 6.0.3 treats blank/empty text same as if missing.
                    // It's suggested that an earlier version handled blank string differently
                    // according to comments in some KML samples so we're preserving empty strings
                    // to force a BalloonStyle to be retained.
                    text = StringUtils.trim(stream.getElementText()); // allow empty string
				} else if (name.equals(BG_COLOR)) {
					color = parseColor(stream.getElementText());
				} else if (name.equals(DISPLAY_MODE)) {
					displayMode = getNonEmptyElementText();
				} else if (name.equals(TEXT_COLOR)) {
					textColor = parseColor(stream.getElementText());
				} else if (name.equals(COLOR)) {
					// color element is deprecated in KML 2.1
					// this is alias for bgColor
					color = parseColor(stream.getElementText());
				}
			}
		}
	}

    /**
	 * Get the href property from the Icon element.
	 *
	 * @param qname the qualified name of this event
	 * @return the href, <code>null</code> if not found.
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private String parseIconHref(QName qname) throws XMLStreamException {
		String href = null;
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, qname)) {
				return href;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				String name = se.getName().getLocalPart();
				if (name.equals(HREF)) {
					href = getNonEmptyElementText();
				}
			}
		}
	}

	/**
	 * Parse the color from a kml file, in {@code AABBGGRR} order.
	 *
	 * @param cstr
	 *            a hex encoded string, must be exactly 8 characters long.
	 * @return the color value, null if value is null, empty or invalid
	 */
	private Color parseColor(String cstr) {
		if (cstr == null) return null;
        cstr = cstr.trim();
		if (cstr.startsWith("#")) {
			// skip over '#' prefix used for HTML color codes allowed by Google Earth
			// but invalid wrt KML XML Schema.
			log.debug("Skip '#' in color code: " + cstr);
			cstr = cstr.substring(1);
		}
        if (cstr.length() == 8)
            try {
                int alpha = Integer.parseInt(cstr.substring(0, 2), 16);
                int blue = Integer.parseInt(cstr.substring(2, 4), 16);
                int green = Integer.parseInt(cstr.substring(4, 6), 16);
                int red = Integer.parseInt(cstr.substring(6, 8), 16);
                return new Color(red, green, blue, alpha);
            } catch (IllegalArgumentException ex) {
                // fall through and log bad value
            }

        log.warn("Invalid color value: " + cstr);
        return null;
	}

    /**
	 * @param style
	 * @param name the qualified name of this event
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void handleIconStyle(Style style, QName name) throws XMLStreamException {
		String url = null;
		Double scale = null; //1.0;		// default value
		Double heading = null; // 0.0;
		Color color = null; // Color.white;	// default="ffffffff"
		while (true) {
			XMLEvent e = stream.nextEvent();
			if (foundEndTag(e, name)) {
				try {
					style.setIconStyle(color, scale, heading, url);
				} catch (IllegalArgumentException iae) {
					log.warn("Invalid style: " + iae);
				}
				return;
			}
			if (e.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = e.asStartElement();
				QName qname = se.getName();
				String localPart = qname.getLocalPart();
				if (localPart.equals(SCALE)) {
					String value = getNonEmptyElementText();
					if (value != null)
						try {
							scale = Double.parseDouble(value);
						} catch (NumberFormatException nfe) {
							log.warn("Invalid scale value: " + value);
						}
				} else if (localPart.equals(HEADING)) {
					String value = getNonEmptyElementText();
					if (value != null)
						try {
							heading = Double.parseDouble(value);
						} catch (NumberFormatException nfe) {
							log.warn("Invalid heading value: " + value);
						}
				} else if (localPart.equals(COLOR)) {
					String value = stream.getElementText();
					color = parseColor(value);
					//if (color == null) {
						//log.warn("Invalid IconStyle color: " + value);
						//color = Color.white; // use default="ffffffff"
					//}
				} else if (localPart.equals(ICON)) {
                    // IconStyle/Icon is kml:BasicLinkType with only href property
					url = parseIconHref(qname);
                    // if have Icon element but no href then use empty string to indicate that Icon
                    // was present but don't have an associated href as handled in KmlOutputStream.
                    // Having empty Icon element is handled the same as having an empty href
                    // element in Google Earth.
                    if (url == null) url = "";
				}
			}
		}
	}

	/**
	 * @param e current XML element
	 * @return IGISObject representing current element,
	 * 			NullObject if failed to parse and unable to skip to end tag for that element
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 * @throws IOException if encountered NetworkLinkControl or out of order Style element
	 * 			and failed to skip to end tag for that element.
	 */
	private IGISObject handleStartElement(XMLEvent e) throws XMLStreamException, IOException {
		StartElement se = e.asStartElement();
		QName name = se.getName();
		String ns = name.getNamespaceURI();

        // handle non-kml namespace elements as foreign elements
        // review: should we check instead if namespace doesn't equal our root document namespace...
		// if namespace empty string then probably old-style "kml" root element without explicit namespace
		if (StringUtils.isNotEmpty(ns) && !ms_kml_ns.contains(ns)) {
            // //ns.startsWith("http://www.google.com/kml/ext/")) { ...
            // handle extension namespace
            // http://code.google.com/apis/kml/documentation/kmlreference.html#kmlextensions
			log.debug("XXX: handle as foreign element: " + name);
			return getForeignElement(se);
		}

		String localname = name.getLocalPart();
		String elementName = localname; // differs from localname if aliased by Schema mapping
		//System.out.println(localname); //debug
		// check if element has been aliased in Schema
		// only used for old-style KML 2.0/2.1 Schema defs with "parent" attribute.
		// generally only Placemarks are aliased. Not much use to alias Document or Folder elements, etc.
		if (schemaAliases != null) {
			String newName = schemaAliases.get(elementName);
			if (newName != null) {
				// log.info("Alias " + elementName + " -> " + newName);
				// Note: does not support multiple levels of aliases (e.g. Person <- Placemark; VipPerson <- Person, etc.)
				// To-date have only seen aliases for Placemarks so don't bother checking.
				elementName = newName;
			}
		}
		try {
			if (ms_features.contains(elementName)) {
				// all non-container elements that extend kml:AbstractFeatureType base type in KML Schema
				// Placemark, NetworkLink, GroundOverlay, ScreenOverlay, PhotoOverlay
				return handleFeature(e, elementName);
			} else if (ms_containers.contains(elementName)) {
				// all container elements that extend kml:AbstractContainerType base type in KML Schema
				//System.out.println("** handle container: " + elementName);
				return handleContainer(se);
			} else if (SCHEMA.equals(localname)) {
				return handleSchema(se, name);
			} else if (NETWORK_LINK_CONTROL.equals(localname)) {
				return handleNetworkLinkControl(stream, name);
			} else if (STYLE.equals(localname)) {
				log.debug("Out of order element: " + localname);
				// note this breaks the strict ordering required by KML 2.2
				return handleStyle(null, se, name);
			} else if (STYLE_MAP.equals(localname)) {
				log.debug("Out of order element: " + localname);
				// note this breaks the strict ordering required by KML 2.2
				return handleStyleMap(null, se, name);
			} else {
				String namespace = name.getNamespaceURI();
				if (ms_kml_ns.contains(namespace)) {
					// Look for next start element and recurse
					XMLEvent next = stream.nextTag();
					if (next != null && next.getEventType() == XMLEvent.START_ELEMENT) {
						return handleStartElement(next);
					}
				} else {
					log.debug("XXX: handle startElement with foreign namespace: " + name);
					return getForeignElement(se);
				}
			}
		} catch (XMLStreamException e1) {
			log.warn("Skip element: " + localname);
			log.debug("", e1);
			skipNextElement(stream, name);
		}

		// return non-null NullObject to skip but not end parsing...
		return NullObject.getInstance();
	}

	private IGISObject handleNetworkLinkControl(XMLEventReader stream, QName name) throws XMLStreamException {
		NetworkLinkControl c = new NetworkLinkControl();
		// if true indicates we're parsing the Update element
		boolean updateFlag = false;
		//String updateType = null;
		while (true) {
			XMLEvent next = stream.nextEvent();
			if (foundEndTag(next, name)) {
				break;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				QName qname = se.getName();
				String tag = qname.getLocalPart(); // never-null
				if (updateFlag) {
					if (tag.equals("targetHref")) {
						String val = getNonEmptyElementText();
						if (val != null) c.setTargetHref(val);
						// TODO: NetworkLinkControl can have 1 or more Update controls
						// TODO: -- handle Update details
					} else if (tag.equals("Create")) {
						c.setUpdateType("Create");
					} else if (tag.equals("Delete")) {
						c.setUpdateType("Delete");
					} else if (tag.equals("Change")) {
						c.setUpdateType("Change");
					}
				} else {
					if (tag.equals("minRefreshPeriod")) {
						Double val = getDoubleElementValue("minRefreshPeriod");
						if (val != null) c.setMinRefreshPeriod(val);
					} else if (tag.equals("maxSessionLength")) {
						Double val = getDoubleElementValue("maxSessionLength");
						if (val != null) c.setMaxSessionLength(val);
					} else if (tag.equals("cookie")) {
						String val = getNonEmptyElementText();
						if (val != null) c.setCookie(val);
					} else if (tag.equals("message")) {
						String val = getNonEmptyElementText();
						if (val != null) c.setMessage(val);
					} else if (tag.equals("linkName")) {
						String val = getNonEmptyElementText();
						if (val != null) c.setLinkName(val);
					} else if (tag.equals("linkDescription")) {
						String val = getNonEmptyElementText();
						if (val != null) c.setLinkDescription(val);
					} else if (tag.equals("linkSnippet")) {
						String val = getNonEmptyElementText();
						if (val != null) c.setLinkSnippet(val);
					} else if (tag.equals("expires")) {
						String expires = getNonEmptyElementText();
						if (expires != null)
							try {
								c.setExpires(parseDate(expires));
							} catch (ParseException e) {
								log.warn("Ignoring bad expires value: " + expires + ": " + e);
							}
					} else if (tag.equals(LOOK_AT) || tag.equals(CAMERA)) {
						TaggedMap viewGroup = handleTaggedData(qname); // LookAt | Camera
						c.setViewGroup(viewGroup);
					} else if (tag.equals("Update")) {
						updateFlag = true; // set flag to parse inside Update element
					}
				}
			} else {
				if (foundEndTag(next, "Update"))
					updateFlag = false;
			}
		}
		return c;
	}

	/**
	 * Return Schema object populated with SimpleFields as defined
	 * @param element
     * @param qname  the qualified name of this event
	 * @return
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private IGISObject handleSchema(StartElement element, QName qname)
			throws XMLStreamException {
		Schema s = new Schema();
		Attribute attr = element.getAttributeByName(new QName(NAME));
		String name = getNonEmptyAttrValue(attr);

		// get parent attribute for old-style KML 2.0/2.1 which aliases KML elements
		// (e.g. Placemarks) with user-defined ones.
/*
        <Schema name="S_FOBS_USA_ISAF_NATO_DSSSSSSDDDD" parent="Placemark">
            <SimpleField name="NAME" type="wstring"/>
            <SimpleField name="DATE" type="wstring"/>
            <SimpleField name="MGRS" type="wstring"/>
        </Schema>
*/
		attr = element.getAttributeByName(new QName(PARENT));
		String parent = getNonEmptyAttrValue(attr);
		Attribute id = element.getAttributeByName(ID_ATTR);
        /*
         * The �value space� of ID is the set of all strings that �match� the NCName production in [Namespaces in XML]:
         *  NCName ::=  (Letter | '_') (NCNameChar)*  -- An XML Name, minus the ":"
         *  NCNameChar ::=  Letter | Digit | '.' | '-' | '_' | CombiningChar | Extender
         */
		if (id != null) {
            // NOTE: reference and schema id must be handled exactly the same. See handleExtendedData().
            // Schema id is not really a URI but will be treated as such for validation for now.
            // NCName is subset of possible URI values.
            // Following characters cause fail URI creation: 0x20 ":<>[\]^`{|} so escape them
			String uri = UrlRef.escapeUri(id.getValue());
			// remember the schema for later references
			schemata.put(uri, s);
			try {
				s.setId(new URI(uri));
			} catch (URISyntaxException e) {
                // is URI properly encoded??
				log.warn("Invalid schema id " + uri, e);
			}
		}

		int gen = 0;
		while (true) {
			XMLEvent next = stream.nextEvent();
			if (foundEndTag(next, qname)) {
				break;
			}
			if (next.getEventType() == XMLEvent.START_ELEMENT) {
				StartElement se = next.asStartElement();
				if (foundStartTag(se, SIMPLE_FIELD)) {
					Attribute fname = se.getAttributeByName(new QName(NAME));
					String fieldname = fname != null ? fname.getValue() : "gen" + gen++;
					// http://code.google.com/apis/kml/documentation/kmlreference.html#simplefield
					// If either the type or the name is omitted, the field is ignored.
					try {
						SimpleField field = new SimpleField(fieldname);
						Attribute type = se.getAttributeByName(new QName(TYPE));
						SimpleField.Type ttype = SimpleField.Type.STRING; // default
						if (type != null) {
							String typeValue = type.getValue();
							// old-style "wstring" is just a string type
							if (StringUtils.isNotBlank(typeValue) && !"wstring".equalsIgnoreCase(typeValue))
								ttype = SimpleField.Type.valueOf(typeValue.toUpperCase());
						}
						field.setType(ttype);
						String displayName = parseDisplayName(SIMPLE_FIELD);
						field.setDisplayName(displayName);
						s.put(fieldname, field);
					} catch (IllegalArgumentException e) {
						log.warn("Invalid schema field " + fieldname + ": " + e.getMessage());
					}
				} else if (foundStartTag(se, PARENT)) {
					 // parent should only appear as Schema child element in KML 2.0 or 2.1
/*
        <Schema>
            <name>S_FOBS_USA_ISAF_NATO_DSSSSSSDDDD</name>
            <parent>Placemark</parent>
            <SimpleField name="NAME" type="string"/>
            <SimpleField name="DATE" type="string"/>
            <SimpleField name="MGRS" type="string"/>
        </Schema>
*/
					String parentVal = getNonEmptyElementText();
					if (parentVal != null) parent = parentVal;
				} else if (foundStartTag(se, NAME)) {
					 // name should only appear as Schema child element in KML 2.0 or 2.1
					String nameVal = getNonEmptyElementText();
					if (nameVal != null) name = nameVal;
				}
			}
		}

		if (name != null) s.setName(name);

		// define old-style Schema parent association
		if (parent != null) {
			s.setParent(parent);
			if (name != null) {
				// add alias to schema alias list
				if (schemaAliases == null)
					schemaAliases = new HashMap<String, String>();
				schemaAliases.put(name, parent);
			}
		}

		return s;
	}

	/**
	 * Returns non-empty text value from attribute. Functionally
     * same as calling <tt>StringUtils.trimToNull(attr.getValue())</tt>.
	 * @param attr Attribute
	 * @return non-empty text value trimmed from attribute,
	 * 			null if empty
	 */
	private static String getNonEmptyAttrValue(Attribute attr) {
		if (attr != null) {
			String value = attr.getValue();
			if (value != null) {
				value = value.trim();
				if (value.length() != 0) return value;
			}
		}
		return null;
	}

	/**
	 * @param tag
	 * @return
	 * @throws XMLStreamException
	 */
	private String parseDisplayName(String tag) throws XMLStreamException {
		String rval = null;
		while (true) {
			XMLEvent ee = stream.nextEvent();
			if (ee == null) {
				break;
			}
			if (ee.getEventType() == XMLStreamReader.START_ELEMENT) {
				StartElement sl = ee.asStartElement();
				QName name = sl.getName();
				String localname = name.getLocalPart();
				if (localname.equals(DISPLAY_NAME)) {
					rval = getNonEmptyElementText();
				}
			} else if (foundEndTag(ee, tag)) {
				break;
			}
		}
		return rval;
	}

	/**
	 * @param e
	 * @param type
	 * @return
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private IGISObject handleFeature(XMLEvent e, String type) throws XMLStreamException {
		StartElement se = e.asStartElement();
		boolean placemark = PLACEMARK.equals(type);
		boolean screen = SCREEN_OVERLAY.equals(type);
		boolean photo = PHOTO_OVERLAY.equals(type);
		boolean ground = GROUND_OVERLAY.equals(type);
		boolean network = NETWORK_LINK.equals(type);
		boolean isOverlay = screen || photo || ground;
		Feature fs;
		if (placemark) {
			fs = new Feature();
		} else if (screen) {
			fs = new ScreenOverlay();
		} else if (photo) {
			fs = new PhotoOverlay();
		} else if (ground) {
			fs = new GroundOverlay();
		} else if (network) {
			fs = new NetworkLink();
		} else {
			String localname = se.getName().getLocalPart();
			if (!localname.equals(type))
				log.error(String.format("Found new unhandled feature type: %s [%s]", type, localname));
			else
				log.error("Found new unhandled feature type: " + type);
			return NullObject.getInstance();
		}

		QName name = se.getName();
		addLast(fs);
		Attribute id = se.getAttributeByName(ID_ATTR);
		if (id != null) fs.setId(id.getValue());

		while (true) {
			XMLEvent ee = stream.nextEvent();
			// Note: if element has undeclared namespace then throws XMLStreamException
			// Message: http://www.w3.org/TR/1999/REC-xml-names-19990114#ElementPrefixUnbound
			if (foundEndTag(ee, name)) {
				break; // End of feature
			}
			if (ee.getEventType() == XMLStreamReader.START_ELEMENT) {
				StartElement sl = ee.asStartElement();
				QName qName = sl.getName();
				String localname = qName.getLocalPart();
				// Note: if element is aliased Placemark then metadata fields won't be saved
				// could treat as ExtendedData if want to preserve this data.
                if (network && OPEN.equals(localname)) {
                    if (isTrue(getElementText(qName)))
						((NetworkLink)fs).setOpen(true);
                }
				else if (!handleProperties(fs, ee, qName)) {
					// Deal with specific feature elements
					if (ms_geometries.contains(localname)) {
						// geometry: Point, LineString, LinearRing, Polygon, MultiGeometry, Model
                        try {
                            Geometry geo = handleGeometry(sl);
                            if (geo != null) {
                                fs.setGeometry(geo);
                            }
						} catch (XMLStreamException xe) {
							log.warn("Failed XML parsing: skip geometry " + localname);
							log.debug("", xe);
							skipNextElement(stream, qName);
                        } catch (RuntimeException rte) {
                            // IllegalStateException or IllegalArgumentException
                            log.warn("Failed geometry: " + fs, rte);
                        }
					} else if (isOverlay) {
						if (COLOR.equals(localname)) {
							((Overlay) fs).setColor(parseColor(stream
									.getElementText()));
						} else if (DRAW_ORDER.equals(localname)) {
							Integer val = getIntegerElementValue(DRAW_ORDER);
							if (val != null)
								((Overlay) fs).setDrawOrder(val);
						} else if (ICON.equals(localname)) {
							((Overlay) fs).setIcon(handleTaggedData(qName)); // Icon
						}
						if (ground) {
							if (LAT_LON_BOX.equals(localname)) {
								handleLatLonBox((GroundOverlay) fs, qName);
							} else if (ALTITUDE.equals(localname)) {
								String text = getNonEmptyElementText();
								if (text != null) {
									((GroundOverlay) fs).setAltitude(new Double(text));
								}
							} else if (ALTITUDE_MODE.equals(localname)) {
								// TODO: doesn't differentiate btwn kml:altitudeMode and gx:altitudeMode
								((GroundOverlay) fs).setAltitudeMode(
                                        getNonEmptyElementText());
							}
						} else if (screen) {
							if (OVERLAY_XY.equals(localname)) {
								ScreenLocation val = handleScreenLocation(sl);
								((ScreenOverlay) fs).setOverlay(val);
							} else if (SCREEN_XY.equals(localname)) {
								ScreenLocation val = handleScreenLocation(sl);
								((ScreenOverlay) fs).setScreen(val);
							} else if (ROTATION_XY.equals(localname)) {
								ScreenLocation val = handleScreenLocation(sl);
								((ScreenOverlay) fs).setRotation(val);
							} else if (SIZE.equals(localname)) {
								ScreenLocation val = handleScreenLocation(sl);
								((ScreenOverlay) fs).setSize(val);
							} else if (ROTATION.equals(localname)) {
								String val = getNonEmptyElementText();
                                if (val != null)
                                    try {
                                        double rot = Double.parseDouble(val);
                                        if (Math.abs(rot) <= 180)
                                            ((ScreenOverlay) fs).setRotationAngle(rot);
                                        else
                                            log.warn("Invalid ScreenOverlay rotation value " + val);
                                    } catch (IllegalArgumentException nfe) {
                                        log.warn("Invalid ScreenOverlay rotation " + val + ": " + nfe);
                                    }
							}
						} else if (photo) {
                            if (ROTATION.equals(localname)) {
								String val = getNonEmptyElementText();
                                if (val != null)
                                    try {
                                        double rot = Double.parseDouble(val);
                                        if (Math.abs(rot) <= 180)
                                            ((PhotoOverlay) fs).setRotation(rot);
                                        else
                                            log.warn("Invalid PhotoOverlay rotation value " + val);
                                    } catch (IllegalArgumentException nfe) {
                                        log.warn("Invalid PhotoOverlay rotation " + val + ": " + nfe);
                                    }
                            }
                            // TODO: fill in other properties (ViewVolume, ImagePyramid, shape)
                            // Note Point is populated above using setGeometry()
                        }
					} else if (network) {
						if (REFRESH_VISIBILITY.equals(localname)) {
							((NetworkLink) fs).setRefreshVisibility(isTrue(stream
									.getElementText()));
						} else if (FLY_TO_VIEW.equals(localname)) {
							((NetworkLink) fs).setFlyToView(isTrue(stream
									.getElementText()));
						} else if (LINK.equals(localname)) {
							((NetworkLink) fs).setLink(handleTaggedData(qName)); // Link
						} else if (URL.equals(localname)) {
							((NetworkLink) fs).setLink(handleTaggedData(qName)); // Url
						}
					}
				}
			}
		}
		return readSaved();
	}

	/**
	 * Process the attributes from the start element to create a screen location
	 *
	 * @param sl
	 *            the start element
	 * @return the location, never <code>null</code>.
	 */
	private ScreenLocation handleScreenLocation(StartElement sl) {
        try {
            ScreenLocation loc = new ScreenLocation();
            Attribute x = sl.getAttributeByName(new QName("x"));
            Attribute y = sl.getAttributeByName(new QName("y"));
            Attribute xunits = sl.getAttributeByName(new QName("xunits"));
            Attribute yunits = sl.getAttributeByName(new QName("yunits"));
            if (x != null) {
                loc.x = new Double(x.getValue());
            }
            if (y != null) {
                loc.y = new Double(y.getValue());
            }
            if (xunits != null) {
                String val = xunits.getValue();
                loc.xunit = ScreenLocation.UNIT.valueOf(val.toUpperCase());
            }
            if (yunits != null) {
                String val = yunits.getValue();
                loc.yunit = ScreenLocation.UNIT.valueOf(val.toUpperCase());
            }
            return loc;
        } catch (IllegalArgumentException iae) {
            log.error("Invalid screenLocation", iae);
            return null;
        }
    }

	/**
	 * Handle a set of elements with character values. The block has been found
	 * that starts with a &lt;localname&gt; tag, and it will end with a matching
	 * tag. All other elements found will be added to a created map object.
	 *
	 * @param name
	 *            the QName, assumed not <code>null</code>.
	 * @param map
	 *            TaggedMap to provide, never null
	 * @return the map, null if no non-empty values are found
	 * @throws XMLStreamException if there is an error with the underlying XML
	 */
	private TaggedMap handleTaggedData(QName name, TaggedMap map)
			throws XMLStreamException {
		String rootNs = name.getNamespaceURI();
		while (true) {
			XMLEvent event = stream.nextEvent();
			if (foundEndTag(event, name)) {
				break;
			}
			if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
				StartElement se = event.asStartElement();
				QName qname = se.getName();
				String sename = qname.getLocalPart();
				if (rootNs != null) {
					// rootNs should never be null. should be empty string "" if default xmlns
					// ignore extension elements. don't know how to parse inside them yet
					// e.g. http://www.google.com/kml/ext/2.2
					// only add those element that are part of the KML namespace which we expect
					String ns = qname.getNamespaceURI();
                    if (ns != null && !rootNs.equals(ns)) {
                        if (!handleExtension(map, se, qname)) {
                            log.debug("Skip " + qname.getPrefix() + ":" + sename);
                        }
                        continue;
                    }
                }
                String value;
                // ignore empty elements; e.g. <Icon><href /></Icon>
                // except viewFormat tag in Link which is allowed to have empty string value
                if (VIEW_FORMAT.equals(sename)) {
                    value = StringUtils.trim(stream.getElementText()); // allow empty string
                } else {
                    value = getNonEmptyElementText();
                }
                if (value != null)
                    map.put(sename, value);
			}
		}
		return map.isEmpty() ? null : map;
	}

    private TaggedMap handleTaggedData(QName name) throws XMLStreamException {
		// handle Camera, LookAt, Icon, Link, and Url elements
		return handleTaggedData(name, new TaggedMap(name.getLocalPart()));
	}

    private boolean handleExtension(TaggedMap map, StartElement se, QName qname) throws XMLStreamException {
        String ns = qname.getNamespaceURI();
		// tagged data used to store child text-content elements for following elements:
		// Camera, LookAt, LatLonAltBox, Lod, Icon, Link, and Url
        // TODO: allow other extensions for TaggedMaps besides gx namespace ?
        if (ns.startsWith(NS_GOOGLE_KML_EXT_PREFIX)) {
			return handleElementExtension(map, (Element) getForeignElement(se), null);
        } else {
			skipNextElement(stream, qname);
			return false;
        }
    }

    private boolean handleElementExtension(TaggedMap map, Element el, String namePrefix) {
        /*
         LookAt/Camera elements can include gx:TimeSpan or gx:TimeStamp child elements:

         <gx:TimeSpan>
           <begin>2010-05-28T02:02:09Z</begin>
           <end>2010-05-28T02:02:56Z</end>
         </gx:TimeSpan>
        */
        String prefix = el.getPrefix();
        String ns = el.getNamespaceURI();
        // use "gx" handle regardless of what KML uses for gx namespace
        if (ns.startsWith(NS_GOOGLE_KML_EXT_PREFIX)) prefix = "gx";

        if (!el.getChildren().isEmpty()) {
            boolean found = false;
            String eltname = el.getName();
            if (StringUtils.isNotBlank(prefix)) {
                eltname = prefix + ":" + eltname;
            }
            if (namePrefix == null) namePrefix = eltname;
            else namePrefix += "/" + eltname;
            for (Element child : el.getChildren()) {
                if (handleElementExtension(map, child, namePrefix))
                    found = true; // got match
            }
            return found;
        }

        // if not Node then look for simple TextElement
        String text = el.getText();
        if (StringUtils.isBlank(text)) {
            return false;
        }

        String eltname = el.getName();
        if (StringUtils.isNotBlank(prefix)) {
            if (ALTITUDE_MODE.equals(eltname)) {
                // handle altitudeMode as special case. store w/o prefix since handled as a single attribute
                // if have gx:altitudeMode and altitudeMode then altitudeMode overrides gx:altitudeMode
				// Note Google Earth deliberately generates Placemarks with both gx:altitudeMode and altitudeMode
				// for backward compatibility breaking strict conformance to the official OGC KML 2.2 Schema !!!
				// see http://code.google.com/p/earth-issues/issues/detail?id=1182
                if (map.containsKey(ALTITUDE_MODE)) {
                    log.debug("Element has duplicate altitudeMode defined"); // ignore but return as element processed
                    return true;
                }
            } else {
                eltname = prefix + ":" + eltname; // prefix name with namespace prefix
            }
            log.debug("Handle tag data " + prefix + ":" + el.getName());
        } // else log.debug("non-prefix ns=" + el.getNamespace());
        if (namePrefix == null) namePrefix = eltname;
        else namePrefix += "/" + eltname;
        map.put(namePrefix, text);
        return true;
    }

	/**
	 * Handle a LatLonBox element with north, south, east and west elements.
	 *
	 * @param overlay
	 * @param name  the qualified name of this event
     * @throws XMLStreamException if there is an error with the underlying XML
	 */
	private void handleLatLonBox(GroundOverlay overlay, QName name)
			throws XMLStreamException {
		while (true) {
			XMLEvent event = stream.nextEvent();
			if (foundEndTag(event, name)) {
				break;
			}
			if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
				StartElement se = event.asStartElement();
				String sename = se.getName().getLocalPart();
				String value = getNonEmptyElementText();
				if (value != null) {
                    try {
                        Double angle = Double.valueOf(value);
                        if (NORTH.equals(sename)) {
                            overlay.setNorth(angle);
                        } else if (SOUTH.equals(sename)) {
                            overlay.setSouth(angle);
                        } else if (EAST.equals(sename)) {
                            overlay.setEast(angle);
                        } else if (WEST.equals(sename)) {
							// normalize west: value < -180 and add 360.
							// reverse hack/fix in KmlOutputStream for bug in Google Earth crossing IDL
							// must be consistent with handling in KmlOutputStream.handleOverlay()
							if (angle < -180) {
								log.debug("Normalized GroundOverlay west value");
								angle += 360;
							}
                            overlay.setWest(angle);
                        } else if (ROTATION.equals(sename)) {
                            overlay.setRotation(angle);
                        }
                    } catch (NumberFormatException nfe) {
                        log.error("Invalid GroundOverlay angle " + value + " in " + sename);
                    } catch (IllegalArgumentException nfe) {
                        log.error("Invalid GroundOverlay value in " + sename + ": " + nfe);
                    }
                }
			}
		}
	}

	/**
	 * Parse and process the geometry for the feature and store in the feature
	 *
	 * @param sl StartElement
     * @return Geometry associated with this element
     *          otherwise null if no valid Geometry can be constructed
     * @throws XMLStreamException if there is an error with the underlying XML
     * @throws IllegalStateException if geometry is invalid
     * @throws IllegalArgumentException if geometry is invalid (e.g. Line has < 2 points, etc.)
	 */
	@SuppressWarnings("unchecked")
	private Geometry handleGeometry(StartElement sl) throws XMLStreamException {
		QName name = sl.getName();
		String localname = name.getLocalPart();
		// localname must match: { Point, MultiGeometry, Model }, or { LineString, LinearRing, Polygon }
		// note: gx:altitudeMode may be found within geometry elements but doesn't appear to affect parsing
		if (localname.equals(POINT)) {
			return parseCoordinate(name);
		} else if (localname.equals(MULTI_GEOMETRY)) {
			List<Geometry> geometries = new ArrayList<Geometry>();
			while (true) {
				XMLEvent event = stream.nextEvent();
				if (foundEndTag(event, name)) {
					break;
				}
				if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
					StartElement el = (StartElement) event;
					String tag = el.getName().getLocalPart();
					// tag must match: Point, LineString, LinearRing, Polygon, MultiGeometry, or Model
					if (ms_geometries.contains(tag)) {
						try {
							Geometry geom = handleGeometry(el);
							if (geom != null) geometries.add(geom);
						} catch (RuntimeException rte) {
							// IllegalStateException or IllegalArgumentException
							log.warn("Failed geometry: " + tag, rte);
						}
					}
				}
			}
            // if no valid geometries then return null
            if (geometries.isEmpty()) {
                log.debug("No valid geometries in MultiGeometry");
                return null;
            }
            // if only one valid geometry then drop collection and use single geometry
            if (geometries.size() == 1) {
                log.debug("Convert MultiGeometry to single geometry");
				// tesselate/extrude properties are preserved on target geometry
                return geometries.get(0);
            }
			boolean allpoints = true;
			for(Geometry geo : geometries) {
				if (geo != null && geo.getClass() != Point.class) {
					allpoints = false;
					break;
				}
			}
			if (allpoints) {
				return new MultiPoint((List) geometries);
			} else {
				return new GeometryBag(geometries);
			}
		} else if (localname.equals(MODEL)) {
			// we don't really have a way to represent this yet
            Model model = new Model();
            while (true) {
				XMLEvent event = stream.nextEvent();
				if (foundEndTag(event, name)) {
					break;
				}
				if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
					StartElement se = event.asStartElement();
					QName qname = se.getName();
					String localPart = qname.getLocalPart();
					if (localPart.equals(LOCATION)) {
                        Geodetic2DPoint point = parseLocation(qname);
                        if (point != null)
                            model.setLocation(point);
                    } else if (localPart.equals(ALTITUDE_MODE)) {
						// TODO: doesn't differentiate btwn kml:altitudeMode and gx:altitudeMode
                        model.setAltitudeMode(getNonEmptyElementText());
                    }
					// todo: Orientation, Scale, Link, ResourceMap
				}
            }
            return model;
		} else {
			// otherwise try LineString, LinearRing, Polygon
			return getGeometryBase(name, localname);
		}
	}

	/**
	 * Construct Geometry from the KML
	 * @param name  the qualified name of this event
	 * @param localname local part of this <code>QName</code>
	 * @return geometry
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 * @exception IllegalArgumentException if geometry is invalid (e.g. no valid coordinates)
	 * @throws IllegalStateException if Bad poly found (e.g. no outer ring)
	 */
	private GeometryBase getGeometryBase(QName name, String localname) throws XMLStreamException {
		if (localname.equals(LINE_STRING)) {
			GeometryGroup geom = parseCoordinates(name);
			if (geom.size() == 1) {
				Point pt = geom.points.get(0);
				log.info("line with single coordinate converted to point: " + pt);
				return getGeometry(geom, pt);
			} else {
				// if geom.size() == 0 throws IllegalArgumentException
				return getGeometry(geom, new Line(geom.points));
			}
		} else if (localname.equals(LINEAR_RING)) {
			GeometryGroup geom = parseCoordinates(name);
			if (geom.size() == 1) {
				Point pt = geom.points.get(0);
				log.info("ring with single coordinate converted to point: " + pt);
				return getGeometry(geom, pt);
			} else if (geom.size() != 0 && geom.size() < 4) {
				log.info("ring with " + geom.size() + " coordinates converted to line: " + geom);
				return getGeometry(geom, new Line(geom.points));
			} else {
				// if geom.size() == 0 throws IllegalArgumentException
				return getGeometry(geom, new LinearRing(geom.points));
			}
		} else if (localname.equals(POLYGON)) {
			// contains one outer ring and 0 or more inner rings
			LinearRing outer = null;
			GeometryGroup geom = new GeometryGroup();
			List<LinearRing> inners = new ArrayList<LinearRing>();
			while (true) {
				XMLEvent event = stream.nextEvent();
				if (foundEndTag(event, name)) {
					break;
				}
				if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
					StartElement se = event.asStartElement();
					QName qname = se.getName();
					String sename = qname.getLocalPart();
					if (sename.equals(OUTER_BOUNDARY_IS)) {
						parseCoordinates(qname, geom);
						int nPoints = geom.size();
						if (nPoints == 1) {
							Point pt = geom.points.get(0);
							log.info("polygon with single coordinate converted to point: " + pt);
							return getGeometry(geom, pt);
						} else if (nPoints != 0 && nPoints < 4) {
							// less than 4 points - use line for the shape
							log.info("polygon with " + nPoints + " coordinates converted to line: " + geom);
							Line line = new Line(geom.points);
							return getGeometry(geom, line);
						}
						// if geom.size() == 0 throws IllegalArgumentException
						outer = new LinearRing(geom.points);
					} else if (sename.equals(INNER_BOUNDARY_IS)) {
						GeometryGroup innerRing = parseCoordinates(qname);
						if (innerRing.size() != 0)
							inners.add(new LinearRing(innerRing.points));
					} else if (sename.equals(ALTITUDE_MODE)) {
						// TODO: doesn't differentiate btwn kml:altitudeMode and gx:altitudeMode
						geom.altitudeMode = getNonEmptyElementText();
					} else if (EXTRUDE.equals(sename)) {
						if (isTrue(stream.getElementText()))
							geom.extrude = Boolean.TRUE;
						// default 0/false
					} else if (TESSELLATE.equals(sename)) {
						if (isTrue(stream.getElementText()))
							geom.tessellate = Boolean.TRUE;
						// default 0/false
					}
				}
			}
			if (outer == null) {
				throw new IllegalStateException("Bad poly found, no outer ring");
			}
			return getGeometry(geom, new Polygon(outer, inners));
		}

		return null;
	}

	/**
	 * Map properties of GeometryGroup onto the created <code>GeometryBase</code>
	 * @param group
	 * @param geom GeometryBase, never null
	 * @return filled in GeometryBase object
	 */
	private static GeometryBase getGeometry(GeometryGroup group, GeometryBase geom) {
		if (group != null) {
			if (group.tessellate != null)
				geom.setTessellate(group.tessellate);
			if (group.extrude != null)
				geom.setExtrude(group.extrude);
			if (group.altitudeMode != null)
				geom.setAltitudeMode(group.altitudeMode);
		}
		return geom;
	}

	private Geodetic2DPoint parseLocation(QName qname) throws XMLStreamException {
        Latitude latitude = null;
        Longitude longitude = null;
        Double altitude = null;
        while (true) {
            XMLEvent event = stream.nextEvent();
			if (foundEndTag(event, qname)) {
                break;
			}
            if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
                StartElement se = event.asStartElement();
                String name = se.getName().getLocalPart();
                if (name.equals(LATITUDE)) {
                    String value = getNonEmptyElementText();
                    if (value != null)
                        try {
                            latitude = new Latitude(Double.parseDouble(value), Angle.DEGREES);
                        } catch (IllegalArgumentException nfe) {
                            log.warn("Invalid latitude value: " + value);
                        }
                } else if (name.equals(LONGITUDE)) {
                    String value = getNonEmptyElementText();
                    if (value != null)
                        try {
                            longitude = new Longitude(Double.parseDouble(value), Angle.DEGREES);
                        } catch (IllegalArgumentException nfe) {
                            log.warn("Invalid longitude value: " + value);
                        }
                } else if (name.equals(ALTITUDE)) {
                    String value = getNonEmptyElementText();
                    if (value != null)
                        try {
                            altitude = Double.valueOf(value);
                        } catch (NumberFormatException nfe) {
                            log.warn("Invalid altitude value: " + value);
                        }
                }
            }
        }

        if (longitude == null && latitude == null) return null;
        if (longitude == null) longitude = new Longitude();
        else if (latitude == null) latitude = new Latitude();
        return altitude == null ? new Geodetic2DPoint(longitude, latitude)
                : new Geodetic3DPoint(longitude, latitude, altitude);
    }

	/**
	 * Find the coordinates element, extract the lat/lon/alt properties,
	 * and store in a <tt>GeometryGroup</tt> object. The element name
	 * is used to spot if we leave the "end" of the block. The stream
	 * will be positioned after the element when this returns.
	 *
	 * @param qname  the qualified name of this event
     * @param geom GeomBase, never null
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private void parseCoordinates(QName qname, GeometryGroup geom) throws XMLStreamException {
		while (true) {
			XMLEvent event = stream.nextEvent();
			if (foundEndTag(event, qname)) {
				break;
			}
			if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
				final QName name = event.asStartElement().getName();
				String localPart = name.getLocalPart();
				if (COORDINATES.equals(localPart)) {
					String text = getNonEmptyElementText();
					if (text != null) geom.points = parseCoord(text);
				}
				else if (ALTITUDE_MODE.equals(localPart)) {
					// Note: handle kml:altitudeMode and gx:altitudeMode
					// if have both forms then use one from KML namespace as done in handleElementExtension()
					if (geom.altitudeMode == null || ms_kml_ns.contains(name.getNamespaceURI()))
						geom.altitudeMode = getNonEmptyElementText();
					else {
						// e.g. qName = {http://www.google.com/kml/ext/2.2}altitudeMode
						log.debug("Skip duplicate value for " + name);
					}
				}
				else if (EXTRUDE.equals(localPart)) {
					if (isTrue(stream.getElementText()))
						geom.extrude = Boolean.TRUE;
				}
				else if (TESSELLATE.equals(localPart)) {
					if (isTrue(stream.getElementText()))
						geom.tessellate = Boolean.TRUE;
				}
			}
		}
		if (geom.points == null) geom.points = Collections.emptyList();
	}

    /**
	 * Find/parse the coordinates element, extract the lat/lon/alt properties,
	 * and return in a <tt>GeometryGroup</tt> object. The element name is used
	 * to spot if we leave the "end" of the block. The stream will be positioned
	 * after the element when this returns.
	 *
	 * @param qname  the qualified name of this event
	 * @return the list coordinates, empty list if no valid coordinates are found
     * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private GeometryGroup parseCoordinates(QName qname) throws XMLStreamException {
		GeometryGroup geom = new GeometryGroup();
		parseCoordinates(qname, geom);
		return geom;
	}

	/**
	 * Find the coordinates element for Point and extract the lat/lon/alt
	 * properties optionally with extrude and altitudeMode. The element name
	 * is used to spot if we leave the "end" of the block. The stream will
	 * be positioned after the element when this returns.
	 *
	 * @param name  the qualified name of this event
	 * @return the coordinate (first valid coordinate if found), null if not
	 * @throws XMLStreamException if there is an error with the underlying XML.
	 */
	private Point parseCoordinate(QName name) throws XMLStreamException {
		Point rval = null;
		String altitudeMode = null;
		Boolean extrude = null;
		while (true) {
			XMLEvent event = stream.nextEvent();
			if (foundEndTag(event, name)) {
				break;
			}
			if (event.getEventType() == XMLStreamReader.START_ELEMENT) {
				final QName qName = event.asStartElement().getName();
				String localPart = qName.getLocalPart();
				if (COORDINATES.equals(localPart)) {
					String text = getNonEmptyElementText();
					// allow sloppy KML with whitespace appearing before/after
					// lat and lon values; e.g. <coordinates>-121.9921875, 37.265625</coordinates>
					// http://kml-samples.googlecode.com/svn/trunk/kml/ListStyle/radio-folder-vis.kml
					if (text != null) rval = parsePointCoord(text);
				}
				else if (ALTITUDE_MODE.equals(localPart)) {
					// Note: handle kml:altitudeMode and gx:altitudeMode
					// if have both forms then use one from KML namespace as done in handleElementExtension()
					if (altitudeMode == null || ms_kml_ns.contains(qName.getNamespaceURI()))
						altitudeMode = getNonEmptyElementText();
					else {
						// e.g. qName = {http://www.google.com/kml/ext/2.2}altitudeMode
						log.debug("Skip duplicate value for " + qName);
					}
				}
				else if (EXTRUDE.equals(localPart)) {
					if (isTrue(stream.getElementText()))
						extrude = Boolean.TRUE;
				}
				// Note tessellate tag is not applicable to Point
			}
		}
		if (rval != null) {
			if (altitudeMode != null) rval.setAltitudeMode(altitudeMode);
			if (extrude != null) rval.setExtrude(extrude);
		}
		return rval;
	}

	private static Point parsePointCoord(String coord) {
		List<Point> list = parseCoord(coord);
		return list.isEmpty() ? null : list.get(0);
	}

	/**
	 * Coordinate parser that matches the loose parsing of coordinates in Google Earth.
	 * KML reference states "Do not include spaces within a [coordinate] tuple" yet
	 * Google Earth allows whitespace to appear anywhere in the input or commas
     * to appear between tuples (e.g., <tt>1,2,3,4,5,6 -> 1,2,3  4,5,6</tt>).
	 *
	 * <ul>
	 * <li> Simple state machine parsing keeps track of what part of the coordinate
	 * 		had been found so far.
	 * <li> Extra whitespace is allowed anywhere in the string.
	 * <li> Invalid text in input is ignored.
	 * </ul>
	 *
	 * @param coord Coordinate string
	 * @return list of coordinates. Returns empty list if no coordinates are valid, never null
	 */
    @NonNull
	public static List<Point> parseCoord(String coord) {
		List<Point> list = new ArrayList<Point>();
		NumberStreamTokenizer st = new NumberStreamTokenizer(coord);
		st.ordinaryChar(',');
		boolean seenComma = false;
		int numparts = 0;
		double elev = 0;
		Longitude lon = null;
		Latitude lat = null;
        // note the NumberStreamTokenizer may introduce some floating-error (e.g., 5.5 -> 5.499999999999999)
		try {
			while (st.nextToken() != NumberStreamTokenizer.TT_EOF) {
				switch (st.ttype) {
					case NumberStreamTokenizer.TT_WORD:
						//s = "STRING:" + st.sval; // Already a String
						log.warn("ignore invalid string in coordinate: \"" + st.sval + "\"");
						//if (seenComma) System.out.println("\tXXX: WORD: seenComma");
						//if (numparts != 0) System.out.println("\tXXX: WORD: numparts=" + numparts);
						break;

					case NumberStreamTokenizer.TT_NUMBER:
						try {
                            if (numparts == 3) {
                                if (seenComma) {
                                    log.warn("comma found instead of whitespace between tuples before " + st.nval);
                                    // handle commas appearing between tuples
                                    // Google Earth interprets input with: "1,2,3,4,5,6" as two tuples: {1,2,3}  {4,5,6}.
                                    seenComma = false;
                                }
                                // add last coord to list and reset counter
                                if (lon != COORD_ERROR)
                                    list.add(new Point(new Geodetic3DPoint(lon, lat, elev)));
                                numparts = 0; // reset state for start of new tuple
                            }

							switch (++numparts) {
								case 1:
									if (seenComma) {
										// note: this branch might not be possible: if numparts==0 then seenComma should be false
										lat = new Latitude(st.nval, Angle.DEGREES);
										lon = new Longitude(); // skipped longitude (use 0 degrees)
										numparts = 2;
									} else {
										// starting new coordinate
										lon = new Longitude(st.nval, Angle.DEGREES);
									}
									break;

								case 2:
									if (seenComma) {
										//System.out.println("lat=" + st.nval);
										lat = new Latitude(st.nval, Angle.DEGREES);
									} else {
										if (lon != COORD_ERROR)
											list.add(new Point(new Geodetic2DPoint(
													lon, new Latitude())));
										//else System.out.println("\tERROR: drop bad coord");
										// start new tuple
										lon = new Longitude(st.nval, Angle.DEGREES);
										numparts = 1;
									}
									break;

								case 3:
									if (seenComma) {
										elev = st.nval;
									} else {
										if (lon != COORD_ERROR)
											list.add(new Point(new Geodetic2DPoint(lon, lat)));
										//else System.out.println("\tERROR: drop bad coord");
										// start new tuple
										numparts = 1;
										lon = new Longitude(st.nval, Angle.DEGREES);
									}
									break;
							}

							//s = "NUM:" + Double.toString(st.nval);
							/*
							 double nval = st.nval;
							 if (st.nextToken() == StreamTokenizer.TT_WORD && expPattern.matcher(st.sval).matches()) {
								 s = "ENUM:" + Double.valueOf(Double.toString(nval) + st.sval).toString();
							 } else {
								 s = "NUM:" + Double.toString(nval);
								 st.pushBack();
							 }
							 */
						} catch (IllegalArgumentException e) {
							// bad lat/longitude; e.g. out of valid range
							log.error("Invalid coordinate: " + st.nval, e);
							if (numparts != 0) lon = COORD_ERROR;
						}
						seenComma = false; // reset flag
						break;

					default: // single character in ttype
						if (st.ttype == ',') {
							if (!seenComma) {
								// start of next coordinate component
								seenComma = true;
								if (numparts == 0) {
									//System.out.println("\tXXX: WARN: COMMA0: seenComma w/numparts=" + numparts);
									lon = new Longitude(); // skipped longitude (use 0 degrees)
									numparts = 1;
								}
							} else
                                // seenComma -> true
                                if (numparts == 1) {
                                    //System.out.println("\tXXX: WARN: COMMA2: seenComma w/numparts=" + numparts);
                                    lat = new Latitude();  // skipped Latitude (use 0 degrees)
                                    numparts = 2;
                                } else if (numparts == 0) {
                                    // note this branch may never occur since seenComa=true implies numparts > 0
                                    //System.out.println("\tXXX: WARN: COMMA1: seenComma w/numparts=" + numparts);
                                    lon = new Longitude(); // skipped longitude (use 0 degrees)
                                    numparts = 1;
                                }
                                //else System.out.println("\tXXX: ** ERROR: COMMA3: seenComma w/numparts=" + numparts);
						} else
							log.warn("ignore invalid character in coordinate string: (" + (char) st.ttype + ")");
						//s = "CHAR:" + String.valueOf((char) st.ttype);
				}
				//System.out.println("\t" + s);
			} // while
		} catch (IOException e) {
			// we're using StringReader. this should never happen
			log.error("Failed to parse coord string: " + coord == null || coord.length() <= 20
                    ? coord : coord.substring(0,20) + "...", e);
		}

		// add last coord if valid
		if (numparts != 0 && lon != COORD_ERROR)
			switch (numparts) {
				case 1:
					list.add(new Point(new Geodetic2DPoint(lon, new Latitude())));
					break;
				case 2:
					list.add(new Point(new Geodetic2DPoint(lon, lat)));
					break;
				case 3:
					list.add(new Point(new Geodetic3DPoint(lon, lat, elev)));
			}

		return list;
	}

	/**
	 * @param e
	 * @return
	 */
	private IGISObject handleEndElement(XMLEvent e) {
		EndElement ee = e.asEndElement();
		String localname = ee.getName().getLocalPart();

		if (ms_containers.contains(localname)) {
			return new ContainerEnd();
		}

		return null;
	}

	private static class GeometryGroup {
		List<Point> points;
		String altitudeMode;
		Boolean extrude;
		Boolean tessellate;

		int size() {
			return points == null ? 0 : points.size();
		}
		public String toString() {
			return String.valueOf(points);
		}
	}

}
