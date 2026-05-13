package org.caizx.pdfocr.service;

import org.caizx.pdfocr.model.OcrPageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PdfOcrService {

    List<OcrPageResult> ocrPdf(MultipartFile file, String model);
}
