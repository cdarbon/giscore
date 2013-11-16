package org.opensextant.giscore.test.utils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.junit.Before;
import org.junit.Test;

import org.opensextant.giscore.events.IGISObject;
import org.opensextant.giscore.input.kml.KmlReader;
import org.opensextant.giscore.utils.KmlRegionBox;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

/**
 * @author Jason Mathews, MITRE Corp.
 * Date: Oct 18, 2010 1:49:01 PM
 */
public class TestKmlRegionBox {

	private final File outDir = new File("testOutput");

	@Before
	public void initialize() {
		if (!outDir.exists()) outDir.mkdir();
	}

	@Test
	public void testMain() throws IOException {
        // test remote NetworkLink with Region definition
        String url = "http://kml-samples.googlecode.com/svn/trunk/kml/Region/d/1030.kml";
		File file = new File(outDir, "bbox1.kml");
        KmlRegionBox.main(new String[]{
			url, "-o" + file.toString(), "-f"
		});
		assertTrue(file.isFile());
		KmlReader reader = null;
		try {
			assertTrue(file.length() > 0);
			reader = new KmlReader(file);
			List<IGISObject> features = reader.readAll();
			assertEquals(features.size(), 5);
			// features = DocumentStart + ContainerStart  + 2 placemarks + ContainerEnd
		} finally {
			if (file.exists() && !file.delete()) file.deleteOnExit();
			if (reader != null) reader.close();
		}
	}

	@Test
	public void testRegions() throws XMLStreamException, IOException {
		File dir = new File("data/kml/Region");
		if (dir.isDirectory()) {
			KmlRegionBox app = new KmlRegionBox();
			File file = new File(outDir, "bbox2.kml");
            app.setOutFile(file);
            try {
			    app.checkSource(dir);
			    assertFalse(app.getRegions().isEmpty());
            } finally {
				if (file.exists() && !file.delete()) file.deleteOnExit();
                app.close();
            }
		}
	}

}
