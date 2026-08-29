package com.guanxian.platform.ai.rag;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

@Component
public class KnowledgeDocumentParser {
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/csv");
    private static final String PARSER_NAME = "apache-tika";
    private static final String PARSER_VERSION = "3.2.3";

    private final RagProperties properties;

    public KnowledgeDocumentParser(RagProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    public ParsedDocument parse(String filename, String mediaType, byte[] content) {
        String normalizedMediaType = mediaType == null ? "" : mediaType.split(";", 2)[0]
                .trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MEDIA_TYPES.contains(normalizedMediaType)) {
            throw new DocumentParseException("knowledge file type is not supported");
        }
        if (content == null || content.length == 0 || content.length > properties.getMaxDocumentBytes()) {
            throw new DocumentParseException("knowledge file size is invalid");
        }
        Metadata metadata = new Metadata();
        metadata.set("Content-Type", normalizedMediaType);
        if (filename != null && !filename.isBlank()) metadata.set("resourceName", filename);
        ContentHandler handler = new BodyContentHandler(properties.getMaxDocumentChars());
        try (ByteArrayInputStream source = new ByteArrayInputStream(content)) {
            new AutoDetectParser().parse(source, handler, metadata, new ParseContext());
        } catch (Exception exception) {
            throw new DocumentParseException("knowledge file parsing failed or exceeded the text limit", exception);
        }
        String text = Normalizer.normalize(handler.toString(), Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.isBlank()) throw new DocumentParseException("knowledge file contains no extractable text");
        if (text.length() > properties.getMaxDocumentChars()) {
            throw new DocumentParseException("knowledge file text exceeds the configured limit");
        }
        return new ParsedDocument(text, pageCount(metadata), PARSER_NAME, PARSER_VERSION,
                normalizedMediaType);
    }

    private Integer pageCount(Metadata metadata) {
        for (String key : new String[]{"xmpTPg:NPages", "meta:page-count", "Page-Count"}) {
            String value = metadata.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                int count = Integer.parseInt(value.trim());
                if (count > 0) return count;
            } catch (NumberFormatException ignored) {
                // Parser metadata is untrusted; unknown page counts stay null.
            }
        }
        return null;
    }

    public record ParsedDocument(String text, Integer pageCount, String parserName,
                                 String parserVersion, String mediaType) {}

    public static class DocumentParseException extends IllegalArgumentException {
        public DocumentParseException(String message) { super(message); }
        public DocumentParseException(String message, Throwable cause) { super(message, cause); }
    }
}
