package org.caizx.pdfocr.controller;

import org.caizx.pdfocr.model.PageComponentsResult;
import org.caizx.pdfocr.model.Result;
import org.caizx.pdfocr.service.PipelineAnalyzeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/pdf")
public class PipelineAnalyzeController {

    private final PipelineAnalyzeService pipelineAnalyzeService;

    public PipelineAnalyzeController(PipelineAnalyzeService pipelineAnalyzeService) {
        this.pipelineAnalyzeService = pipelineAnalyzeService;
    }

    @PostMapping("/analyze-pipeline")
    public Result<List<PageComponentsResult>> analyzePipeline(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error(400, "Uploaded file is empty");
        return Result.success(pipelineAnalyzeService.analyzePipeline(file));
    }

    @PostMapping("/analyze-pages")
    public Result<List<PageComponentsResult>> analyzePages(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error(400, "Uploaded file is empty");
        return Result.success(pipelineAnalyzeService.analyzePages(file));
    }

    @PostMapping("/analyze-full")
    public Result<List<PageComponentsResult>> analyzeFull(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error(400, "Uploaded file is empty");
        return Result.success(pipelineAnalyzeService.analyzeFull(file));
    }

    /** Async pipeline with SSE real-time progress */
    @PostMapping(value = "/analyze-async", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeAsync(@RequestParam("file") MultipartFile file,
                                   @RequestParam("mode") String mode) {
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        SseEmitter emitter = new SseEmitter(1800000L); // 30 min
        pipelineAnalyzeService.analyzeAsync(file, mode, emitter);
        return emitter;
    }

    /** Retry a single failed page */
    @PostMapping("/retry-page")
    public Result<PageComponentsResult> retryPage(@RequestParam("file") MultipartFile file,
                                                   @RequestParam("mode") String mode,
                                                   @RequestParam("baseName") String baseName,
                                                   @RequestParam("pageNumber") int pageNumber) {
        if (file.isEmpty()) return Result.error(400, "Uploaded file is empty");
        PageComponentsResult result = pipelineAnalyzeService.retryPage(file, mode, baseName, pageNumber);
        return Result.success(result);
    }
}
