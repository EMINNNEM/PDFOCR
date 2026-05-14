package org.caizx.pdfocr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageComponentsResult {

    private int pageNumber;
    private String imageFileName;
    private List<String> components;
}
