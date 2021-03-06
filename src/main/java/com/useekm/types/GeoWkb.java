/*
 * Copyright 2011 by TalkingTrends (Amsterdam, The Netherlands)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://opensahara.com/licenses/apache-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.useekm.types;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.Validate;
import org.openrdf.model.URI;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKBWriter;

import edu.ncsa.sstde.indexing.GeoConstants;

public class GeoWkb extends AbstractGeo {
    public GeoWkb(Geometry geometry) {
        super(new String(Base64.encodeBase64(new WKBWriter().write(geometry))));
        Validate.notNull(geometry);
    }

    public GeoWkb(String value) {
        super(value);
    }

    @Override public URI getType() {
        return GeoConstants.XMLSCHEMA_SPATIAL_BIN;
    }

    @Override public Geometry getGeo() {
        return binaryToGeometry(Base64.decodeBase64(getValue().getBytes()));
    }
}
