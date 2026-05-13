package org.caizx.pdfocr.service;

import org.caizx.pdfocr.model.SplitPageInfo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PdfSplitService {

    List<SplitPageInfo> splitPdf(MultipartFile file);
}
