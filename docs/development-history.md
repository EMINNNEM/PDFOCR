# PDFOCR 项目开发历程

> Spring Boot 4.0.6 + Java 17, PDFBox 3.0.4, Spring AI 2.0.0-M1, PaddleOCR, Flask

## 项目概述

从零搭建的 PDF 智能分析平台，支持 PDF 拆页、高清 PNG 转换、PaddleOCR 文本识别、AI 元器件提取。

## 功能迭代

### 1. PDF 拆页（第 1 轮）

**需求**：前端上传 PDF → 后端拆分为单页 PDF，无质量损失。

**实现**：
- `PdfSplitController` + `PdfSplitServiceImpl`：使用 PDFBox `Splitter` 逐页拆分
- 输出到 `./uploads/{baseName}/`，命名 `{baseName}-{pageNumber}.pdf`
- 拆分后自动删除源文件
- `frontend/index.html`：拖拽上传 + 下载

**遇到的问题**：
- 中文文件名编码：curl 传中文文件名时 Spring 按 ISO-8859-1 解析导致乱码，通过 `CharacterEncodingFilter` + 服务层 `fixFilenameEncoding()` 修复
- `<java.version>25</java.version>` 但系统仅有 JDK 17，降为 17

### 2. PaddleOCR 文本识别（第 2 轮）

**需求**：拆分后每页调用 PaddleOCR 云 API 提取文本（PP-OCRv5 / PP-StructureV3）。

**架构演进**：
- 初版：Java 通过 `ProcessBuilder` 调用 Python 脚本
- 终版：Python Flask 服务（`:5000`）→ Java HTTP 调用，更健壮

**实现**：
- `PaddleOCR/service.py`：Flask 服务，封装 PaddleOCR API 的作业提交 + 轮询
- `PdfOcrServiceImpl`：调用 Python 服务，保存 JSON 结果
- `PdfOcrController`：`POST /api/pdf/ocr?model=PP-OCRv5`

**遇到的问题**：
- Auth header：API 手册要求 `token {TOKEN}`，不是 `Bearer {TOKEN}`
- 缺少 `requiredPayload`：必须传 `{"fileType": 0}`
- `optionalPayload` 参数不全：初版仅 4 个参数，对照参考脚本 `PaddleOCR.py` 补全为 10 个（包括 `textDetThresh: 0.2`、`textDetUnclipRatio: 2.0` 等电路图调优参数）
- JSON 中文转义：Flask `jsonify()` 默认 `ensure_ascii=True` → `app.json.ensure_ascii = False` 修复
- Java `RestTemplate` 的 `StringHttpMessageConverter` 默认 `ISO-8859-1` → 强制 UTF-8
- 输出精简：新增 `extract=rec_texts` 参数，仅保存 `rec_texts` 数组（从 120KB 缩至 1.4KB）

### 3. PDF 转高清 PNG（第 3 轮）

**需求**：拆分后的每页 PDF 转为高清 PNG，无损压缩。

**实现**：
- `PdfImageServiceImpl`：PDFBox `PDFRenderer.renderImage(page, scale)`，`scale = dpi / 72f`
- `PdfImageController`：`POST /api/pdf/to-images`，默认 200 DPI，最高输出 9362×18724px
- `frontend/images.html`：缩略图预览 + 下载

### 4. AI 元器件提取（第 4 轮）

**需求**：发送 PNG 给 AI，提取电气图中的元器件名称。

**参考项目**：`C:\Code\Java\interview-guide`（Spring AI 2.0.0-M1 + DashScope Qwen）

**实现**：
- 添加 `spring-ai-starter-model-openai:2.0.0-M1` + Spring Milestones 仓库
- 配置迁移：`.properties` → `application.yml`
- `ComponentExtractionServiceImpl`：Spring AI `ChatClient` 多模态调用
  - System prompt：电气工程信息抽取（元器件定义、排除规则、JSON 输出格式）
  - User prompt：发送 PNG 图片（`Media mime=image/png`）
- `ComponentExtractionController`：`POST /api/pdf/extract-components`
- `frontend/components.html`：表格展示元器件名称

**遇到的问题**：
- `Media` 类导入路径：`org.springframework.ai.content.Media`（不是 `org.springframework.ai.model.Media`）
- API Key 环境变量：`AI_BAILIAN_API_KEY`
- 模型配置：`qwen-vl-max`（视觉语言模型），`temperature: 0.1`

### 5. 全流程 Pipeline（第 5 轮）

**需求**：从前端上传 PDF → 拆分 → 转 PNG → AI 提取 → 表格展示。

**实现**：
- `PipelineAnalyzeServiceImpl`：编排 `splitPdf` → `renderPageToPng` → `callAiForComponents`
- `PipelineAnalyzeController`：3 个端点
  - `POST /api/pdf/analyze-pipeline`：拆分→PNG→AI（质量最高）
  - `POST /api/pdf/analyze-pages`：拆分后直接发 PDF 到 AI
  - `POST /api/pdf/analyze-full`：整个 PDF 一次发 AI
- `frontend/pipeline.html`：模式选择 + 阶段进度 + 分页 Tab

**遇到的问题**：
- `MultipartFile` 被 `splitPdf()` 消费后无法再次 `convertToImages(file)` → 改为直接从磁盘读取已拆分的页面 PDF 渲染 PNG

### 6. 异步多线程 Pipeline（第 6 轮）

**需求**：并发调 AI（13 页从 ~25 分钟缩至 ~8 分钟）、SSE 实时进度、超时重试、失败恢复。

**实现**：
- `ThreadPoolExecutor`（3 线程）并发提交 AI 调用
- `CompletableFuture.get(120s)` 超时 → 重试 1 次 → 标记 failed
- SSE 实时推送：`stage` → `page(status)` → `done`
- `SseEmitter` 返回给前端
- `POST /api/pdf/analyze-async`（SSE 端点）
- `POST /api/pdf/retry-page`（失败页面重试）
- `frontend/pipeline.html` 重写：
  - `fetch()` + `ReadableStream` 消费 SSE
  - 实时渲染每页状态卡片（⏳等待 / 🔄处理中 / ✅完成 / ❌失败）
  - 失败页面 `重试` 按钮

## 最终项目结构

```
src/main/java/org/caizx/pdfocr/
├── PdfocrApplication.java           @SpringBootApplication + @EnableAsync
├── config/
│   └── WebMvcConfig.java            CORS, RestTemplate(UTF-8), CharacterEncodingFilter
├── controller/
│   ├── PdfSplitController.java      POST split / GET download
│   ├── PdfOcrController.java        POST ocr / GET download-json
│   ├── PdfImageController.java      POST to-images / GET download-image
│   ├── ComponentExtractionController.java  POST extract-components
│   └── PipelineAnalyzeController.java      POST analyze-*, POST retry-page, SSE
├── model/
│   ├── Result.java                  统一响应 {code, message, data}
│   ├── SplitPageInfo.java           PDF 拆分结果
│   ├── OcrPageResult.java           OCR 结果
│   ├── ImagePageInfo.java           PNG 转换结果
│   ├── ComponentExtractionResult.java  AI 提取结果
│   └── PageComponentsResult.java    每页元器件结果
├── service/
│   ├── PdfSplitService.java
│   ├── PdfOcrService.java
│   ├── PdfImageService.java
│   ├── ComponentExtractionService.java
│   ├── PipelineAnalyzeService.java
│   └── impl/
│       ├── PdfSplitServiceImpl.java
│       ├── PdfOcrServiceImpl.java
│       ├── PdfImageServiceImpl.java
│       ├── ComponentExtractionServiceImpl.java
│       └── PipelineAnalyzeServiceImpl.java
└── exception/
    ├── BusinessException.java
    ├── PaddleOcrException.java
    └── GlobalExceptionHandler.java

PaddleOCR/
├── service.py          Flask OCR 服务（端口 5000）
├── PaddleOCR.py        参考脚本
├── PP-OCRv5.py         OCRv5 示例
└── PP-StructureV3.py   StructureV3 示例

frontend/
├── index.html          PDF 拆分工具
├── ocr.html            OCR 文本提取
├── images.html         PDF 转 PNG
├── components.html     AI 元器件提取
└── pipeline.html       全流程异步分析（SSE 实时进度）
```

## REST API 总览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/pdf/split` | 拆分为单页 PDF |
| GET | `/api/pdf/download?dir=&page=` | 下载拆分单页 |
| POST | `/api/pdf/ocr?model=PP-OCRv5` | PaddleOCR 文本识别 |
| GET | `/api/pdf/download-json?dir=&page=&model=` | 下载 OCR JSON |
| POST | `/api/pdf/to-images` | PDF 转高清 PNG |
| GET | `/api/pdf/download-image?dir=&page=` | 下载 PNG |
| POST | `/api/pdf/extract-components` | AI 提取元器件 |
| POST | `/api/pdf/analyze-pipeline` | 全流程同步 |
| POST | `/api/pdf/analyze-pages` | 拆分后 AI 分析 |
| POST | `/api/pdf/analyze-full` | 整份 PDF AI 分析 |
| POST | `/api/pdf/analyze-async` | **全流程异步 SSE** |
| POST | `/api/pdf/retry-page` | 失败页面重试 |

## 配置要点 `application.yml`

```yaml
spring:
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      api-key: ${AI_BAILIAN_API_KEY:}
      chat:
        options:
          model: ${AI_MODEL:qwen-vl-max}
          temperature: 0.1
pdf:
  upload-dir: ./uploads
  image:
    dpi: 200
  analysis:
    threads: 3
    timeout-seconds: 120
paddleocr:
  service:
    url: http://localhost:5000/ocr
  token: <PaddleOCR Token>
```

## 关键技术决策

| 决策 | 原因 |
|------|------|
| Python 作为独立服务而非 ProcessBuilder | 更健壮、解耦、可独立扩展 |
| Spring AI OpenAI 兼容模式连接 Qwen | DashScope API 兼容 OpenAI 格式 |
| 多线程 ThreadPoolExecutor 而非 @Async | 精确控制线程池大小和队列 |
| SSE 而非 WebSocket | 单向推送足够，SSE 更轻量 |
| 超时 120s + 重试 1 次 | Qwen VL 大图处理可能超时 |
| `.properties` → `.yml` | 层次化配置更清晰 |
