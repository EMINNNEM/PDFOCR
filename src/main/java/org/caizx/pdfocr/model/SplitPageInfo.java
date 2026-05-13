package org.caizx.pdfocr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SplitPageInfo {

    private int pageNumber;
    private String fileName;
    private long fileSize;
}
