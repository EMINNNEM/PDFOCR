package org.caizx.pdfocr.service;

import org.caizx.pdfocr.model.ComponentExtractionResult;
import org.springframework.web.multipart.MultipartFile;

public interface ComponentExtractionService {

    ComponentExtractionResult extractComponents(MultipartFile image);
}
