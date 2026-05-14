package org.caizx.pdfocr.service;

import org.caizx.pdfocr.model.PageComponentsResult;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface PipelineAnalyzeService {

    List<PageComponentsResult> analyzePipeline(MultipartFile file);

    List<PageComponentsResult> analyzePages(MultipartFile file);

    List<PageComponentsResult> analyzeFull(MultipartFile file);

    /** Async with SSE: real-time per-page progress pushed to frontend */
    void analyzeAsync(MultipartFile file, String mode, SseEmitter emitter);

    /** Retry a single page: accepts a page file (PDF or PNG) */
    PageComponentsResult retryPage(MultipartFile file, String mode, String baseName, int pageNumber);
}
