package org.caizx.pdfocr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImagePageInfo {

    private int pageNumber;
    private String pdfFileName;
    private String imageFileName;
    private int imageWidth;
    private int imageHeight;
    private long imageFileSize;
}
