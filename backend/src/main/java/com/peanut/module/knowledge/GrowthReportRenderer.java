package com.peanut.module.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.BaseFont;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

@Component
class GrowthReportRenderer {
    private final TemplateEngine templates;
    private final String font;

    GrowthReportRenderer(TemplateEngine templates, @Value("${growth-report.font:}") String configuredFont) {
        this.templates = templates;
        this.font = !configuredFont.isBlank() ? configuredFont : List.of(
                "C:/Windows/Fonts/simsun.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,2",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc,0"
        ).stream().filter(path -> Files.isRegularFile(Path.of(path.substring(0, path.lastIndexOf(',')))))
                .findFirst().orElse("");
    }

    byte[] render(Map<String, Object> report) throws IOException {
        if (font.isEmpty()) {
            throw new IOException("Set GROWTH_REPORT_FONT to a font that can render Chinese");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.getFontResolver().addFont(font, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            Context context = new Context(Locale.SIMPLIFIED_CHINESE, report);
            renderer.setDocumentFromString(templates.process("growth-report", context));
            renderer.layout();
            renderer.createPDF(output);
            return output.toByteArray();
        } catch (DocumentException error) {
            throw new IOException("Could not render growth report", error);
        }
    }
}
