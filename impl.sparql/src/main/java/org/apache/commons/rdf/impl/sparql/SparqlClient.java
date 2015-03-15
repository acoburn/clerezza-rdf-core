/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.commons.rdf.impl.sparql;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import javax.xml.parsers.*;
import org.apache.commons.rdf.BlankNode;
import org.apache.commons.rdf.BlankNodeOrIri;
import org.apache.commons.rdf.Iri;
import org.apache.commons.rdf.Language;
import org.apache.commons.rdf.Literal;
import org.apache.commons.rdf.RdfTerm;
import org.apache.commons.rdf.Triple;
import org.apache.commons.rdf.impl.utils.AbstractLiteral;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

/**
 *
 * @author developer
 */
public class SparqlClient {

    final String endpoint;

    public SparqlClient(final String endpoint) {
        this.endpoint = endpoint;
    }

    List<Map<String, RdfTerm>> queryResultSet(final String query) throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(endpoint);
        List<NameValuePair> nvps = new ArrayList<NameValuePair>();
        nvps.add(new BasicNameValuePair("query", query));
        httpPost.setEntity(new UrlEncodedFormEntity(nvps));
        CloseableHttpResponse response2 = httpclient.execute(httpPost);

        try {
            HttpEntity entity2 = response2.getEntity();
            InputStream in = entity2.getContent();
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            SAXParser saxParser = spf.newSAXParser();
            XMLReader xmlReader = saxParser.getXMLReader();
            final SparqlsResultsHandler sparqlsResultsHandler = new SparqlsResultsHandler();
            xmlReader.setContentHandler(sparqlsResultsHandler);
            xmlReader.parse(new InputSource(in));
            /*
             for (int ch = in.read(); ch != -1; ch = in.read()) {
             System.out.print((char)ch);
             }
             */
            // do something useful with the response body
            // and ensure it is fully consumed
            EntityUtils.consume(entity2);
            return sparqlsResultsHandler.getResults();
        } catch (ParserConfigurationException ex) {
            throw new RuntimeException(ex);
        } catch (SAXException ex) {
            throw new RuntimeException(ex);
        } finally {
            response2.close();
        }

    }

    final public static class SparqlsResultsHandler extends DefaultHandler {

        private String currentBindingName;
        private Map<String, RdfTerm> currentResult = null;
        private final List<Map<String, RdfTerm>> results = new ArrayList<>();
        private boolean readingValue;
        private String lang; //the xml:lang attribute of a literal
        private String value;
        private Map<String, BlankNode> bNodeMap = new HashMap<>();
        private static final Iri XSD_STRING = new Iri("http://www.w3.org/2001/XMLSchema#string");

        private RdfTerm getBNode(String value) {
            if (!bNodeMap.containsKey(value)) {
                bNodeMap.put(value, new BlankNode());
            }
            return bNodeMap.get(value);
        }

        private List<Map<String, RdfTerm>> getResults() {
            return results;
        }

        enum BindingType {

            uri, bnode, literal;
        }

        @Override
        public void startDocument() throws SAXException {

        }

        @Override
        public void startElement(String namespaceURI,
                String localName,
                String qName,
                Attributes atts)
                throws SAXException {
            if ("http://www.w3.org/2005/sparql-results#".equals(namespaceURI)) {
                if ("result".equals(localName)) {
                    if (currentResult != null) {
                        throw new SAXException("unexpected tag <result>");
                    }
                    currentResult = new HashMap<>();
                } else if ("binding".equals(localName)) {
                    if (currentResult == null) {
                        throw new SAXException("unexpected tag <binding>");
                    }
                    currentBindingName = atts.getValue("name");
                } else if ("uri".equals(localName) || "bnode".equals(localName) || "literal".equals(localName)) {
                    if (readingValue) {
                        throw new SAXException("unexpected tag <" + localName + ">");
                    }
                    lang = atts.getValue("http://www.w3.org/XML/1998/namespace", "lang");
                    readingValue = true;
                }
            }

            //System.out.println(namespaceURI);
            //System.out.println(qName);
        }

        @Override
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (readingValue) {
                value = new String(chars, start, length);
                //System.err.println(value + start + ", " + length);
            }
        }

        @Override
        public void endElement(String namespaceURI,
                String localName,
                String qName)
                throws SAXException {
            if ("http://www.w3.org/2005/sparql-results#".equals(namespaceURI)) {
                if ("result".equals(localName)) {
                    results.add(currentResult);
                    currentResult = null;
                } else if ("binding".equals(localName)) {
                    if (currentBindingName == null) {
                        throw new SAXException("unexpected tag </binding>");
                    }
                    currentBindingName = null;
                } else {
                    try {
                        BindingType b = BindingType.valueOf(localName);
                        RdfTerm rdfTerm = null;
                        final Language language = lang == null? null : new Language(lang);;
                        switch (b) {
                            case uri:
                                rdfTerm = new Iri(value);
                                break;
                            case bnode:
                                rdfTerm = getBNode(value);
                                break;
                            case literal:
                                final String lf = value;
                                rdfTerm = new AbstractLiteral() {

                                    @Override
                                    public String getLexicalForm() {
                                        return lf;
                                    }

                                    @Override
                                    public Iri getDataType() {
                                        //TODO implement
                                        return XSD_STRING;
                                    }

                                    @Override
                                    public Language getLanguage() {
                                        return language;
                                    }
                                };
                                break;
                        }
                        currentResult.put(currentBindingName, rdfTerm);
                        readingValue = false;
                    } catch (IllegalArgumentException e) {
                            //not uri|bnode|literal
                    }
                }
            }
        }

        public void endDocument() throws SAXException {
            //System.out.println("results: " + results.size());
        }

    }

}
