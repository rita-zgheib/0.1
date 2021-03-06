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
package edu.ncsa.uiuc.rdfrepo.testing;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringTokenizer;

import org.apache.commons.dbcp.BasicDataSource;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.Var;
import org.openrdf.sail.Sail;
import org.openrdf.sail.SailException;
import org.openrdf.sail.nativerdf.NativeStore;

import com.useekm.indexing.exception.IndexException;
import com.useekm.indexing.postgis.PostgisIndexMatcher;
import com.useekm.reposail.RepositorySail;

import edu.ncsa.sstde.indexing.IndexingSail;
import edu.ncsa.sstde.indexing.postgis.PostgisIndexerSettings;

public class USeekMSailFac {

    public static IndexingSail getNativeIndexingSail(String dir, String dburl, String dbuser, String password, String capturepredicate) throws SailException {
        //create a basic sail from file system
        Sail sail = new NativeStore(new File(dir));

        return getIndexingSail(dburl, dbuser, password, capturepredicate, sail);
    }

    public static IndexingSail getIndexingSail(String dburl, String dbuser, String password, String capturepredicate, Sail sail) throws SailException {
        //set tup the postgis datasource for indexer
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(dburl);
        dataSource.setUsername(dbuser);
        dataSource.setPassword(password);
        PostgisIndexerSettings indexerSettings = new PostgisIndexerSettings();

        //        String patternString =
        //            "?observation <http://purl.oclc.org/NET/ssnx/ssn#observationResultTime> ?time." +
        //                "?time  <http://www.w3.org/2006/time#inXSDDateTime> ?timevalue. " +
        //                "?loc <http://www.opengis.net/rdf#hasWKT> ?coord. " +
        //                "?sensor <http://www.loa-cnr.it/ontologies/DUL.owl#hasLocation> ?loc." +
        //                "?observation <http://purl.oclc.org/NET/ssnx/ssn#observedBy> ?sensor. ";

        String patternString =
            "?geometry <http://www.opengis.net/rdf#hasWKT> ?wkt.";

        sail.initialize();
        indexerSettings.setMatchSatatments(createPatternFromString(patternString, sail));
        indexerSettings.setDataSource(dataSource);

        //a matcher decides which object should be indexed by its associated predicate, we can use "http://www.opengis.net/rdf#hasWKT"
        PostgisIndexMatcher matcher = new PostgisIndexMatcher();
        matcher.setPredicate(capturepredicate);

        //create a IndexingSail from the basic sail and the indexer
        indexerSettings.setMatchers(Arrays.asList(new PostgisIndexMatcher[] {matcher}));

        //        PartitionDef p = new P
        //        IndexingSail indexingSail = new IndexingSail(sail, indexerSettings);
        //        indexingSail.getConnection().

        //        indexingSail.initialize();
        return null;
    }

    private static Collection<String> tokenizePatternString(String patterString) {
        boolean escape = false;
        int startpoint = 0;
        Collection<String> strings = new ArrayList<String>();
        for (int i = 0; i < patterString.length(); i++) {
            if (patterString.charAt(i) == '<') {
                escape = true;
            } else if (patterString.charAt(i) == '>') {
                escape = false;
            } else if (patterString.charAt(i) == '.' && !escape) {
                strings.add(patterString.substring(startpoint, i));
                startpoint = i +1;
            }
        }
        String lastString = patterString.substring(startpoint);
        if (!lastString.trim().isEmpty()) {
            strings.add(lastString);
        }

        return strings;
    }

    private static Collection<StatementPattern> createPatternFromString(String patternString, Sail sail) {
        Collection<StatementPattern> patterns = new ArrayList<StatementPattern>();
        //        StringTokenizer tokenizer = new StringTokenizer(patternString, ".");
        Collection<String> statStrings = tokenizePatternString(patternString);
        for (String statString: statStrings) {
            //            String statString = tokenizer.nextToken();
            StringTokenizer tokenizer2 = new StringTokenizer(statString, " ");
            StatementPattern pattern = new StatementPattern();

            pattern.setSubjectVar(createVar(tokenizer2.nextToken(), sail));
            pattern.setPredicateVar(createVar(tokenizer2.nextToken(), sail));
            pattern.setObjectVar(createVar(tokenizer2.nextToken(), sail));

            patterns.add(pattern);
        }
        return patterns;

    }

    private static Var createVar(String varString, Sail sail) {
        Var var = new Var();
        if (varString.startsWith("?")) {
            var.setName(varString.substring(1));
        } else if (varString.startsWith("<") && varString.endsWith(">")) {
            ValueFactory factory = sail.getValueFactory();
            //            URI uri = new URIImpl("");
            var.setValue(sail.getValueFactory().createURI(varString.substring(1, varString.length() - 1)));
        } else {
            throw new IndexException("");
        }
        return var;
    }

    public static IndexingSail getIndexingSail(String dburl, String dbuser, String password, String capturepredicate, RepositorySail sail) throws SailException {
        //set tup the postgis datasource for indexer

        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(dburl);
        dataSource.setUsername(dbuser);
        dataSource.setPassword(password);
        PostgisIndexerSettings indexerSettings = new PostgisIndexerSettings();
        indexerSettings.setDataSource(dataSource);

        String patternString =
            "?observation <http://purl.oclc.org/NET/ssnx/ssn#observationResultTime> ?time." +
                "?time  <http://www.w3.org/2006/time#inXSDDateTime> ?timevalue. " +
                "?loc <http://www.opengis.net/rdf#hasWKT> ?coord. " +
                "?sensor <http://www.loa-cnr.it/ontologies/DUL.owl#hasLocation> ?loc." +
                "?observation <http://purl.oclc.org/NET/ssnx/ssn#observedBy> ?sensor. ";

        //        String patternString =
        //            "?geometry <http://www.opengis.net/rdf#hasWKT> ?wkt.";

        sail.initialize();
        indexerSettings.setMatchSatatments(createPatternFromString(patternString, sail));
        indexerSettings.setDataSource(dataSource);

        //a matcher decides which object should be indexed by its associated predicate, we can use "http://www.opengis.net/rdf#hasWKT"
        PostgisIndexMatcher matcher = new PostgisIndexMatcher();
        matcher.setPredicate(capturepredicate);

        //create a IndexingSail from the basic sail and the indexer
        indexerSettings.setMatchers(Arrays.asList(new PostgisIndexMatcher[] {matcher}));
        return null;

        //        PartitionDef p = new P
        //        IndexingSail indexingSail = new IndexingSail(sail, indexerSettings);
        //
        //        indexingSail.initialize();
        //        return indexingSail;
    }

}
