package org.caizx.pdfocr.controller;

import org.caizx.pdfocr.exception.BusinessException;
import org.caizx.pdfocr.model.Result;
import org.caizx.pdfocr.model.SplitPageInfo;
import org.caizx.pdfocr.service.PdfSplitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/pdf")
public class PdfSplitController {

    private static final Logger log = LoggerFactory.getLogger(PdfSplitController.class);

    private final PdfSplitService pdfSplitService;

    @Value("${pdf.upload-dir:./uploads}")
    private String uploadDir;

    public PdfSplitController(PdfSplitService pdfSplitService) {
        this.pdfSplitService = pdfSplitService;
    }

    @PostMapping("/split")
    public Result<List<SplitPageInfo>> splitPdf(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "Uploaded file is empty");
        }
        List<SplitPageInfo> results = pdfSplitService.splitPdf(file);
        return Result.success(results);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(
            @RequestParam("dir") String dir,
            @RequestParam("page") int page) {
        if (dir.contains("..") || dir.contains("/") || dir.contains("\\")) {
            throw new BusinessException(400, "Invalid directory parameter");
        }
        if (page < 1) {
            throw new BusinessException(400, "Page number must be positive");
        }

        String fileName = dir + "-" + page + ".pdf";
        Path filePath = Paths.get(uploadDir).toAbsolutePath().normalize()
                .resolve(dir).resolve(fileName);

        if (!Files.exists(filePath)) {
            throw new BusinessException(404, "File not found: " + fileName);
        }

        try {
            FileInputStream fis = new FileInputStream(filePath.toFile());
            InputStreamResource resource = new InputStreamResource(fis);

            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodedFileName)
                    .body(resource);
        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            throw new BusinessException(500, "Failed to read file");
        }
    }
}
