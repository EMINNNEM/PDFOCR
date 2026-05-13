package org.caizx.pdfocr.exception;

import lombok.Getter;

@Getter
public class PaddleOcrException extends RuntimeException {

    private final int code;

    public PaddleOcrException(int code, String message) {
        super(message);
        this.code = code;
    }

    public PaddleOcrException(String message) {
        this(500, message);
    }
}
