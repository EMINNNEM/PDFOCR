package org.caizx.pdfocr.service.impl;

import org.caizx.pdfocr.exception.BusinessException;
import org.caizx.pdfocr.exception.PaddleOcrException;
import org.caizx.pdfocr.model.OcrPageResult;
import org.caizx.pdfocr.model.SplitPageInfo;
import org.caizx.pdfocr.service.PdfOcrService;
import org.caizx.pdfocr.service.PdfSplitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PdfOcrServiceImpl implements PdfOcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrServiceImpl.class);

    private static final Set<String> VALID_MODELS = Set.of("PP-OCRv5", "PP-StructureV3");

    private final PdfSplitService pdfSplitService;
    private final RestTemplate restTemplate;

    @Value("${pdf.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${paddleocr.service.url}")
    private String paddleocrServiceUrl;

    @Value("${paddleocr.token}")
    private String paddleocrToken;

    @Value("${paddleocr.optional.useDocOrientationClassify:true}")
    private boolean useDocOrientationClassify;

    @Value("${paddleocr.optional.useDocUnwarping:false}")
    private boolean useDocUnwarping;

    @Value("${paddleocr.optional.useTextlineOrientation:true}")
    private boolean useTextlineOrientation;

    @Value("${paddleocr.optional.textDetLimitSideLen:960}")
    private int textDetLimitSideLen;

    @Value("${paddleocr.optional.textDetLimitType:max}")
    private String textDetLimitType;

    @Value("${paddleocr.optional.textDetThresh:0.2}")
    private double textDetThresh;

    @Value("${paddleocr.optional.textDetBoxThresh:0.5}")
    private double textDetBoxThresh;

    @Value("${paddleocr.optional.textDetUnclipRatio:2.0}")
    private double textDetUnclipRatio;

    @Value("${paddleocr.optional.textRecScoreThresh:0.3}")
    private double textRecScoreThresh;

    public PdfOcrServiceImpl(PdfSplitService pdfSplitService,
                             RestTemplate restTemplate) {
        this.pdfSplitService = pdfSplitService;
        this.restTemplate = restTemplate;
    }

    @Override
    public List<OcrPageResult> ocrPdf(MultipartFile file, String model) {
        validateInput(file, model);

        List<SplitPageInfo> splitPages = pdfSplitService.splitPdf(file);
        if (splitPages.isEmpty()) {
            throw new BusinessException(400, "PDF has no pages");
        }

        String baseName = extractBaseName(splitPages.get(0).getFileName());
        Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
        log.info("OCR processing {} pages, model={}, outputDir={}", splitPages.size(), model, outputDir);

        List<OcrPageResult> results = new ArrayList<>();
        for (SplitPageInfo page : splitPages) {
            try {
                OcrPageResult result = processPage(page, baseName, outputDir, model);
                results.add(result);
                log.info("Page {} OCR complete: {}", page.getPageNumber(), result.getJsonFileName());
            } catch (Exception e) {
                log.error("Page {} OCR failed: {}", page.getPageNumber(), e.getMessage());
                throw new PaddleOcrException(500,
                        "OCR failed for page " + page.getPageNumber() + ": " + e.getMessage());
            }
        }

        return results;
    }

    private OcrPageResult processPage(SplitPageInfo page, String baseName, Path outputDir, String model)
            throws IOException {
        Path pdfPath = outputDir.resolve(page.getFileName());
        if (!Files.exists(pdfPath)) {
            throw new PaddleOcrException(500, "Page PDF not found: " + pdfPath);
        }

        String jsonFileName = baseName + "-" + page.getPageNumber() + "-" + model + ".json";
        Path jsonPath = outputDir.resolve(jsonFileName);

        String ocrJson = callPaddleOcrService(pdfPath, model);
        Files.writeString(jsonPath, ocrJson, StandardCharsets.UTF_8);

        long jsonSize = Files.size(jsonPath);
        return new OcrPageResult(page.getPageNumber(), page.getFileName(), jsonFileName, jsonSize);
    }

    private String callPaddleOcrService(Path pdfPath, String model) throws IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(pdfPath.toFile()));
        body.add("model", model);
        body.add("token", paddleocrToken);
        body.add("useDocOrientationClassify", String.valueOf(useDocOrientationClassify));
        body.add("useDocUnwarping", String.valueOf(useDocUnwarping));
        body.add("useTextlineOrientation", String.valueOf(useTextlineOrientation));
        body.add("textDetLimitSideLen", String.valueOf(textDetLimitSideLen));
        body.add("textDetLimitType", textDetLimitType);
        body.add("textDetThresh", String.valueOf(textDetThresh));
        body.add("textDetBoxThresh", String.valueOf(textDetBoxThresh));
        body.add("textDetUnclipRatio", String.valueOf(textDetUnclipRatio));
        body.add("textRecScoreThresh", String.valueOf(textRecScoreThresh));
        body.add("extract", "rec_texts");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        log.debug("Calling PaddleOCR service: url={}, model={}", paddleocrServiceUrl, model);

        ResponseEntity<String> response = restTemplate.postForEntity(
                paddleocrServiceUrl, requestEntity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new PaddleOcrException(502,
                    "PaddleOCR service returned status " + response.getStatusCode());
        }

        String responseBody = response.getBody();
        if (responseBody.contains("\"error\"")) {
            log.error("PaddleOCR service returned error: {}", responseBody);
            throw new PaddleOcrException(502, "PaddleOCR service returned an error");
        }

        return responseBody;
    }

    private void validateInput(MultipartFile file, String model) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(400, "Only PDF files are supported");
        }
        if (model == null || !VALID_MODELS.contains(model)) {
            throw new BusinessException(400, "Invalid model: " + model
                    + ", must be one of " + VALID_MODELS);
        }
    }

    private String extractBaseName(String fileName) {
        return fileName.replaceFirst("-\\d+\\.pdf$", "");
    }
}
