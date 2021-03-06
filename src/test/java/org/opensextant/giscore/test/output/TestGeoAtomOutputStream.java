/****************************************************************************************
 *  TestGeoAtomOutputStream.java
 *
 *  Created: Jul 19, 2010
 *
 *  @author DRAND
 *
 *  (C) Copyright MITRE Corporation 2010
 *
 *  The program is provided "as is" without any warranty express or implied, including
 *  the warranty of non-infringement and the implied warranties of merchantibility and
 *  fitness for a particular purpose.  The Copyright owner will not be liable for any
 *  damages suffered by you as a result of using the Program.  In no event will the
 *  Copyright owner be liable for any special, indirect or consequential damages or
 *  lost profits even if the Copyright owner has been advised of the possibility of
 *  their occurrence.
 *
 ***************************************************************************************/
package org.opensextant.giscore.test.output;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.math.RandomUtils;
import org.junit.Test;
import org.opensextant.giscore.DocumentType;
import org.opensextant.giscore.GISFactory;
import org.opensextant.giscore.Namespace;
import org.opensextant.giscore.events.AtomAuthor;
import org.opensextant.giscore.events.AtomHeader;
import org.opensextant.giscore.events.AtomLink;
import org.opensextant.giscore.events.DocumentStart;
import org.opensextant.giscore.events.Element;
import org.opensextant.giscore.events.Feature;
import org.opensextant.giscore.events.IGISObject;
import org.opensextant.giscore.events.Row;
import org.opensextant.giscore.events.SimpleField;
import org.opensextant.giscore.geometry.Circle;
import org.opensextant.giscore.geometry.Line;
import org.opensextant.giscore.geometry.LinearRing;
import org.opensextant.giscore.geometry.Point;
import org.opensextant.giscore.input.IGISInputStream;
import org.opensextant.giscore.output.IGISOutputStream;
import org.opensextant.giscore.output.atom.IAtomConstants;
import org.opensextant.giscore.test.input.TestGeoAtomStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestGeoAtomOutputStream {
	private static final String OPENSEARCH = "opensearch";
	private static final SimpleField X = new SimpleField("X",
			SimpleField.Type.DOUBLE);
	private static final SimpleField Y = new SimpleField("Y",
			SimpleField.Type.DOUBLE);

	@Test
	public void testBasicOutput() throws Exception {
		File temp = File.createTempFile("test", ".xml");
		FileOutputStream os = new FileOutputStream(temp);
		IGISOutputStream gisos = GISFactory.getOutputStream(
				DocumentType.GeoAtom, os);
		AtomHeader header = new AtomHeader("http://www.fake.mitre.org/12412412412512123123",
				new AtomLink(new URL(
						"http://www.fake.mitre.org/atomfakefeed/id=xyzzy/123"),
						"self"), "dummy title", new Date());
		header.getAuthors().add(new AtomAuthor("Joe Shmoe","joe@mitre.org"));
		header.getRelatedlinks().add(new AtomLink(new URL("http://www.yahoo.com"), "related"));

        Namespace ns = Namespace.getNamespace(OPENSEARCH, "http://a9.com/-/spec/opensearch/1.1/");
        header.getNamespaces().add(ns);
		Element results = new Element(ns, "totalResults");
		results.setText("1000");
		Element startIndex = new Element(ns, "startIndex");
		startIndex.setText("1");
		
		header.getElements().add(results);
		header.getElements().add(startIndex);
		gisos.write(header);
		
		List<IGISObject> written = new ArrayList<IGISObject>();
		for (int i = 0; i < 25; i++) {
			IGISObject ob = randomFeature();
			gisos.write(ob);
			written.add(ob);
		}
		for (int i = 0; i < 10; i++) {
			IGISObject ob = randomRow();
			gisos.write(ob);
			written.add(ob);
		}

		gisos.close();
		
		FileInputStream is = new FileInputStream(temp);
		IGISInputStream gisis = GISFactory.getInputStream(DocumentType.GeoAtom, is);
		IGISObject first = gisis.read();
		assertNotNull(first);
		assertTrue(first instanceof AtomHeader);
		AtomHeader readheader = (AtomHeader) first;
		assertEquals(header, readheader);
		
		List<IGISObject> read = new ArrayList<IGISObject>();
		while(true) {
			IGISObject ob = gisis.read();
			if (ob == null) break;
			if (ob instanceof DocumentStart) continue;
			read.add(ob);
		}
		assertEquals(written.size(), read.size());
		
		for(int i = 0; i < written.size(); i++) {
			System.err.println("Compare #" + i);
			compare(written.get(i), read.get(i));
		}
	}

	@Test
	public void testForeignElements() throws Exception {
		File file = new File("data/atom/techalerts.xml");
		IGISInputStream gis = GISFactory.getInputStream(DocumentType.GeoAtom, file);
		final ByteArrayOutputStream bos = new ByteArrayOutputStream((int) (file.length() + 1000));
		final IGISOutputStream gos = GISFactory.getOutputStream(DocumentType.GeoAtom, bos);
		for(IGISObject obj = gis.read(); obj != null; obj = gis.read()) {
			gos.write(obj);
		}
		gis.close();
		gos.close();
		gis = GISFactory.getInputStream(DocumentType.GeoAtom, new ByteArrayInputStream(bos.toByteArray()));
		TestGeoAtomStream.checkTechAlerts(gis);
	}

	private void compare(IGISObject ob, IGISObject ob2) {
		if (ob instanceof Feature) {
			compareFeatures(ob, ob2);
		} else {
			compareRows((Row) ob, (Row) ob2);
		}
	}

	private void compareRows(Row r1, Row r2) {
		assertEquals(r1.getId(), r2.getId());
		// Compare data by named fields
		Map<String,SimpleField> r1fieldmap = new HashMap<String, SimpleField>();
		Map<String,SimpleField> r2fieldmap = new HashMap<String, SimpleField>();
		
		for(SimpleField f : r1.getFields()) {
			r1fieldmap.put(f.getName(), f);
		}
		for(SimpleField f : r2.getFields()) {
			r2fieldmap.put(f.getName(), f);
		}
		assertEquals(r1fieldmap.keySet(), r2fieldmap.keySet());
		// Compare data
		for(String name : r1fieldmap.keySet()) {
			SimpleField r1field = r1fieldmap.get(name);
			SimpleField r2field = r2fieldmap.get(name);
			Object data1 = r1.getData(r1field);
			Object data2 = r2.getData(r2field);
			if (data1 instanceof Double) {
				assertEquals((Double) data1, (Double) data2, 0.0001);
			} else {
				assertEquals(data1, data2);
			}
		}
	}

	private void compareFeatures(IGISObject ob, IGISObject ob2) {
		if (ob instanceof Feature && ob2 instanceof Feature) {
			// 
		} else {
			fail("Not both features");
		}
		Feature f1 = (Feature) ob;
		Feature f2 = (Feature) ob2;
		assertEquals(f1.getName(), f2.getName());
		assertEquals(f1.getDescription(), f2.getDescription());
		assertEquals(f1.getStartTime(), f2.getStartTime());
		// Hard to compare the geometry objects due to comparing double values
		// just compare type and counts and call it a day
		assertNotNull(f1.getGeometry());
		assertNotNull(f2.getGeometry());
		assertEquals(f1.getGeometry().getClass(), f2.getGeometry().getClass());
		assertEquals(f1.getGeometry().getNumPoints(), f2.getGeometry().getNumPoints());
		compareRows((Row) ob, (Row) ob2);
	}

	private IGISObject randomFeature() {
		Feature rval = new Feature();
		rval.setStartTime(new Date());
		rval.setName("Random Name " + RandomUtils.nextInt(100));
		fillData((Row) rval);
		int i = RandomUtils.nextInt(4);
		double centerlat = 40.0 + RandomUtils.nextDouble() * 2.0;
		double centerlon = 40.0 + RandomUtils.nextDouble() * 2.0;
		Point p1 = new Point(centerlat, centerlon);
		switch (i) {
		case 0:
			rval.setGeometry(p1);
			break;
		case 1: {
			List<Point> pts = new ArrayList<Point>();
			pts.add(p1);
			double dx = RandomUtils.nextDouble() * 4.0;
			double dy = RandomUtils.nextDouble() * 4.0;
			Point p2 = new Point(centerlat + dy, centerlon + dx);
			pts.add(p2);
			rval.setGeometry(new Line(pts));
			break;
		}
		case 2:
			rval.setGeometry(new Circle(p1.asGeodetic2DPoint(), RandomUtils.nextDouble() * 3000.0));
			break;
		default: {
			List<Point> pts = new ArrayList<Point>();
			pts.add(p1);
			pts.add(new Point(centerlat + 0.0, centerlon + 0.5));
			pts.add(new Point(centerlat + 0.5, centerlon + 0.5));
			pts.add(new Point(centerlat + 0.8, centerlon + 0.3));
			pts.add(new Point(centerlat + 0.5, centerlon + 0.0));
			pts.add(new Point(centerlat + 0.0, centerlon + 0.0));
			rval.setGeometry(new LinearRing(pts));
		}
		}
		rval.setDescription("Random desc " + RandomUtils.nextInt());
		return rval;
	}

	private void fillData(Row rval) {
		rval.setId("urn:mitre:test:" + System.nanoTime());
		rval.putData(IAtomConstants.LINK_ATTR,
				"http://asite.mitre.org/myservice/fetch=" + rval.getId());
		if (!(rval instanceof Feature)) {
			rval.putData(IAtomConstants.UPDATED_ATTR, new Date());
			rval.putData(IAtomConstants.TITLE_ATTR,
					"Random Title " + RandomUtils.nextInt(100));
		}
		rval.putData(X, RandomUtils.nextDouble());
		rval.putData(Y, RandomUtils.nextDouble());
		rval.putData(IAtomConstants.AUTHOR_ATTR,
				"author a" + RandomUtils.nextInt(5));
	}

	private IGISObject randomRow() {
		Row rval = new Row();
		fillData(rval);
		rval.putData(IAtomConstants.CONTENT_ATTR, "Content " + RandomUtils.nextInt());
		return rval;
	}

}
