package org.caizx.pdfocr.service.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.caizx.pdfocr.exception.BusinessException;
import org.caizx.pdfocr.model.SplitPageInfo;
import org.caizx.pdfocr.service.PdfSplitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PdfSplitServiceImpl implements PdfSplitService {

    private static final Logger log = LoggerFactory.getLogger(PdfSplitServiceImpl.class);

    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff]");

    @Value("${pdf.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public List<SplitPageInfo> splitPdf(MultipartFile file) {
        validateFile(file);

        String originalFilename = fixFilenameEncoding(file.getOriginalFilename());
        String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));

        try {
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);

            Path sourcePath = uploadPath.resolve(originalFilename);
            file.transferTo(sourcePath.toFile());
            log.info("Source PDF saved: {}", sourcePath);

            Path outputDir = uploadPath.resolve(baseName);
            Files.createDirectories(outputDir);

            List<SplitPageInfo> results = new ArrayList<>();
            try (PDDocument document = Loader.loadPDF(sourcePath.toFile())) {
                Splitter splitter = new Splitter();
                List<PDDocument> pages = splitter.split(document);

                for (int i = 0; i < pages.size(); i++) {
                    int pageNumber = i + 1;
                    String pageFileName = baseName + "-" + pageNumber + ".pdf";
                    Path pagePath = outputDir.resolve(pageFileName);

                    try (PDDocument pageDoc = pages.get(i)) {
                        pageDoc.save(pagePath.toFile());
                        long fileSize = Files.size(pagePath);
                        results.add(new SplitPageInfo(pageNumber, pageFileName, fileSize));
                        log.info("Saved page {}: {} ({} bytes)", pageNumber, pageFileName, fileSize);
                    }
                }
            }

            Files.deleteIfExists(sourcePath);
            log.info("Deleted source file: {}", sourcePath);

            return results;
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to process PDF: " + e.getMessage());
        }
    }

    private String fixFilenameEncoding(String filename) {
        if (filename == null) {
            return null;
        }
        if (CJK_PATTERN.matcher(filename).find()) {
            return filename;
        }
        try {
            byte[] bytes = filename.getBytes(StandardCharsets.ISO_8859_1);
            String fixed = new String(bytes, StandardCharsets.UTF_8);
            if (CJK_PATTERN.matcher(fixed).find()) {
                return fixed;
            }
        } catch (Exception ignored) {
        }
        return filename;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(400, "Only PDF files are supported");
        }
        String safeName = filename.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (safeName.contains("..")) {
            throw new BusinessException(400, "Invalid file name");
        }
    }
}
