package org.caizx.pdfocr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrPageResult {

    private int pageNumber;
    private String pdfFileName;
    private String jsonFileName;
    private long jsonFileSize;
}
