package org.caizx.pdfocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class PdfocrApplication {

    public static void main(String[] args) {
        SpringApplication.run(PdfocrApplication.class, args);
    }
}
