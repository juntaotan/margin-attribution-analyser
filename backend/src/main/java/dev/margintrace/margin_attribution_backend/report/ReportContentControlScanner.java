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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ReportContentControlScanner {

    private static final String WORD_NAMESPACE =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int PREVIEW_LIMIT = 120;

    public List<ContentControlDescriptor> scan(byte[] documentBytes) throws IOException {
        Map<String, MutableContentControl> controls = new LinkedHashMap<>();
        int unnamedControlIndex = 0;

        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(documentBytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!entry.isDirectory() && isWordContentPart(entry.getName())) {
                    byte[] xml = readCurrentEntry(archive);
                    unnamedControlIndex = scanPart(xml, controls, unnamedControlIndex);
                }
                archive.closeEntry();
            }
        }

        return controls.values().stream()
                .map(MutableContentControl::toDescriptor)
                .toList();
    }

    public List<String> contextsForTag(byte[] documentBytes, String requestedTag) throws IOException {
        return contextsForControl(documentBytes, requestedTag, null, null);
    }

    public List<String> contextsForControl(
            byte[] documentBytes,
            String requestedTag,
            String requestedWordId,
            String requestedAlias) throws IOException {
        String tag = normalizedSelector(requestedTag);
        String wordId = normalizedSelector(requestedWordId);
        String alias = normalizedSelector(requestedAlias);
        if (tag.isBlank() && wordId.isBlank() && alias.isBlank()) {
            return List.of();
        }

        Set<String> contexts = new LinkedHashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(documentBytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!entry.isDirectory() && isWordContentPart(entry.getName())) {
                    Document document = parseXml(readCurrentEntry(archive));
                    NodeList controls = document.getElementsByTagNameNS(WORD_NAMESPACE, "sdt");
                    for (int index = 0; index < controls.getLength(); index++) {
                        Element control = (Element) controls.item(index);
                        Element properties = directChild(control, "sdtPr");
                        if (properties != null && matchesControl(properties, tag, wordId, alias)) {
                            String context = extractMarkedSentence(control);
                            if (!context.isBlank()) {
                                contexts.add(context);
                            }
                        }
                    }
                }
                archive.closeEntry();
            }
        }
        return List.copyOf(contexts);
    }

    private boolean matchesControl(Element properties, String tag, String wordId, String alias) {
        if (!tag.isBlank()) {
            return tag.equals(propertyValue(properties, "tag"));
        }
        if (!wordId.isBlank()) {
            return wordId.equals(propertyValue(properties, "id"));
        }
        return alias.equals(propertyValue(properties, "alias"));
    }

    private String normalizedSelector(String value) {
        return value == null ? "" : value.trim();
    }

    private int scanPart(
            byte[] xml,
            Map<String, MutableContentControl> controls,
            int unnamedControlIndex) throws IOException {
        Document document = parseXml(xml);
        NodeList structuredDocumentTags = document.getElementsByTagNameNS(WORD_NAMESPACE, "sdt");

        for (int index = 0; index < structuredDocumentTags.getLength(); index++) {
            Element control = (Element) structuredDocumentTags.item(index);
            Element properties = directChild(control, "sdtPr");
            if (properties == null) {
                continue;
            }

            String tag = propertyValue(properties, "tag");
            String wordId = propertyValue(properties, "id");
            String alias = propertyValue(properties, "alias");
            String key;

            if (!tag.isBlank()) {
                key = tag;
            } else if (!wordId.isBlank()) {
                key = "content-control:" + wordId;
            } else {
                key = "content-control:unnamed-" + (++unnamedControlIndex);
            }

            String displayName = !alias.isBlank()
                    ? alias
                    : (!tag.isBlank() ? tag : "Untitled content control");
            String preview = extractPreview(control);

            MutableContentControl existing = controls.get(key);
            if (existing == null) {
                controls.put(key, new MutableContentControl(key, tag, wordId, displayName, preview));
            } else {
                existing.occurrences++;
                if (existing.preview.isBlank() && !preview.isBlank()) {
                    existing.preview = preview;
                }
            }
        }

        return unnamedControlIndex;
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

    private boolean isWordContentPart(String entryName) {
        if ("word/document.xml".equals(entryName)
                || "word/footnotes.xml".equals(entryName)
                || "word/endnotes.xml".equals(entryName)) {
            return true;
        }
        return entryName.matches("word/(header|footer)\\d+\\.xml");
    }

    private byte[] readCurrentEntry(ZipInputStream archive) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        archive.transferTo(output);
        return output.toByteArray();
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

    private String extractPreview(Element control) {
        Element content = directChild(control, "sdtContent");
        if (content == null) {
            return "";
        }

        NodeList textNodes = content.getElementsByTagNameNS(WORD_NAMESPACE, "t");
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < textNodes.getLength(); index++) {
            String text = textNodes.item(index).getTextContent().trim();
            if (!text.isBlank()) {
                parts.add(text);
            }
        }

        String preview = String.join(" ", parts);
        return preview.length() <= PREVIEW_LIMIT
                ? preview
                : preview.substring(0, PREVIEW_LIMIT - 1) + "…";
    }

    private String extractMarkedSentence(Element control) {
        Element paragraph = ancestor(control, "p");
        if (paragraph == null) {
            return targetMarker(control);
        }

        StringBuilder markedText = new StringBuilder();
        appendMarkedText(paragraph, control, markedText);
        String fullText = normalizeWhitespace(markedText.toString());
        int controlStart = fullText.indexOf("[[TARGET ");
        if (controlStart < 0) {
            return fullText;
        }
        int markerEnd = fullText.indexOf("]]", controlStart);
        int controlEnd = markerEnd < 0 ? controlStart : markerEnd + 2;
        int sentenceStart = 0;
        for (int index = controlStart - 1; index >= 0; index--) {
            if (isSentenceBoundary(fullText, index)) {
                sentenceStart = index + 1;
                break;
            }
        }

        int sentenceEnd = fullText.length();
        for (int index = controlEnd; index < fullText.length(); index++) {
            if (isSentenceBoundary(fullText, index)) {
                sentenceEnd = index + 1;
                break;
            }
        }
        return fullText.substring(sentenceStart, sentenceEnd).trim();
    }

    private void appendMarkedText(Node node, Element targetControl, StringBuilder output) {
        if (node instanceof Element element
                && WORD_NAMESPACE.equals(element.getNamespaceURI())
                && "sdt".equals(element.getLocalName())) {
            output.append(element == targetControl ? targetMarker(element) : controlMarker(element));
            return;
        }
        if (node instanceof Element element
                && WORD_NAMESPACE.equals(element.getNamespaceURI())
                && "t".equals(element.getLocalName())) {
            output.append(element.getTextContent());
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            appendMarkedText(child, targetControl, output);
        }
    }

    private String targetMarker(Element control) {
        return contentControlMarker("TARGET", control);
    }

    private String controlMarker(Element control) {
        return contentControlMarker("CONTROL", control);
    }

    private String contentControlMarker(String markerType, Element control) {
        Element properties = directChild(control, "sdtPr");
        String tag = properties == null ? "" : propertyValue(properties, "tag");
        String alias = properties == null ? "" : propertyValue(properties, "alias");
        String value = normalizeWhitespace(extractText(control));
        return " [[" + markerType
                + " tag=\"" + markerValue(tag)
                + "\" alias=\"" + markerValue(alias)
                + "\" value=\"" + markerValue(value)
                + "\"]] ";
    }

    private String markerValue(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
    }

    private Element ancestor(Element element, String localName) {
        for (Node parent = element.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (parent instanceof Element parentElement
                    && WORD_NAMESPACE.equals(parentElement.getNamespaceURI())
                    && localName.equals(parentElement.getLocalName())) {
                return parentElement;
            }
        }
        return null;
    }

    private String extractText(Element element) {
        NodeList textNodes = element.getElementsByTagNameNS(WORD_NAMESPACE, "t");
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < textNodes.getLength(); index++) {
            text.append(textNodes.item(index).getTextContent());
        }
        return text.toString();
    }

    private String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean isSentenceBoundary(String text, int index) {
        char character = text.charAt(index);
        if (character == '。' || character == '！' || character == '？') {
            return true;
        }
        if (character != '.' && character != '!' && character != '?') {
            return false;
        }
        if (character == '.'
                && index > 0
                && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1))) {
            return false;
        }
        return index + 1 == text.length() || Character.isWhitespace(text.charAt(index + 1));
    }

    public record ContentControlDescriptor(
            String id,
            String tag,
            String wordId,
            String alias,
            String preview,
            int occurrences,
            boolean tagged) {
    }

    private static final class MutableContentControl {
        private final String id;
        private final String tag;
        private final String wordId;
        private final String alias;
        private String preview;
        private int occurrences = 1;

        private MutableContentControl(String id, String tag, String wordId, String alias, String preview) {
            this.id = id;
            this.tag = tag;
            this.wordId = wordId;
            this.alias = alias;
            this.preview = preview;
        }

        private ContentControlDescriptor toDescriptor() {
            return new ContentControlDescriptor(
                    id,
                    tag,
                    wordId,
                    alias,
                    preview,
                    occurrences,
                    !tag.isBlank());
        }
    }
}
