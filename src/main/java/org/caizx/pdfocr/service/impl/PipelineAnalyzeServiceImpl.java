package org.caizx.pdfocr.service.impl;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.caizx.pdfocr.exception.BusinessException;
import org.caizx.pdfocr.exception.PaddleOcrException;
import org.caizx.pdfocr.model.PageComponentsResult;
import org.caizx.pdfocr.model.SplitPageInfo;
import org.caizx.pdfocr.service.PdfSplitService;
import org.caizx.pdfocr.service.PipelineAnalyzeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PipelineAnalyzeServiceImpl implements PipelineAnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(PipelineAnalyzeServiceImpl.class);

    private final PdfSplitService pdfSplitService;
    private final ComponentExtractionServiceImpl extractionService;

    @Value("${pdf.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${pdf.image.dpi:200}")
    private int dpi;

    @Value("${pdf.analysis.threads:3}")
    private int threadCount;

    @Value("${pdf.analysis.timeout-seconds:120}")
    private int timeoutSeconds;

    private volatile ThreadPoolExecutor executor;

    public PipelineAnalyzeServiceImpl(PdfSplitService pdfSplitService,
                                      ComponentExtractionServiceImpl extractionService) {
        this.pdfSplitService = pdfSplitService;
        this.extractionService = extractionService;
    }

    private ThreadPoolExecutor getExecutor() {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    executor = new ThreadPoolExecutor(
                            threadCount, threadCount,
                            60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>());
                    executor.allowCoreThreadTimeOut(true);
                }
            }
        }
        return executor;
    }

    // ─── Synchronous methods (unchanged) ──────────────────────────────

    @Override
    public List<PageComponentsResult> analyzePipeline(MultipartFile file) {
        validatePdf(file);
        List<SplitPageInfo> splitPages = pdfSplitService.splitPdf(file);
        if (splitPages.isEmpty()) throw new BusinessException(400, "PDF has no pages");
        String baseName = extractBaseName(splitPages.get(0).getFileName());
        Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
        float scale = dpi / 72f;

        List<PageComponentsResult> results = new ArrayList<>();
        for (SplitPageInfo page : splitPages) {
            try {
                Path pdfPath = outputDir.resolve(page.getFileName());
                byte[] pngBytes = renderPageToPng(pdfPath, scale);
                String imageName = baseName + "-" + page.getPageNumber() + ".png";
                Files.write(outputDir.resolve(imageName), pngBytes);
                List<String> components = extractionService.callAiForComponents(pngBytes, "image/png", imageName);
                String jsonName = baseName + "-" + page.getPageNumber() + "-components.json";
                extractionService.saveComponentsJson(outputDir, jsonName, components);
                results.add(new PageComponentsResult(page.getPageNumber(), imageName, components));
            } catch (IOException e) {
                throw new PaddleOcrException(500, "Page " + page.getPageNumber() + " failed: " + e.getMessage());
            }
        }
        return results;
    }

    @Override
    public List<PageComponentsResult> analyzePages(MultipartFile file) {
        validatePdf(file);
        List<SplitPageInfo> splitPages = pdfSplitService.splitPdf(file);
        if (splitPages.isEmpty()) throw new BusinessException(400, "PDF has no pages");
        String baseName = extractBaseName(splitPages.get(0).getFileName());
        Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);

        List<PageComponentsResult> results = new ArrayList<>();
        for (SplitPageInfo page : splitPages) {
            try {
                byte[] pdfBytes = Files.readAllBytes(outputDir.resolve(page.getFileName()));
                List<String> components = extractionService.callAiForComponents(pdfBytes, "application/pdf", page.getFileName());
                String jsonName = baseName + "-" + page.getPageNumber() + "-components.json";
                extractionService.saveComponentsJson(outputDir, jsonName, components);
                results.add(new PageComponentsResult(page.getPageNumber(), page.getFileName(), components));
            } catch (IOException e) {
                throw new PaddleOcrException(500, "Page " + page.getPageNumber() + " failed: " + e.getMessage());
            }
        }
        return results;
    }

    @Override
    public List<PageComponentsResult> analyzeFull(MultipartFile file) {
        validatePdf(file);
        String originalFilename = file.getOriginalFilename();
        String baseName = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.')) : "full-analysis";
        try {
            byte[] pdfBytes = file.getBytes();
            List<String> components = extractionService.callAiForComponents(pdfBytes, "application/pdf", originalFilename);
            Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
            Files.createDirectories(outputDir);
            extractionService.saveComponentsJson(outputDir, baseName + "-full-components.json", components);
            return List.of(new PageComponentsResult(1, originalFilename, components));
        } catch (IOException e) {
            throw new PaddleOcrException(500, "Failed: " + e.getMessage());
        }
    }

    // ─── Async with SSE ──────────────────────────────────────────────

    @Override
    public void analyzeAsync(MultipartFile file, String mode, SseEmitter emitter) {
        ThreadPoolExecutor pool = getExecutor();
        pool.submit(() -> {
            try {
                doAnalyzeAsync(file, mode, emitter);
            } catch (Exception e) {
                log.error("Async analysis fatal error", e);
                safeSend(emitter, "error", Map.of("message", e.getMessage()));
                emitter.completeWithError(e);
            }
        });
    }

    @Override
    public PageComponentsResult retryPage(MultipartFile file, String mode, String baseName, int pageNumber) {
        try {
            byte[] fileBytes = file.getBytes();
            String originalFilename = file.getOriginalFilename();
            String mime = mode.contains("pipeline") ? "image/png" : "application/pdf";

            List<String> components = callAiWithRetry(fileBytes, mime,
                    originalFilename != null ? originalFilename : ("page-" + pageNumber));

            Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
            Files.createDirectories(outputDir);
            String jsonName = baseName + "-" + pageNumber + "-components.json";
            extractionService.saveComponentsJson(outputDir, jsonName, components);

            return new PageComponentsResult(pageNumber,
                    originalFilename != null ? originalFilename : "", components);
        } catch (IOException e) {
            throw new PaddleOcrException(500, "Retry failed: " + e.getMessage());
        }
    }

    // ─── Core async logic ────────────────────────────────────────────

    private void doAnalyzeAsync(MultipartFile file, String mode, SseEmitter emitter) {
        safeSend(emitter, "stage", Map.of("stage", "split", "message", "拆分 PDF..."));
        List<SplitPageInfo> splitPages = pdfSplitService.splitPdf(file);
        if (splitPages.isEmpty()) {
            safeSend(emitter, "error", Map.of("message", "PDF has no pages"));
            emitter.complete();
            return;
        }

        String baseName = extractBaseName(splitPages.get(0).getFileName());
        Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
        float scale = dpi / 72f;
        int total = splitPages.size();
        log.info("Async {}: {} pages, threads={}, timeout={}s", mode, total, threadCount, timeoutSeconds);

        // Render all PNGs first (if pipeline mode) — this is fast, done sequentially
        if ("pipeline".equals(mode)) {
            safeSend(emitter, "stage", Map.of("stage", "convert", "message", "渲染高清 PNG..."));
            for (SplitPageInfo page : splitPages) {
                try {
                    Path pdfPath = outputDir.resolve(page.getFileName());
                    byte[] pngBytes = renderPageToPng(pdfPath, scale);
                    String imageName = baseName + "-" + page.getPageNumber() + ".png";
                    Files.write(outputDir.resolve(imageName), pngBytes);
                } catch (IOException e) {
                    safeSend(emitter, "page", Map.of(
                            "pageNumber", page.getPageNumber(),
                            "status", "failed",
                            "error", "PNG render failed: " + e.getMessage()));
                }
            }
        }

        safeSend(emitter, "stage", Map.of("stage", "analyze", "message", "AI 识别元器件（并发中）..."));

        // Submit all pages concurrently to thread pool
        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> failedPages = new ArrayList<>();
        Map<Integer, PageComponentsResult> results = new LinkedHashMap<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            final SplitPageInfo page = splitPages.get(i);
            final int pageNum = page.getPageNumber();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                safeSend(emitter, "page", Map.of("pageNumber", pageNum, "status", "processing"));
                try {
                    byte[] fileBytes;
                    String imageName;
                    String mime;

                    if ("pipeline".equals(mode)) {
                        imageName = baseName + "-" + pageNum + ".png";
                        fileBytes = Files.readAllBytes(outputDir.resolve(imageName));
                        mime = "image/png";
                    } else {
                        imageName = page.getFileName();
                        fileBytes = Files.readAllBytes(outputDir.resolve(imageName));
                        mime = "application/pdf";
                    }

                    List<String> components = callAiWithRetry(fileBytes, mime, imageName);

                    String jsonName = baseName + "-" + pageNum + "-components.json";
                    extractionService.saveComponentsJson(outputDir, jsonName, components);

                    var result = new PageComponentsResult(pageNum, imageName, components);
                    synchronized (results) {
                        results.put(pageNum, result);
                    }
                    successCount.incrementAndGet();
                    safeSend(emitter, "page", Map.of(
                            "pageNumber", pageNum, "status", "done",
                            "imageFileName", imageName, "components", components));

                } catch (Exception e) {
                    log.error("Page {} failed after retry: {}", pageNum, e.getMessage());
                    synchronized (failedPages) {
                        failedPages.add(pageNum);
                    }
                    safeSend(emitter, "page", Map.of(
                            "pageNumber", pageNum, "status", "failed",
                            "error", e.getMessage()));
                }
            }, getExecutor());
            futures.add(future);
        }

        // Wait for all to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(Math.max(total * (long) timeoutSeconds * 2, 600L), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Global timeout waiting for futures", e);
        }

        // Build ordered result list
        List<PageComponentsResult> ordered = new ArrayList<>();
        for (SplitPageInfo p : splitPages) {
            PageComponentsResult r = results.get(p.getPageNumber());
            if (r != null) ordered.add(r);
        }

        safeSend(emitter, "done", Map.of(
                "totalPages", total,
                "successPages", successCount.get(),
                "failedPages", failedPages,
                "baseName", baseName,
                "data", ordered));

        emitter.complete();
    }

    // ─── AI call with timeout + retry ────────────────────────────────

    private List<String> callAiWithRetry(byte[] fileBytes, String mime, String logName) {
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(
                () -> extractionService.callAiForComponents(fileBytes, mime, logName));

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("AI timeout for {}, retrying once...", logName);
            CompletableFuture<List<String>> retry = CompletableFuture.supplyAsync(
                    () -> extractionService.callAiForComponents(fileBytes, mime, logName + " (retry)"));
            try {
                return retry.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e2) {
                throw new PaddleOcrException(504, "AI timeout after retry: " + logName);
            } catch (Exception e2) {
                throw new PaddleOcrException(500, "AI retry failed: " + e2.getMessage());
            }
        } catch (Exception e) {
            throw new PaddleOcrException(500, "AI call failed: " + e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private byte[] renderPageToPng(Path pdfPath, float scale) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImage(0, scale);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private void safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
        }
    }

    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "Uploaded file is empty");
        String f = file.getOriginalFilename();
        if (f == null || !f.toLowerCase().endsWith(".pdf")) throw new BusinessException(400, "Only PDF files are supported");
    }

    private String extractBaseName(String fileName) {
        return fileName.replaceFirst("-\\d+\\.pdf$", "");
    }
}
