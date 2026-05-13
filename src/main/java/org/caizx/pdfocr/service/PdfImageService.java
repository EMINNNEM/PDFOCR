package org.caizx.pdfocr.service;

import org.caizx.pdfocr.model.ImagePageInfo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PdfImageService {

    List<ImagePageInfo> convertToImages(MultipartFile file);
}
