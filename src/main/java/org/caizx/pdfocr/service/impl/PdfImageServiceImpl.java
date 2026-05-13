package org.caizx.pdfocr.service.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.caizx.pdfocr.exception.BusinessException;
import org.caizx.pdfocr.exception.PaddleOcrException;
import org.caizx.pdfocr.model.ImagePageInfo;
import org.caizx.pdfocr.model.SplitPageInfo;
import org.caizx.pdfocr.service.PdfImageService;
import org.caizx.pdfocr.service.PdfSplitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfImageServiceImpl implements PdfImageService {

    private static final Logger log = LoggerFactory.getLogger(PdfImageServiceImpl.class);

    private final PdfSplitService pdfSplitService;

    @Value("${pdf.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${pdf.image.dpi:200}")
    private int dpi;

    public PdfImageServiceImpl(PdfSplitService pdfSplitService) {
        this.pdfSplitService = pdfSplitService;
    }

    @Override
    public List<ImagePageInfo> convertToImages(MultipartFile file) {
        validateFile(file);

        List<SplitPageInfo> splitPages = pdfSplitService.splitPdf(file);
        if (splitPages.isEmpty()) {
            throw new BusinessException(400, "PDF has no pages");
        }

        String baseName = extractBaseName(splitPages.get(0).getFileName());
        Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
        float scale = dpi / 72f;
        log.info("Converting {} pages to PNG, dpi={}, scale={}, outputDir={}",
                splitPages.size(), dpi, scale, outputDir);

        List<ImagePageInfo> results = new ArrayList<>();
        for (SplitPageInfo page : splitPages) {
            try {
                ImagePageInfo result = renderPage(page, baseName, outputDir, scale);
                results.add(result);
                log.info("Page {} rendered: {} ({}x{}, {} bytes)",
                        page.getPageNumber(), result.getImageFileName(),
                        result.getImageWidth(), result.getImageHeight(),
                        result.getImageFileSize());
            } catch (IOException e) {
                log.error("Page {} render failed: {}", page.getPageNumber(), e.getMessage());
                throw new PaddleOcrException(500,
                        "Image conversion failed for page " + page.getPageNumber() + ": " + e.getMessage());
            }
        }

        return results;
    }

    private ImagePageInfo renderPage(SplitPageInfo page, String baseName, Path outputDir, float scale)
            throws IOException {
        Path pdfPath = outputDir.resolve(page.getFileName());
        if (!Files.exists(pdfPath)) {
            throw new PaddleOcrException(500, "Page PDF not found: " + pdfPath);
        }

        String imageFileName = baseName + "-" + page.getPageNumber() + ".png";
        Path imagePath = outputDir.resolve(imageFileName);

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImage(0, scale);
            ImageIO.write(image, "PNG", imagePath.toFile());

            long fileSize = Files.size(imagePath);
            return new ImagePageInfo(page.getPageNumber(), page.getFileName(),
                    imageFileName, image.getWidth(), image.getHeight(), fileSize);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(400, "Only PDF files are supported");
        }
    }

    private String extractBaseName(String fileName) {
        return fileName.replaceFirst("-\\d+\\.pdf$", "");
    }
}
