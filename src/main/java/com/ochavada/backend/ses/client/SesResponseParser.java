package com.ochavada.backend.ses.client;

import com.ochavada.backend.ses.client.SesResponse.BatchResult;
import com.ochavada.backend.ses.client.SesResponse.CommunicationResult;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Interpreta las respuestas SOAP del servicio por nombre local de los elementos (sin depender de los
 * prefijos). Tolera las variaciones presentes en la propia especificación: {@code codigoRetorno} y
 * {@code codigo}, {@code resultadoComunicacion} y {@code resutadoComunicacion}.
 * <p>
 * El parser está endurecido frente a XXE: sin DTD, sin entidades externas.
 */
@Component
public class SesResponseParser {

    private static final Set<String> CODE_NAMES = Set.of("codigoRetorno", "codigo");
    private static final Set<String> COMMUNICATION_RESULT_NAMES = Set.of("resultadoComunicacion", "resutadoComunicacion");

    public SesResponse parse(String soap) {
        Document doc = secureParse(soap);
        Element fault = first(doc.getDocumentElement(), "Fault");
        if (fault != null) {
            throw new SesTransportException("SOAP Fault del servicio: " + text(fault, "faultstring"), null);
        }
        Element response = first(doc.getDocumentElement(), "respuesta");
        if (response == null) {
            throw new SesTransportException("Respuesta del servicio sin bloque 'respuesta'", null);
        }
        String codeText = null;
        for (String name : CODE_NAMES) {
            codeText = childText(response, name);
            if (codeText != null) {
                break;
            }
        }
        int code;
        try {
            code = codeText == null ? -1 : Integer.parseInt(codeText.trim());
        } catch (NumberFormatException ex) {
            code = -1;
        }
        List<BatchResult> batches = new ArrayList<>();
        NodeList results = doc.getElementsByTagNameNS("*", "resultado");
        for (int i = 0; i < results.getLength(); i++) {
            batches.add(batch((Element) results.item(i)));
        }
        return new SesResponse(code, childText(response, "descripcion"), childText(response, "lote"), batches, soap);
    }

    private BatchResult batch(Element result) {
        List<CommunicationResult> communications = new ArrayList<>();
        Element block = firstChild(result, "resultadoComunicaciones");
        if (block != null) {
            NodeList children = block.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element e && COMMUNICATION_RESULT_NAMES.contains(e.getLocalName())) {
                    communications.add(new CommunicationResult(
                            parseInt(childText(e, "orden"), i + 1),
                            childText(e, "codigoComunicacion"),
                            childText(e, "tipoError"),
                            childText(e, "error"),
                            "true".equalsIgnoreCase(childText(e, "anulada"))));
                }
            }
        }
        return new BatchResult(childText(result, "lote"), parseInt(childText(result, "codigoEstado"), -1),
                childText(result, "descEstado"), communications);
    }

    private static Document secureParse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception ex) {
            throw new SesTransportException("Respuesta del servicio no interpretable", ex);
        }
    }

    /** Primer descendiente con ese nombre local. */
    private static Element first(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    /** Hijo directo con ese nombre local. */
    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }

    private static String text(Element root, String localName) {
        Element e = first(root, localName);
        return e == null ? null : e.getTextContent().trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
