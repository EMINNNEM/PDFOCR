package org.caizx.pdfocr.service.impl;

import org.caizx.pdfocr.exception.BusinessException;
import org.caizx.pdfocr.exception.PaddleOcrException;
import org.caizx.pdfocr.model.ComponentExtractionResult;
import org.caizx.pdfocr.service.ComponentExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ComponentExtractionServiceImpl implements ComponentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ComponentExtractionServiceImpl.class);

    private static final String SYSTEM_PROMPT = """
            你是一个电气工程领域的信息抽取助手。

            任务：从给定的图片中提取"元器件名称"。

            【元器件定义】
            元器件是指电气系统中的功能部件，例如：
            - 继电器（如：继电器K1）
            - 传感器（如：温度传感器）
            - 控制器（如：ECU）
            - 开关、保险丝、电机、灯等

            【排除内容】
            以下内容不是元器件：
            - 人名（如：王伟、李国辉）
            - 日期（如：20211207）
            - 编号（如：CA1234E6）
            - 无意义单字母（如：A、B、K）

            【要求】
            1. 只输出元器件名称
            2. 去重
            3. 保持原始文本（不要改写）
            4. 严格输出JSON格式，不要包含markdown代码块标记

            【输出格式】
            {"components": ["元器件1", "元器件2"]}
            """;

    private static final Pattern JSON_PATTERN = Pattern.compile(
            "\\{\\s*\"components\"\\s*:\\s*\\[.*?]\\s*}", Pattern.DOTALL);

    private static final Pattern STRING_PATTERN = Pattern.compile("\"([^\"]*)\"");

    private final ChatClient chatClient;

    @Value("${pdf.upload-dir:./uploads}")
    private String uploadDir;

    public ComponentExtractionServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public ComponentExtractionResult extractComponents(MultipartFile image) {
        validateFile(image);

        String originalFilename = image.getOriginalFilename();
        String nameWithoutExt = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : "extraction";
        String baseName = nameWithoutExt.replaceFirst("-\\d+$", "");

        try {
            byte[] imageBytes = image.getBytes();

            List<String> components = callAiForComponents(
                    imageBytes, "image/png", originalFilename);

            Path outputDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(baseName);
            Files.createDirectories(outputDir);

            String jsonFileName = nameWithoutExt + "-components.json";
            long jsonSize = saveComponentsJson(outputDir, jsonFileName, components);

            return new ComponentExtractionResult(originalFilename, jsonFileName, jsonSize, components);

        } catch (IOException e) {
            throw new PaddleOcrException(500, "Failed to process image: " + e.getMessage());
        }
    }

    List<String> callAiForComponents(byte[] fileBytes, String mimeType, String logName) {
        String type = mimeType.contains("pdf") ? "pdf" : mimeType.contains("/") ? mimeType.split("/")[1] : mimeType;
        MimeType mt = new MimeType(mimeType.split("/")[0], type);

        log.info("Sending file to AI for component extraction: {}, size={} bytes, mime={}",
                logName, fileBytes.length, mimeType);

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userSpec -> userSpec
                        .text("请从文件中提取元器件名称")
                        .media(mt, new ByteArrayResource(fileBytes)))
                .call()
                .content();

        log.info("AI response (first 300 chars): {}",
                response.length() > 300 ? response.substring(0, 300) + "..." : response);

        return parseComponents(response);
    }

    long saveComponentsJson(Path outputDir, String jsonFileName, List<String> components) throws IOException {
        Path jsonPath = outputDir.resolve(jsonFileName);
        String jsonContent = buildResultJson(components);
        Files.writeString(jsonPath, jsonContent, StandardCharsets.UTF_8);
        long jsonSize = Files.size(jsonPath);
        log.info("Saved {} components to {} ({} bytes)", components.size(), jsonPath, jsonSize);
        return jsonSize;
    }

    private List<String> parseComponents(String aiResponse) {
        Matcher jsonMatcher = JSON_PATTERN.matcher(aiResponse);
        if (jsonMatcher.find()) {
            String jsonBlock = jsonMatcher.group();
            return extractStrings(jsonBlock);
        }

        String trimmed = aiResponse.trim();
        if (trimmed.startsWith("{")) {
            return extractStrings(trimmed);
        }

        log.warn("Could not parse JSON from AI response, treating as plain text list");
        return List.of(aiResponse.trim());
    }

    private List<String> extractStrings(String json) {
        LinkedHashSet<String> components = new LinkedHashSet<>();
        Matcher strMatcher = STRING_PATTERN.matcher(json);
        boolean inArray = false;
        while (strMatcher.find()) {
            String keyOrValue = strMatcher.group(1);
            if ("components".equals(keyOrValue)) {
                inArray = true;
                continue;
            }
            if (inArray && !keyOrValue.isBlank() && keyOrValue.length() > 1) {
                components.add(keyOrValue);
            }
        }
        return new ArrayList<>(components);
    }

    private String buildResultJson(List<String> components) {
        StringBuilder sb = new StringBuilder("{\n  \"components\": [\n");
        for (int i = 0; i < components.size(); i++) {
            sb.append("    \"").append(escapeJson(components.get(i))).append("\"");
            if (i < components.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractBaseName(String fileName) {
        if (fileName == null) {
            return "component-extraction";
        }
        String withoutExt = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        return withoutExt.replaceFirst("-\\d+$", "");
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new BusinessException(400, "File name is missing");
        }
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            throw new BusinessException(400, "Only PNG/JPG images are supported");
        }
    }
}
