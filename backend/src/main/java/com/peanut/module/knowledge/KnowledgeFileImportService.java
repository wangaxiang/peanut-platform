package com.peanut.module.knowledge;

import static com.peanut.module.knowledge.KnowledgeModels.ChunkRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class KnowledgeFileImportService {
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 40 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_CHUNKS = 100;
    private static final int CHUNK_LENGTH = 700;
    private static final int MAX_TEXT_LENGTH = CHUNK_LENGTH * MAX_CHUNKS;
    private static final String WORD_XML = "word/document.xml";
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private final KnowledgeService knowledge;
    private final ObjectMapper json;

    KnowledgeFileImportService(KnowledgeService knowledge, ObjectMapper json) {
        this.knowledge = knowledge;
        this.json = json;
    }

    ImportSummary importFile(MultipartFile upload) {
        String name = baseName(upload.getOriginalFilename());
        List<FileResult> results = new ArrayList<>();
        ImportBudget budget = new ImportBudget();
        try (InputStream input = upload.getInputStream()) {
            if ("zip".equals(extension(name))) {
                readArchive(input, name, budget, results);
            } else {
                results.add(importDocument(name, null, readBytes(input, MAX_FILE_BYTES), budget));
            }
        } catch (IOException | IllegalArgumentException error) {
            results.add(new FileResult(name, 0, "ZIP 或文件读取失败，文件可能损坏或超过大小限制。"));
        }
        int written = results.stream().mapToInt(FileResult::imported).sum();
        int failures = (int) results.stream().filter(result -> result.message() != null).count();
        return new ImportSummary(written, failures, results);
    }

    private void readArchive(InputStream input, String archive, ImportBudget budget, List<FileResult> results)
            throws IOException {
        int entries = 0;
        int remainingBytes = MAX_EXPANDED_BYTES;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > MAX_ENTRIES) {
                    results.add(new FileResult(archive, 0, "ZIP 最多包含 100 个条目，其余条目未导入。"));
                    return;
                }
                byte[] bytes = readBytes(zip, Math.min(MAX_FILE_BYTES, remainingBytes));
                remainingBytes -= bytes.length;
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.startsWith("__MACOSX/") || ".DS_Store".equals(baseName(name))) {
                    continue;
                }
                results.add(importDocument(name, archive, bytes, budget));
                if (budget.remaining == 0) {
                    if (zip.getNextEntry() != null) {
                        results.add(new FileResult(archive, 0, "已达到 100 个知识片段上限，后续条目未导入。"));
                    }
                    return;
                }
            }
        }
        if (results.isEmpty()) {
            results.add(new FileResult(archive, 0, "ZIP 中没有可导入的文件。"));
        }
    }

    private FileResult importDocument(String entry, String archive, byte[] bytes, ImportBudget budget) {
        String name = baseName(entry);
        String label = archive == null ? name : archive + " / " + entry;
        String format = extension(name);
        if (budget.remaining == 0) {
            return new FileResult(label, 0, "已达到 100 个知识片段上限。");
        }
        if ("csv".equals(format)) {
            return importCsv(label, bytes, budget);
        }
        int written = 0;
        try {
            String raw = switch (format) {
                case "pdf" -> readPdf(bytes);
                case "docx" -> readWord(bytes);
                case "txt", "md" -> StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                default -> throw new IllegalArgumentException("支持 PDF、DOCX、TXT、MD 和 CSV；ZIP 内不支持再嵌套压缩包。");
            };
            String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\ufeff", "").strip();
            if (text.isEmpty()) {
                return new FileResult(label, 0, "pdf".equals(format)
                        ? "PDF 没有可提取文字，请先对扫描件做 OCR。" : "文件里没有可导入的正文。");
            }
            List<String> parts = splitText(text);
            if (parts.size() > budget.remaining) {
                return new FileResult(label, 0, "本次最多写入 100 个知识片段，请拆分资料后重试。");
            }
            budget.remaining -= parts.size();
            for (int index = 0; index < parts.size(); index++) {
                var metadata = json.createObjectNode().put("sourceFile", name).put("format", format)
                        .put("part", index + 1).put("parts", parts.size());
                if (archive != null) {
                    metadata.put("archive", archive).put("archiveEntry", entry);
                }
                String suffix = parts.size() > 1 ? "（第 " + (index + 1) + " 段）" : "";
                String title = name.substring(0, Math.min(name.length(), 200 - suffix.length()));
                knowledge.importChunk(new ChunkRequest(title + suffix, parts.get(index), metadata));
                written++;
            }
            return new FileResult(label, written, null);
        } catch (InvalidPasswordException error) {
            return new FileResult(label, written, "PDF 需要密码，请上传可读取的版本。");
        } catch (ResponseStatusException error) {
            return new FileResult(label, written, error.getReason());
        } catch (IllegalArgumentException error) {
            return new FileResult(label, written, error.getMessage());
        } catch (IOException | XMLStreamException error) {
            return new FileResult(label, written, "无法提取正文。请确认文件完整，TXT 和 MD 使用 UTF-8 编码。");
        }
    }

    private FileResult importCsv(String label, byte[] bytes, ImportBudget budget) {
        try {
            var result = knowledge.csv(new ByteArrayInputStream(bytes), budget.remaining);
            budget.remaining -= result.successCount() + result.failureCount();
            String message = null;
            if (result.failureCount() > 0) {
                var first = result.failures().getFirst();
                message = "有 " + result.failureCount() + " 行导入失败，首个错误在第 " + first.row() + " 行：" + first.message();
            } else if (result.successCount() == 0) {
                message = "CSV 没有数据行。";
            }
            return new FileResult(label, result.successCount(), message);
        } catch (ResponseStatusException error) {
            return new FileResult(label, 0, error.getReason());
        }
    }

    private static String readPdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IllegalArgumentException("PDF 禁止提取文字，请上传允许复制正文的版本。");
            }
            if (document.getNumberOfPages() > 200) {
                throw new IllegalArgumentException("PDF 最多支持 200 页，请先拆分。");
            }
            PDFTextStripper reader = new PDFTextStripper();
            reader.setSortByPosition(true);
            TextBuffer text = new TextBuffer();
            reader.writeText(document, text);
            return text.toString();
        }
    }

    private static String readWord(byte[] bytes) throws IOException, XMLStreamException {
        int remaining = MAX_EXPANDED_BYTES;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (int index = 0; index < MAX_ENTRIES; index++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                byte[] data = readBytes(zip, Math.min(MAX_FILE_BYTES, remaining));
                remaining -= data.length;
                if (WORD_XML.equals(entry.getName())) {
                    return extractWordXml(data);
                }
            }
        }
        throw new IllegalArgumentException("无法读取 DOCX 的正文，请重新保存为 Word .docx 格式。");
    }

    private static String extractWordXml(byte[] data) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(data));
        TextBuffer text = new TextBuffer();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && WORD_NS.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> text.write(reader.getElementText());
                        case "tab" -> text.write(" ");
                        case "br", "cr" -> text.write("\n");
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && WORD_NS.equals(reader.getNamespaceURI()) && "p".equals(reader.getLocalName())) {
                    text.write("\n");
                }
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new IllegalArgumentException("文件或 ZIP 解压后的内容超过大小限制。");
        }
        return bytes;
    }

    private static List<String> splitText(String text) {
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("正文过长，请拆成多个文件导入。");
        }
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + CHUNK_LENGTH, text.length());
            if (end < text.length()) {
                for (int index = end - 1; index >= start + CHUNK_LENGTH / 2; index--) {
                    if ("\n。！？；".indexOf(text.charAt(index)) >= 0) {
                        end = index + 1;
                        break;
                    }
                }
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String part = text.substring(start, end).strip();
            if (!part.isEmpty()) parts.add(part);
            start = end;
        }
        return parts;
    }

    private static String baseName(String name) {
        if (name == null || name.isBlank()) return "未命名资料";
        String path = name.replace('\\', '/');
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static final class ImportBudget {
        private int remaining = MAX_CHUNKS;
    }

    private static final class TextBuffer extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(char[] buffer, int start, int length) {
            if (text.length() + length > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("正文过长，请拆成多个文件导入。");
            }
            text.append(buffer, start, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    record ImportSummary(int successCount, int failedFiles, List<FileResult> files) {
    }

    record FileResult(String name, int imported, String message) {
    }
}
