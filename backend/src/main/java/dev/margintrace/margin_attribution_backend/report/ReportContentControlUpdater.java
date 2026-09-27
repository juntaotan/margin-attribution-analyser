package dev.margintrace.margin_attribution_backend.report;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Component
public class ReportContentControlUpdater {

    private static final String WORD_NAMESPACE =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public UpdateResult replaceContentByAlias(
            byte[] documentBytes,
            String requestedAlias,
            String replacementValue) throws IOException {
        String alias = requestedAlias == null ? "" : requestedAlias.trim();
        if (alias.isBlank()) {
            throw new IllegalArgumentException("A content control Alias is required");
        }
        if (replacementValue == null || replacementValue.length() > 10_000) {
            throw new IllegalArgumentException("Enter a valid result value before applying");
        }

        int updatedControls = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(documentBytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream archive = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry copiedEntry = new ZipEntry(entry.getName());
                archive.putNextEntry(copiedEntry);
                if (!entry.isDirectory()) {
                    byte[] content = input.readAllBytes();
                    if (isWordContentPart(entry.getName())) {
                        PartUpdate updatedPart = updatePart(content, alias, replacementValue);
                        content = updatedPart.content();
                        updatedControls += updatedPart.updatedControls();
                    }
                    archive.write(content);
                }
                archive.closeEntry();
                input.closeEntry();
            }
            archive.finish();
            if (updatedControls == 0) {
                throw new IllegalArgumentException(
                        "No saved content control was found for Alias: " + alias);
            }
            return new UpdateResult(output.toByteArray(), updatedControls);
        }
    }

    private PartUpdate updatePart(byte[] xml, String alias, String replacementValue)
            throws IOException {
        Document document = parseXml(xml);
        NodeList controls = document.getElementsByTagNameNS(WORD_NAMESPACE, "sdt");
        int updatedControls = 0;

        for (int index = 0; index < controls.getLength(); index++) {
            Element control = (Element) controls.item(index);
            Element properties = directChild(control, "sdtPr");
            if (properties == null || !alias.equals(propertyValue(properties, "alias"))) {
                continue;
            }
            Element content = directChild(control, "sdtContent");
            if (content != null) {
                replaceContent(document, control, content, replacementValue);
                updatedControls++;
            }
        }

        return updatedControls == 0
                ? new PartUpdate(xml, 0)
                : new PartUpdate(writeXml(document), updatedControls);
    }

    private void replaceContent(
            Document document,
            Element control,
            Element content,
            String replacementValue) {
        while (content.hasChildNodes()) {
            content.removeChild(content.getFirstChild());
        }

        boolean inlineControl = control.getParentNode() instanceof Element parent
                && WORD_NAMESPACE.equals(parent.getNamespaceURI())
                && "p".equals(parent.getLocalName());
        Element run = document.createElementNS(WORD_NAMESPACE, "w:r");
        Element text = document.createElementNS(WORD_NAMESPACE, "w:t");
        text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
        text.setTextContent(replacementValue);
        run.appendChild(text);

        if (inlineControl) {
            content.appendChild(run);
        } else {
            Element paragraph = document.createElementNS(WORD_NAMESPACE, "w:p");
            paragraph.appendChild(run);
            content.appendChild(paragraph);
        }
    }

    private Document parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Unable to read Word content controls", exception);
        }
    }

    private byte[] writeXml(Document document) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (TransformerException exception) {
            throw new IOException("Unable to update Word content controls", exception);
        }
    }

    private Element directChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && WORD_NAMESPACE.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private String propertyValue(Element properties, String propertyName) {
        Element property = directChild(properties, propertyName);
        return property == null ? "" : property.getAttributeNS(WORD_NAMESPACE, "val").trim();
    }

    private boolean isWordContentPart(String entryName) {
        if ("word/document.xml".equals(entryName)
                || "word/footnotes.xml".equals(entryName)
                || "word/endnotes.xml".equals(entryName)) {
            return true;
        }
        return entryName.matches("word/(header|footer)\\d+\\.xml");
    }

    public record UpdateResult(byte[] documentBytes, int updatedControls) {
    }

    private record PartUpdate(byte[] content, int updatedControls) {
    }
}
