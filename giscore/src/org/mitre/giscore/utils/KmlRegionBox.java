package org.mitre.giscore.utils;

import org.mitre.giscore.input.kml.KmlReader;
import org.mitre.giscore.input.kml.IKml;
import org.mitre.giscore.input.kml.UrlRef;
import org.mitre.giscore.events.*;
import org.mitre.giscore.geometry.Point;
import org.mitre.giscore.geometry.LinearRing;
import org.mitre.giscore.output.kml.KmlOutputStream;
import org.mitre.giscore.DocumentType;
import org.mitre.itf.geodesy.Geodetic2DBounds;
import org.apache.commons.lang.StringUtils;

import javax.xml.stream.XMLStreamException;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URI;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.ArrayList;

/**
 * Create KML output with bounding box outlines from KML regions.
 * 
 * Parse KML sources and extract each bounding box from defined Regions ignoring
 * duplicates.  Creates a KML output file 'bbox.kml' (or as specified) in current
 * directory with a Placemark with LinearRing geometry for the bounding box of
 * each Region with a valid LatLonAltBox.
 * 
 * @author Jason Mathews, MITRE Corp.
 * Date: Nov 5, 2009 9:18:24 PM
 */
public class KmlRegionBox {

	private KmlOutputStream kos;
	private final List<Geodetic2DBounds> regions = new ArrayList<Geodetic2DBounds>();
	private String outFile;
	private boolean followLinks;

	public void checkSource(URL url) throws IOException, XMLStreamException {
		System.out.println(url);
		processKmlSource(new KmlReader(url), url.toString());
	}

	public void checkSource(File file) throws XMLStreamException, IOException {
		if (file.isDirectory()) {
			for (File f : file.listFiles())
				if (f.isDirectory())
					checkSource(f);
				else {
					String name = f.getName().toLowerCase();
					if (name.endsWith(".kml") || name.endsWith(".kmz"))
						checkSource(f);
				}
		} else {
			System.out.println(file.getAbsolutePath());
			String name = file.getName();
			if (name.equals("doc.kml")) {
				File parent = file.getParentFile();
				if (parent != null)
					name = parent.getName() + "/" + name;
			}
			processKmlSource(new KmlReader(file), name);
		}
	}

	private void processKmlSource(KmlReader reader, String source) throws XMLStreamException, IOException {
		try {
			IGISObject o;
			while ((o = reader.read()) != null) {
				checkObject(o, source);
			}
		} finally {
			reader.close();
		}

		if (followLinks) {
			List<URI> networkLinks = reader.getNetworkLinks();
			if (networkLinks.size() != 0) {
				reader.importFromNetworkLinks(new KmlReader.ImportEventHandler() {
                    private URI last;
					public boolean handleEvent(UrlRef ref, IGISObject gisObj) {
                        URI uri = ref.getURI();
                        if (!uri.equals(last)) {
							// first gisObj found from a new KML source
                            System.out.println("Check NetworkLink: " +
                                    (ref.isKmz() ? ref.getKmzRelPath() : uri.toString()));
                            System.out.println();
                            last = uri;
                        }
						try {
							checkObject(gisObj, ref.toString());
						} catch (Exception e) {
							System.out.println("\t*** " + e.getMessage());
							return false;
						}
						return true;
					}
				});
			}
		}
	}

	private void checkObject(IGISObject o, String source) throws FileNotFoundException, XMLStreamException {
		if (o instanceof Common) {
			Common f = (Common) o;
			TaggedMap region = f.getRegion();
			if (region != null) {
				List<Point> pts;
				String name = f.getName();
				try {
					double north = handleTaggedElement(IKml.NORTH, region, 90);
					double south = handleTaggedElement(IKml.SOUTH, region, 90);
					double east = handleTaggedElement(IKml.EAST, region, 180);
					double west = handleTaggedElement(IKml.WEST, region, 180);
					if (Math.abs(north - south) < 1e-5 || Math.abs(east - west) < 1e-5) {
						// incomplete bounding box or too small so skip it
						// 0.0001 (1e-4) degree dif  =~ 10 meter
						// 0.00001 (1e-5) degree dif =~ 1 meter
						// if n/s/e/w values all 0's then ignore it
						if (north != 0 || south != 0 || east != 0 || west != 0)
							System.out.println("\tbbox appears to be very small area: " + name);
						return;
					}

					// check valid Region-LatLonAltBox values:
					// kml:north > kml:south; lat range: +/- 90
					// kml:east > kml:west;   lon range: +/- 180
					if (north < south || east < west) {
						System.out.println("\tRegion has invalid LatLonAltBox: " + name);
					}

					pts = new ArrayList<Point>(5);
					pts.add(new Point(north, west));
					pts.add(new Point(north, east));
					pts.add(new Point(south, east));
					pts.add(new Point(south, west));
					pts.add(pts.get(0));
				} catch (NumberFormatException nfe) {
					System.out.println("\t" + nfe.getMessage() + ": " + name);
					return;
				}
				LinearRing ring = new LinearRing(pts);

				Geodetic2DBounds bounds = ring.getBoundingBox();
				if (regions.contains(bounds)) {
					System.out.println("\tduplicate bbox: " + bounds);
					return;
				}
				regions.add(bounds);
				//regions.put(bounds, bbox);

				Feature bbox = new Feature();
				bbox.setDescription(source);
				ring.setTessellate(true);
				bbox.setGeometry(ring);
				if (StringUtils.isNotBlank(name))
					bbox.setName(name + " bbox");
				else
					bbox.setName("bbox");

				if (kos == null) {
					// initialize KmlOutputStream
					if (StringUtils.isBlank(outFile)) outFile = "bbox.kml";
					kos = new KmlOutputStream(new FileOutputStream(outFile));
					kos.write(new DocumentStart(DocumentType.KML));
					ContainerStart cs = new ContainerStart(IKml.FOLDER);
					cs.setName("Region boxes");
					kos.write(cs);
				}
				kos.write(bbox);
			}
		}
	}

	private static double handleTaggedElement(String tag, TaggedMap region, int maxDegrees) {
		String val = region.get(tag);
		if (val != null && val.length() != 0) {
			double rv;
			try {
				rv = Double.parseDouble(val);
			} catch (NumberFormatException nfe) {
				throw new NumberFormatException(String.format("Invalid value: %s=%s", tag, val));
			}
			if (Math.abs(rv) > maxDegrees) {
				throw new NumberFormatException(String.format("Invalid value out of range: %s=%s", tag, val));
			}
			return rv;
		}
		return 0;
	}

	public static void main(String args[]) {

		KmlRegionBox app = new KmlRegionBox();

		List<String> sources = new ArrayList<String>();
		for (String arg : args) {
			if (arg.equals("-f"))
				app.followLinks = true;
			else if (arg.startsWith("-o"))
				app.outFile = arg.substring(2);
			else if (!arg.startsWith("-"))
				sources.add(arg);
			//System.out.println("Invalid argument: " + arg);
		}

		if (sources.size() == 0) {
			System.out.println("Must specify file and/or URL");
			//usage();
			return;
		}

		for (String arg : sources) {
			try {
				if (arg.startsWith("http:") || arg.startsWith("file:")) {
					URL url = new URL(arg);
					app.checkSource(url);
				} else {
					File f = new File(arg);
					if (f.exists()) {
						try {
							f = f.getCanonicalFile();
						} catch (IOException e) {
							// ignore
						}
						app.checkSource(f);
					} else
						app.checkSource(new URL(arg));
				}
			} catch (MalformedURLException e) {
				System.out.println(arg);
				System.out.println("\t*** " + e.getMessage());
				System.out.println();
			} catch (IOException e) {
				System.out.println(e);
			} catch (XMLStreamException e) {
				System.out.println(e);
			}
		}

		if (app.kos != null)
			try {
				app.kos.close();
			} catch (IOException e) {
				System.out.println("\t*** " + e.getMessage());
			}
	}

}
