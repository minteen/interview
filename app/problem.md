# AI 面试平台 - 核心技术实现难点与亮点分析

## 文档概述
本文档深入分析 AI 面试平台在七大核心技术领域的实现难点与亮点，包括：Tika 格式解析、大文件上传、文件去重、向量数据库、SSE 打字机效果、MapStruct DTO 映射、Redis Stream 异步任务、Redis + Lua 分布式限流。

---

## 1. Tika 格式解析技术

### 1.1 核心实现类
- **DocumentParseService**: `src/main/java/interview/guide/infrastructure/file/DocumentParseService.java`
- **TextCleaningService**: `src/main/java/interview/guide/infrastructure/file/TextCleaningService.java`
- **NoOpEmbeddedDocumentExtractor**: `src/main/java/interview/guide/infrastructure/file/NoOpEmbeddedDocumentExtractor.java`

### 1.2 实现难点

#### 难点一：多格式文档统一解析
**问题**：需要支持 PDF、DOCX、DOC、TXT、MD 等多种格式，每种格式的解析方式差异巨大。

**解决方案**：
- 使用 Apache Tika 的 `AutoDetectParser` 自动检测文件类型
- 采用显式 Parser + Context 方式，而非简单的 `Tika.parseToString()`
- 统一输入流处理入口，支持 `MultipartFile`、`byte[]`、`InputStream` 三种输入方式

```java
// DocumentParseService.java:94-139
private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
    AutoDetectParser parser = new AutoDetectParser();
    BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
    Metadata metadata = new Metadata();
    ParseContext context = new ParseContext();

    // 显式指定 Parser 到 Context（增强健壮性）
    context.set(Parser.class, parser);

    // 禁用嵌入文档解析（关键：避免提取图片引用和临时文件路径）
    context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

    // PDF 专用配置：关闭图片提取，按位置排序文本
    PDFParserConfig pdfConfig = new PDFParserConfig();
    pdfConfig.setExtractInlineImages(false);
    pdfConfig.setSortByPosition(true); // 按 x/y 坐标排序文本，改善多栏布局解析顺序
    context.set(PDFParserConfig.class, pdfConfig);

    parser.parse(inputStream, handler, metadata, context);
    return handler.toString();
}
```

#### 难点二：PDF 多栏布局解析顺序混乱
**问题**：PDF 是基于坐标的页面描述语言，文本提取时经常出现"跳行"或"列错乱"问题。

**解决方案**：
- 配置 `PDFParserConfig.setSortByPosition(true)`
- 按文本块的 x/y 坐标排序，而非 PDF 内部存储顺序
- 此配置对双栏、三栏简历文档的解析效果提升显著

#### 难点三：嵌入文档污染解析结果
**问题**：PDF、DOCX 常包含图片、附件等嵌入资源，Tika 默认会尝试解析这些资源，导致：
- 输出包含图片文件名（如 `image1.png`）
- 包含临时文件路径（如 `file:/tmp/apache-tika-xxx/embedded.doc`）
- 解析速度变慢

**解决方案**：
- 自定义 `NoOpEmbeddedDocumentExtractor`，完全跳过嵌入文档处理
- 在解析上下文（`ParseContext`）中注册该提取器
- 配合 `TextCleaningService` 的后处理，双重保障

#### 难点四：解析文本的语义去噪
**问题**：原始解析文本包含大量噪音数据：
- 控制字符、不可见字符
- 图片文件名、图片 URL
- 分隔线（`---`、`***`、`===`）
- 不规则换行和空白

**解决方案**：
- 预编译正则表达式（性能优化，避免重复编译）
- 分层清洗策略：语义去噪 → 格式规范化
- 整行匹配图片文件名，防止误删正文中的文件名字符串

```java
// TextCleaningService.java:80-105
public String cleanText(String text) {
    if (text == null || text.isBlank()) {
        return "";
    }

    String t = text;

    // ========== 第一层：语义去噪 ==========
    t = CONTROL_CHARS.matcher(t).replaceAll("");
    t = IMAGE_FILENAME_LINE.matcher(t).replaceAll("");  // 整行匹配
    t = IMAGE_URL.matcher(t).replaceAll("");
    t = FILE_URL.matcher(t).replaceAll("");
    t = SEPARATOR_LINE.matcher(t).replaceAll("");

    // ========== 第二层：格式规范化 ==========
    t = t.replace("\r\n", "\n").replace("\r", "\n");
    t = t.replaceAll("(?m)[ \t]+$", "");  // 保留空行，去除行尾空格
    t = t.replaceAll("\\n{3,}", "\n\n");  // 最多保留一个空行

    return t.strip();
}
```

### 1.3 亮点总结

| 亮点 | 说明 |
|------|------|
| 显式 Context 配置 | 不依赖 Tika 默认行为，完全掌控解析过程 |
| 嵌入文档隔离 | NoOpEmbeddedDocumentExtractor 从根源阻止噪音 |
| PDF 位置排序 | 解决多栏布局的解析顺序问题 |
| 分层清洗策略 | 语义级去噪 + 格式级规范化，双层保障 |
| 预编译正则 | 所有 Pattern 静态预编译，避免运行时开销 |
| 最大长度保护 | BodyContentHandler 限制 5MB，防止 OOM |

---

## 2. 大文件上传与文件去重

### 2.1 核心实现类
- **FileStorageService**: `src/main/java/interview/guide/infrastructure/file/FileStorageService.java`
- **FileHashService**: `src/main/java/interview/guide/infrastructure/file/FileHashService.java`
- **FileValidationService**: `src/main/java/interview/guide/infrastructure/file/FileValidationService.java`
- **ResumeUploadService**: `src/main/java/interview/guide/modules/resume/service/ResumeUploadService.java`

### 2.2 实现难点

#### 难点一：大文件内存溢出风险
**问题**：直接使用 `file.getBytes()` 会将整个文件加载到内存，100MB 文件直接消耗 100MB 堆内存。

**解决方案**：
- 哈希计算提供三种重载：`MultipartFile`、`byte[]`、`InputStream`
- 大文件场景使用流式哈希计算（8KB 缓冲区）
- 文件存储使用 S3 客户端的流式上传（RequestBody.fromInputStream）

```java
// FileHashService.java:63-76
public String calculateHash(InputStream inputStream) {
    try {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        byte[] buffer = new byte[BUFFER_SIZE];  // 8KB 缓冲区
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);  // 增量更新
        }
        return bytesToHex(digest.digest());
    } catch (NoSuchAlgorithmException | IOException e) {
        log.error("计算文件哈希失败: {}", e.getMessage());
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
    }
}
```

#### 难点二：文件名安全性问题
**问题**：
- 中文文件名在 S3 中可能出现编码问题
- 特殊字符（如空格、`/`、`\`、`?`）可能导致路径问题
- 文件名碰撞风险

**解决方案**：
- 汉字转拼音（使用 pinyin4j 库），大驼峰格式
- 白名单机制：只保留字母、数字、点、下划线、连字符
- 日期路径 + UUID 前缀，避免碰撞

```java
// FileStorageService.java:206-257
private String generateFileKey(String originalFilename, String prefix) {
    LocalDateTime now = LocalDateTime.now();
    String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
    String uuid = UUID.randomUUID().toString().substring(0, 8);
    String safeName = sanitizeFilename(originalFilename);
    return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
}

private String convertToPinyin(String input) {
    HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
    format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
    format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

    StringBuilder result = new StringBuilder();
    for (char ch : input.toCharArray()) {
        try {
            String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
            if (pinyins != null && pinyins.length > 0) {
                result.append(capitalize(pinyins[0]));  // 首字母大写（大驼峰）
            } else {
                result.append(sanitizeChar(ch));
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            result.append(sanitizeChar(ch));
        }
    }
    return result.toString();
}
```

#### 难点三：文件去重逻辑设计
**问题**：
- 如何判断文件是否重复？
- 重复文件应该复用历史分析结果，节省 AI 调用成本
- 哈希冲突如何处理？

**解决方案**：
- 使用 SHA-256 哈希作为文件唯一标识（ collision resistance）
- 数据库唯一索引约束 `file_hash` 字段
- 去重流程：计算哈希 → 查询数据库 → 存在则返回历史结果 → 不存在则继续处理

```java
// ResumeUploadService 中的去重逻辑（示意）
String fileHash = fileHashService.calculateHash(file);
Optional<ResumeEntity> existingResume = resumeRepository.findByFileHash(fileHash);
if (existingResume.isPresent()) {
    log.info("文件已存在，返回历史分析结果: fileHash={}", fileHash);
    return existingResume.get();  // 复用历史数据
}
```

#### 难点四：文件类型双重验证
**问题**：
- 仅靠文件名后缀验证不安全（可篡改）
- 仅靠 Content-Type 验证也不安全（可伪造）

**解决方案**：
- MIME 类型 + 扩展名双重验证
- 任一验证通过即可（提高兼容性）
- 白名单机制，不允许未知类型

```java
// FileValidationService.java:61-77
public void validateContentType(String contentType, String fileName,
                               Predicate<String> mimeTypeChecker,
                               Predicate<String> extensionChecker,
                               String errorMessage) {
    // 先检查 MIME 类型
    if (mimeTypeChecker.test(contentType)) {
        return;
    }

    // 如果 MIME 类型不支持，再检查文件扩展名
    if (fileName != null && extensionChecker.test(fileName)) {
        return;
    }

    throw new BusinessException(ErrorCode.BAD_REQUEST,
        errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
}
```

### 2.3 亮点总结

| 亮点 | 说明 |
|------|------|
| 流式哈希计算 | 8KB 缓冲区，支持大文件而不 OOM |
| 拼音化文件名 | 中文转拼音，避免 S3 编码问题 |
| UUID + 日期路径 | 三重防碰撞：UUID + 日期 + 原文件名 |
| SHA-256 去重 | 加密级哈希，数据库唯一索引双重保障 |
| 双重文件验证 | MIME + 扩展名，既安全又兼容 |
| 存储桶自动创建 | ensureBucketExists() 方法自动初始化 |

---

## 3. 向量数据库（pgvector + Spring AI）

### 3.1 核心实现类
- **KnowledgeBaseVectorService**: `src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- **KnowledgeBaseQueryService**: `src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- **VectorRepository**: `src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java`

### 3.2 实现难点

#### 难点一：向量化 API 批量限制
**问题**：阿里云 DashScope Embedding API 限制单次请求最多 10 个文本片段（batch size ≤ 10），超过则报错。

**解决方案**：
- 定义常量 `MAX_BATCH_SIZE = 10`
- 分批处理：向上取整计算批次数，循环发送
- 每批完成记录日志，便于监控进度

```java
// KnowledgeBaseVectorService.java:43-78
@Transactional
public void vectorizeAndStore(Long knowledgeBaseId, String content) {
    // 1. 先删除该知识库的旧向量数据
    deleteByKnowledgeBaseId(knowledgeBaseId);

    // 2. 将文本分块
    List<Document> chunks = textSplitter.apply(List.of(new Document(content)));

    // 3. 为每个 chunk 添加 metadata（知识库ID）
    chunks.forEach(chunk -> chunk.getMetadata().put("kb_id", knowledgeBaseId.toString()));

    // 4. 分批向量化并存储（阿里云 API 限制 batch size <= 10）
    int totalChunks = chunks.size();
    int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;  // 向上取整

    for (int i = 0; i < batchCount; i++) {
        int start = i * MAX_BATCH_SIZE;
        int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
        List<Document> batch = chunks.subList(start, end);
        vectorStore.add(batch);
    }
}
```

#### 难点二：多知识库过滤检索
**问题**：需要在向量检索时只返回指定知识库的片段，不能跨知识库污染结果。

**解决方案**：
- 使用 Spring AI 的 `SearchRequest.filterExpression()` 进行前置过滤
- 构建 `kb_id in ['1', '2', '3']` 过滤表达式
-  fallback 方案：前置过滤失败时，先检索再在内存中过滤

```java
// KnowledgeBaseVectorService.java:88-150
public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
    try {
        SearchRequest.Builder builder = SearchRequest.builder()
            .query(query)
            .topK(Math.max(topK, 1));

        if (minScore > 0) {
            builder.similarityThreshold(minScore);
        }

        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
        }

        return vectorStore.similaritySearch(builder.build());
    } catch (Exception e) {
        // 回退到本地过滤
        return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
    }
}

private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
    String values = knowledgeBaseIds.stream()
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .map(id -> "'" + id + "'")
        .collect(Collectors.joining(", "));
    return "kb_id in [" + values + "]";
}
```

#### 难点三：动态检索参数调整
**问题**：
- 短查询（如 "Java"）需要更多结果、更低阈值（召回优先）
- 长查询需要更少结果、更高阈值（精准优先）

**解决方案**：
- 根据查询长度动态计算 topK 和 minScore
- 紧凑长度（去除空白字符后）≤ 4：topK=20, minScore=0.18
- 紧凑长度 ≤ 12：topK=12, minScore=0.28
- 紧凑长度 > 12：topK=8, minScore=0.28

```java
// KnowledgeBaseQueryService.java:274-283
private SearchParams resolveSearchParams(String question) {
    int compactLength = question.replaceAll("\\s+", "").length();
    if (compactLength <= shortQueryLength) {
        return new SearchParams(topkShort, minScoreShort);
    }
    if (compactLength <= 12) {
        return new SearchParams(topkMedium, minScoreDefault);
    }
    return new SearchParams(topkLong, minScoreDefault);
}
```

#### 难点四：短查询命中验证
**问题**：短 token 查询（如 "Redis"）经常检索到语义不相关但向量相似的片段，导致 AI 生成"信息不足"的长篇大论。

**解决方案**：
- 对短 token 查询增加关键词命中确认
- 检索结果中必须包含查询 token（大小写不敏感）
- 否则视为无有效结果，直接返回"未找到相关信息"

```java
// KnowledgeBaseQueryService.java:313-333
private boolean hasEffectiveHit(String question, List<Document> docs) {
    if (docs == null || docs.isEmpty()) {
        return false;
    }

    String normalized = normalizeQuestion(question);
    if (!isShortTokenQuery(normalized)) {
        return true;  // 非短查询直接放行
    }

    // 短查询必须确认关键词命中
    String loweredToken = normalized.toLowerCase();
    for (Document doc : docs) {
        String text = doc.getText();
        if (text != null && text.toLowerCase().contains(loweredToken)) {
            return true;
        }
    }

    return false;  // 未命中关键词
}
```

#### 难点五：旧向量数据清理
**问题**：重新向量化时，旧向量数据必须清理干净，否则会出现新旧数据混杂。

**解决方案**：
- 自定义 VectorRepository，使用原生 SQL 删除
- `@Modifying` + `@Transactional` 保证原子性
- metadata 是 JSONB 类型，使用 `metadata->>'kb_id'` 提取字段

```java
// VectorRepository.java
@Modifying
@Transactional
@Query(value = "DELETE FROM vector_store WHERE metadata->>'kb_id' = :kbId", nativeQuery = true)
void deleteByKnowledgeBaseId(@Param("kbId") Long kbId);
```

#### 难点六：Query Rewrite 查询优化
**问题**：用户原始提问可能口语化、不完整，直接进行向量检索效果差。

**解决方案**：
- 使用 AI 对原始问题进行改写（Query Rewrite）
- 同时尝试改写后的问题和原始问题
- 取第一个有有效命中的查询结果

```java
// KnowledgeBaseQueryService.java:240-249
private QueryContext buildQueryContext(String originalQuestion) {
    String normalizedQuestion = normalizeQuestion(originalQuestion);
    String rewrittenQuestion = rewriteQuestion(normalizedQuestion);
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(rewrittenQuestion);  // 先试改写后的
    candidates.add(normalizedQuestion);   // 再试原始的

    SearchParams searchParams = resolveSearchParams(normalizedQuestion);
    return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
}

private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
    for (String candidateQuery : queryContext.candidateQueries()) {
        List<Document> docs = vectorService.similaritySearch(
            candidateQuery, knowledgeBaseIds,
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore()
        );
        if (hasEffectiveHit(candidateQuery, docs)) {
            return docs;  // 找到有效命中立即返回
        }
    }
    return List.of();
}
```

### 3.3 亮点总结

| 亮点 | 说明 |
|------|------|
| 分批向量化 | 适配 API 限制，batch size 动态计算 |
| 前置过滤 + Fallback | 优先服务器端过滤，失败则内存过滤兜底 |
| 动态检索参数 | 根据查询长度自动调整 topK/minScore |
| 短查询验证 | 避免弱相关片段污染 AI 生成结果 |
| Query Rewrite | 改写用户问题，提升检索质量 |
| 候选查询队列 | 改写查询 + 原始查询，双重尝试 |
| JSONB 元数据 | pgvector 原生支持，过滤性能优异 |
| TokenTextSplitter | 基于 Token 而非字符分块，语义更完整 |

---

## 4. SSE 打字机效果实现

### 4.1 核心实现类
- **RagChatController**: `src/main/java/interview/guide/modules/knowledgebase/RagChatController.java`
- **KnowledgeBaseController**: `src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java`
- **KnowledgeBaseQueryService**: `src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`

### 4.2 实现难点

#### 难点一：流式输出的 SSE 格式封装
**问题**：
- 原始 Flux<String> 不能直接作为 SSE 响应
- 需要包装为 `ServerSentEvent<String>`
- 换行符会破坏 SSE 格式（`data:` 后换行表示结束）

**解决方案**：
- 使用 `ServerSentEvent.builder()` 构建标准 SSE 事件
- 转义换行符：`\n` → `\\n`，`\r` → `\\r`
- 前端接收后再还原换行

```java
// RagChatController.java:100-134
@PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
             produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> sendMessageStream(
        @PathVariable Long sessionId,
        @Valid @RequestBody SendMessageRequest request) {

    // 1. 准备消息（保存用户消息，创建 AI 消息占位）
    Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

    // 2. 获取流式响应
    StringBuilder fullContent = new StringBuilder();

    return sessionService.getStreamAnswer(sessionId, request.question())
        .doOnNext(fullContent::append)
        // 使用 ServerSentEvent 包装，转义换行符避免破坏 SSE 格式
        .map(chunk -> ServerSentEvent.<String>builder()
            .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
            .build())
        .doOnComplete(() -> {
            // 3. 流式完成后更新消息内容
            sessionService.completeStreamMessage(messageId, fullContent.toString());
        })
        .doOnError(e -> {
            // 错误时也保存已接收的内容
            String content = !fullContent.isEmpty()
                ? fullContent.toString()
                : "【错误】回答生成失败：" + e.getMessage();
            sessionService.completeStreamMessage(messageId, content);
        });
}
```

#### 难点二：流式内容的探测窗口归一化
**问题**：AI 经常先输出"抱歉，知识库中没有找到相关信息..."，然后才开始真正回答；或者完全生成"无信息"长篇模板。

**解决方案**：
- 实现探测窗口（前 120 字符）
- 先缓冲内容，不立即输出
- 探测到"无信息"模式：立即替换为固定模板并结束
- 未探测到：释放缓冲并继续实时透传

```java
// KnowledgeBaseQueryService.java:367-420
private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
    return Flux.create(sink -> {
        StringBuilder probeBuffer = new StringBuilder();
        AtomicBoolean passthrough = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        final Disposable[] disposableRef = new Disposable[1];

        disposableRef[0] = rawFlux.subscribe(
            chunk -> {
                if (completed.get() || sink.isCancelled()) {
                    return;
                }
                if (passthrough.get()) {
                    sink.next(chunk);  // 探测完成，直接透传
                    return;
                }

                probeBuffer.append(chunk);
                String probeText = probeBuffer.toString();

                // 检查是否是"无信息"模式
                if (isNoResultLike(probeText)) {
                    completed.set(true);
                    sink.next(NO_RESULT_RESPONSE);  // 替换为固定模板
                    sink.complete();
                    disposableRef[0].dispose();
                    return;
                }

                // 超过探测窗口，开始透传
                if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                    passthrough.set(true);
                    sink.next(probeText);
                    probeBuffer.setLength(0);
                }
            },
            sink::error,
            () -> {
                if (!completed.get() && !passthrough.get()) {
                    sink.next(normalizeAnswer(probeBuffer.toString()));
                }
                sink.complete();
            }
        );

        sink.onCancel(() -> {
            if (disposableRef[0] != null) {
                disposableRef[0].dispose();
            }
        });
    });
}

private boolean isNoResultLike(String text) {
    return text.contains("没有找到相关信息")
        || text.contains("未检索到相关信息")
        || text.contains("信息不足")
        || text.contains("超出知识库范围")
        || text.contains("无法根据提供内容回答");
}
```

#### 难点三：消息持久化与流式输出的协调
**问题**：
- 用户消息需要立即保存（防止刷新丢失）
- AI 消息需要先创建占位，流式完成后再更新
- 流式失败时需要保存已接收的部分内容

**解决方案**：
- 三阶段处理：
  1. **prepareStreamMessage**: 保存用户消息 + 创建 AI 消息占位
  2. **流式输出**: 实时发送给前端
  3. **completeStreamMessage**: 流式完成/失败后更新 AI 消息

```java
// RagChatController.java:109-110
Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

// RagChatController.java:121-124
.doOnComplete(() -> {
    sessionService.completeStreamMessage(messageId, fullContent.toString());
})
.doOnError(e -> {
    String content = !fullContent.isEmpty()
        ? fullContent.toString()
        : "【错误】回答生成失败：" + e.getMessage();
    sessionService.completeStreamMessage(messageId, content);
})
```

#### 难点四：虚拟线程与 Reactor 的兼容性
**问题**：Java 21 虚拟线程与 Reactor 调度器如何配合？

**解决方案**：
- 日志中记录当前线程信息（调试用）
- 确认虚拟线程是否被正确使用
- 不强制指定调度器，让 Spring AI 内部处理

```java
// KnowledgeBaseController.java:96-97
log.debug("收到知识库流式查询请求: kbIds={}, question={}, 线程: {} (虚拟线程: {})",
    request.knowledgeBaseIds(), request.question(),
    Thread.currentThread(), Thread.currentThread().isVirtual());
```

### 4.3 亮点总结

| 亮点 | 说明 |
|------|------|
| ServerSentEvent 封装 | 标准 SSE 格式，兼容浏览器 EventSource |
| 换行符转义 | 防止换行破坏 SSE 协议帧 |
| 探测窗口归一化 | 前 120 字符探测，快速识别"无信息"模式 |
| 三阶段持久化 | prepare → stream → complete，保证消息不丢失 |
| 错误处理保存 | 即使失败也保存已接收的部分内容 |
| Flux.create 手动控制 | 完全掌控背压和生命周期 |
| 虚拟线程日志 | 可观察性设计，便于调试 |

---

## 5. MapStruct DTO 对象映射

### 5.1 核心实现类
- **ResumeMapper**: `src/main/java/interview/guide/infrastructure/mapper/ResumeMapper.java`
- **InterviewMapper**: `src/main/java/interview/guide/infrastructure/mapper/InterviewMapper.java`
- **KnowledgeBaseMapper**: `src/main/java/interview/guide/infrastructure/mapper/KnowledgeBaseMapper.java`
- **RagChatMapper**: `src/main/java/interview/guide/infrastructure/mapper/RagChatMapper.java`

### 5.2 实现难点

#### 难点一：JSON 字段的特殊处理
**问题**：实体中 `strengthsJson`、`suggestionsJson` 等字段存储 JSON 字符串，DTO 中需要是 `List<String>`、`List<Object>`。

**解决方案**：
- MapStruct 不直接处理 JSON ↔ 对象转换
- 标记这些字段为 `ignore = true`
- 在 Service 层手动处理 JSON 序列化/反序列化
- Mapper 提供带额外参数的方法，Service 层传入解析后的 List

```java
// ResumeMapper.java:84-88
@Mapping(target = "strengths", source = "strengths")
@Mapping(target = "suggestions", source = "suggestions")
ResumeDetailDTO.AnalysisHistoryDTO toAnalysisHistoryDTO(
    ResumeAnalysisEntity entity,
    List<String> strengths,        // 手动传入
    List<Object> suggestions        // 手动传入
);

// Service 层调用示例
List<String> strengths = objectMapper.readValue(entity.getStrengthsJson(), new TypeReference<List<String>>() {});
List<Object> suggestions = objectMapper.readValue(entity.getSuggestionsJson(), new TypeReference<List<Object>>() {});
resumeMapper.toAnalysisHistoryDTO(entity, strengths, suggestions);
```

#### 难点二：null 值的默认值处理
**问题**：数据库中分数字段可能为 null，DTO 中需要显示为 0。

**解决方案**：
- 使用 `@Named` 自定义转换器
- `nullToZero` 方法处理 null → 0
- `@Mapping(qualifiedByName = "nullToZero")` 引用该转换器

```java
// ResumeMapper.java:26-31
@Mapping(target = "contentScore", source = "contentScore", qualifiedByName = "nullToZero")
@Mapping(target = "structureScore", source = "structureScore", qualifiedByName = "nullToZero")
@Mapping(target = "skillMatchScore", source = "skillMatchScore", qualifiedByName = "nullToZero")
ResumeAnalysisResponse.ScoreDetail toScoreDetail(ResumeAnalysisEntity entity);

@Named("nullToZero")
default int nullToZero(Integer value) {
    return value != null ? value : 0;
}
```

#### 难点三：需要额外参数的映射
**问题**：`ResumeListItemDTO` 需要 `latestScore`、`lastAnalyzedAt`、`interviewCount` 三个字段，这些不在 `ResumeEntity` 中。

**解决方案**：
- 使用 `default` 方法自定义映射逻辑
- 额外参数作为方法入参
- 手动组装 DTO

```java
// ResumeMapper.java:39-55
default ResumeListItemDTO toListItemDTO(
    ResumeEntity resume,
    Integer latestScore,
    java.time.LocalDateTime lastAnalyzedAt,
    Integer interviewCount
) {
    return new ResumeListItemDTO(
        resume.getId(),
        resume.getOriginalFilename(),
        resume.getFileSize(),
        resume.getUploadedAt(),
        resume.getAccessCount(),
        latestScore,        // 额外参数
        lastAnalyzedAt,     // 额外参数
        interviewCount       // 额外参数
    );
}
```

#### 难点四：批量映射 + Function 抽取器
**问题**：批量转换时，每个实体都需要从 JSON 解析 strengths/suggestions，代码重复。

**解决方案**：
- 使用 `default` 方法 + Function 参数
- Service 层传入抽取器函数
- Mapper 内部循环调用抽取器

```java
// ResumeMapper.java:93-101
default List<ResumeDetailDTO.AnalysisHistoryDTO> toAnalysisHistoryDTOList(
    List<ResumeAnalysisEntity> entities,
    java.util.function.Function<ResumeAnalysisEntity, List<String>> strengthsExtractor,
    java.util.function.Function<ResumeAnalysisEntity, List<Object>> suggestionsExtractor
) {
    return entities.stream()
        .map(e -> toAnalysisHistoryDTO(
            e,
            strengthsExtractor.apply(e),   // 调用抽取器
            suggestionsExtractor.apply(e)   // 调用抽取器
        ))
        .toList();
}

// Service 层调用示例
resumeMapper.toAnalysisHistoryDTOList(
    analyses,
    e -> parseStrengths(e.getStrengthsJson()),  // 传入抽取器
    e -> parseSuggestions(e.getSuggestionsJson()) // 传入抽取器
);
```

#### 难点五：更新现有实体（部分字段）
**问题**：创建实体和更新实体的映射逻辑相似，但更新时不应覆盖 ID、创建时间等字段。

**解决方案**：
- 单独定义 `updateAnalysisEntity` 方法
- 使用 `@MappingTarget` 注解目标实体
- 忽略不应更新的字段

```java
// ResumeMapper.java:124-134
@Mapping(target = "id", ignore = true)
@Mapping(target = "resume", ignore = true)
@Mapping(target = "strengthsJson", ignore = true)
@Mapping(target = "suggestionsJson", ignore = true)
@Mapping(target = "analyzedAt", ignore = true)
@Mapping(target = "contentScore", source = "scoreDetail.contentScore")
void updateAnalysisEntity(ResumeAnalysisResponse response, @MappingTarget ResumeAnalysisEntity entity);
```

### 5.3 亮点总结

| 亮点 | 说明 |
|------|------|
| JSON 字段分层处理 | Mapper 忽略 JSON 字段，Service 层手动处理 |
| @Named 转换器 | 可复用的 null→0 等通用转换 |
| default 方法扩展 | MapStruct 接口中定义自定义逻辑 |
| Function 抽取器模式 | 批量映射时传入外部依赖逻辑 |
| @MappingTarget 更新 | 支持部分字段更新现有实体 |
| componentModel = "spring" | 生成的 Mapper 作为 Spring Bean 注入 |
| unmappedTargetPolicy = IGNORE | 未映射字段忽略，避免编译报错 |

---

## 6. Redis Stream 异步任务处理

### 6.1 核心实现类
- **AbstractStreamProducer**: `src/main/java/interview/guide/common/async/AbstractStreamProducer.java`
- **AbstractStreamConsumer**: `src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- **RedisService**: `src/main/java/interview/guide/infrastructure/redis/RedisService.java`
- **AnalyzeStreamConsumer/Producer**: 简历分析
- **EvaluateStreamConsumer/Producer**: 面试评估
- **VectorizeStreamConsumer/Producer**: 知识库向量化

### 6.2 实现难点

#### 难点一：模板方法设计（生产者）
**问题**：三种异步任务（简历分析、向量化、面试评估）的生产者逻辑高度相似，代码重复。

**解决方案**：
- 抽象 `AbstractStreamProducer<T>` 基类
- 定义抽象方法，子类实现差异部分
- `sendTask()` 为模板方法，封装公共逻辑

```java
// AbstractStreamProducer.java:22-36
protected void sendTask(T payload) {
    try {
        String messageId = redisService.streamAdd(
            streamKey(),
            buildMessage(payload),
            AsyncTaskStreamConstants.STREAM_MAX_LEN
        );
        log.info("{}任务已发送到Stream: {}, messageId={}",
            taskDisplayName(), payloadIdentifier(payload), messageId);
    } catch (Exception e) {
        log.error("发送{}任务失败: {}, error={}",
            taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
        onSendFailed(payload, "任务入队失败: " + e.getMessage());
    }
}

// 子类需要实现的抽象方法
protected abstract String taskDisplayName();           // 任务显示名称
protected abstract String streamKey();                  // Stream Key
protected abstract Map<String, String> buildMessage(T payload);  // 构建消息
protected abstract String payloadIdentifier(T payload); // 负载标识
protected abstract void onSendFailed(T payload, String error);  // 发送失败回调
```

#### 难点二：模板方法设计（消费者）
**问题**：消费者逻辑更复杂，包含生命周期管理、消费循环、ACK、重试、状态机更新等。

**解决方案**：
- 抽象 `AbstractStreamConsumer<T>` 基类
- 封装完整的消费生命周期
- 定义 12 个抽象方法，子类填充业务逻辑

```java
// AbstractStreamConsumer.java:85-113
private void processMessage(StreamMessageId messageId, Map<String, String> data) {
    T payload = parsePayload(messageId, data);
    if (payload == null) {
        ackMessage(messageId);
        return;
    }

    int retryCount = parseRetryCount(data);
    log.info("开始处理{}任务: {}, messageId={}, retryCount={}",
        taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

    try {
        markProcessing(payload);          // 状态 → PROCESSING
        processBusiness(payload);         // 执行业务逻辑
        markCompleted(payload);           // 状态 → COMPLETED
        ackMessage(messageId);            // ACK
        log.info("{}任务完成: {}", taskDisplayName(), payloadIdentifier(payload));
    } catch (Exception e) {
        log.error("{}任务失败: {}, error={}", taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
        if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
            retryMessage(payload, retryCount + 1);  // 重新入队
        } else {
            markFailed(payload, truncateError(...));  // 状态 → FAILED
        }
        ackMessage(messageId);  // 无论成功失败都 ACK
    }
}
```

#### 难点三：消费者生命周期管理
**问题**：
- 应用启动时自动启动消费者
- 应用关闭时优雅停止消费者
- 守护线程避免阻止 JVM 退出

**解决方案**：
- `@PostConstruct` 初始化：创建消费者组、启动单线程池
- `@PreDestroy` 优雅关闭：设置 running 标志、关闭线程池
- `setDaemon(true)` 标记为守护线程

```java
// AbstractStreamConsumer.java:33-62
@PostConstruct
public void init() {
    this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);

    try {
        redisService.createStreamGroup(streamKey(), groupName());
        log.info("Redis Stream 消费者组已创建或已存在: {}", groupName());
    } catch (Exception e) {
        log.warn("创建消费者组时发生异常（可能已存在）: {}", e.getMessage());
    }

    this.executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, threadName());
        t.setDaemon(true);  // 守护线程
        return t;
    });

    running.set(true);
    executorService.submit(this::consumeLoop);
    log.info("{}消费者已启动: consumerName={}", taskDisplayName(), consumerName);
}

@PreDestroy
public void shutdown() {
    running.set(false);
    if (executorService != null) {
        executorService.shutdown();
    }
    log.info("{}消费者已关闭: consumerName={}", taskDisplayName(), consumerName);
}
```

#### 难点四：Redis Stream 阻塞读取
**问题**：客户端轮询（while + sleep）浪费 CPU，且延迟高。

**解决方案**：
- 使用 Redis Stream 的 `BLOCK` 参数
- Redisson 的 `StreamReadGroupArgs.timeout(Duration)`
- 服务端等待消息，无消息时阻塞，有消息时立即返回

```java
// RedisService.java:222-250
public boolean streamConsumeMessages(
        String streamKey,
        String groupName,
        String consumerName,
        int count,
        long blockTimeoutMs,
        StreamMessageProcessor processor) {

    RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);

    // 使用阻塞读取，让 Redis 服务端等待消息
    Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
        groupName,
        consumerName,
        StreamReadGroupArgs.neverDelivered()
            .count(count)
            .timeout(Duration.ofMillis(blockTimeoutMs))  // 阻塞超时
    );

    if (messages == null || messages.isEmpty()) {
        return false;
    }

    for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
        processor.process(entry.getKey(), entry.getValue());
    }

    return true;
}
```

#### 难点五：重试机制设计
**问题**：
- 任务失败需要自动重试
- 最大重试次数限制（3 次）
- 重试次数需要传递

**解决方案**：
- 消息中包含 `retryCount` 字段
- 失败时检查 `retryCount < MAX_RETRY_COUNT`
- 满足则重新入队（retryCount + 1）
- 不满足则标记为 FAILED

```java
// AbstractStreamConsumer.java:102-111
} catch (Exception e) {
    log.error("{}任务失败: {}, error={}", taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
    if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
        retryMessage(payload, retryCount + 1);  // 重新入队
    } else {
        markFailed(payload, truncateError(
            taskDisplayName() + "失败(已重试" + retryCount + "次): " + e.getMessage()
        ));
    }
    ackMessage(messageId);
}

// AnalyzeStreamConsumer.java:118-139
protected void retryMessage(AnalyzePayload payload, int retryCount) {
    Long resumeId = payload.resumeId();
    String content = payload.content();
    try {
        Map<String, String> message = Map.of(
            AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, content,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );

        redisService().streamAdd(
            AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
            message,
            AsyncTaskStreamConstants.STREAM_MAX_LEN
        );
        log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);
    } catch (Exception e) {
        log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
        updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
    }
}
```

#### 难点六：Stream 长度限制
**问题**：Stream 无限增长会占用大量内存。

**解决方案**：
- 发送消息时指定 `maxLen`
- 使用 `trimNonStrict()` 非严格裁剪（性能更好）
- 常量 `STREAM_MAX_LEN = 10000`

```java
// AbstractStreamProducer.java:24-28
String messageId = redisService.streamAdd(
    streamKey(),
    buildMessage(payload),
    AsyncTaskStreamConstants.STREAM_MAX_LEN
);

// RedisService.java:283-292
public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
    RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
    StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
    if (maxLen > 0) {
        args.trimNonStrict().maxLen(maxLen);  // 非严格裁剪
    }
    StreamMessageId messageId = stream.add(args);
    return messageId.toString();
}
```

#### 难点七：消费者组创建幂等
**问题**：重复创建消费者组会报错 `BUSYGROUP`。

**解决方案**：
- 捕获异常，检查错误信息是否包含 `BUSYGROUP`
- 包含则忽略（组已存在）
- 不包含则打印警告

```java
// AbstractStreamConsumer.java:37-42
try {
    redisService.createStreamGroup(streamKey(), groupName());
    log.info("Redis Stream 消费者组已创建或已存在: {}", groupName());
} catch (Exception e) {
    log.warn("创建消费者组时发生异常（可能已存在）: {}", e.getMessage());
}

// RedisService.java:255-266
public void createStreamGroup(String streamKey, String groupName) {
    RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
    try {
        stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
        log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName);
    } catch (Exception e) {
        // 组已存在，忽略
        if (!e.getMessage().contains("BUSYGROUP")) {
            log.warn("创建消费者组失败: {}", e.getMessage());
        }
    }
}
```

### 6.3 亮点总结

| 亮点 | 说明 |
|------|------|
| 双模板方法模式 | Producer 和 Consumer 各有抽象基类 |
| 12 个扩展点 | Consumer 定义 12 个抽象方法，完全控制业务逻辑 |
| 状态机集成 | markProcessing/markCompleted/markFailed |
| 内置重试机制 | retryCount 传递 + MAX_RETRY_COUNT 限制 |
| 阻塞式读取 | BLOCK 参数，服务端等待，低 CPU 低延迟 |
| Stream 长度限制 | trimNonStrict 裁剪，控制内存占用 |
| 幂等消费者组 | BUSYGROUP 异常忽略，支持重复启动 |
| 优雅关闭 | @PreDestroy + AtomicBoolean 标志 |
| 守护线程 | setDaemon(true)，不阻止 JVM 退出 |

---

## 7. Redis + Lua 多维度分布式限流

### 7.1 核心实现类
- **RateLimit**: `src/main/java/interview/guide/common/annotation/RateLimit.java`
- **RateLimitAspect**: `src/main/java/interview/guide/common/aspect/RateLimitAspect.java`
- **rate_limit.lua**: `src/main/resources/scripts/rate_limit.lua`

### 7.2 实现难点

#### 难点一：原子性保障（多维度限流）
**问题**：
- 多维度限流（如同时全局限流 + IP限流）需要原子检查
- 检查维度 A、维度 B 都通过后，再同时扣减两者
- 如果检查后扣减前有其他请求通过，会超卖

**解决方案**：
- 使用 Lua 脚本，整个过程原子执行
- 两阶段设计：
  - **预检查阶段**：遍历所有维度，检查令牌是否充足
  - **扣减阶段**：所有维度通过后，才执行扣减
- 任一维度检查失败，直接返回 0

```lua
-- rate_limit.lua:19-82
-- 第一阶段：预检查阶段 - 检查所有维度是否有足够令牌
for i, key in ipairs(KEYS) do
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"

    -- 回收过期令牌
    local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
    if #expired_values > 0 then
        -- ... 回收过期配额 ...
    end

    -- 核心检查：当前可用令牌是否足够
    local current_val = tonumber(redis.call("get", value_key) or max_tokens)
    if current_val < permits then
        -- 任何一个维度配额不足，直接返回失败
        return 0
    end
end

-- 第二阶段：扣减阶段 - 只有所有维度都通过后才执行
for i, key in ipairs(KEYS) do
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"

    -- 记录本次令牌分配（格式：request_id:permits）
    local permit_record = request_id .. ":" .. permits
    redis.call("zadd", permits_key, now_ms, permit_record)

    -- 扣减令牌
    local current_v = tonumber(redis.call("get", value_key) or max_tokens)
    redis.call("set", value_key, current_v - permits)

    -- 设置过期时间
    local expire_time = math.ceil(interval * 2 / 1000)
    redis.call("expire", value_key, expire_time)
    redis.call("expire", permits_key, expire_time)
end

-- 成功获取所有维度的令牌
return 1
```

#### 难点二：滑动时间窗口实现
**问题**：
- 固定窗口限流有"突刺问题"（窗口边界双倍流量）
- 需要真正的滑动窗口，精确到毫秒

**解决方案**：
- 使用 Sorted Set 存储每次令牌分配记录
- Score = 分配时间戳（毫秒）
- Member = `request_id:permits`
- 每次请求先回收 `now - interval` 之前的记录
- 回收的配额加回 `value_key`

```lua
-- rate_limit.lua:30-51
-- 回收过期令牌
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
if #expired_values > 0 then
    local expired_count = 0
    for _, v in ipairs(expired_values) do
        -- 解析：使用更高效的模式匹配
        local p = tonumber(string.match(v, ":(%d+)$"))
        if p then
            expired_count = expired_count + p
        end
    end

    -- 删除过期记录
    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)

    -- 回收配额
    if expired_count > 0 then
        local curr_v = tonumber(redis.call("get", value_key) or max_tokens)
        local next_v = math.min(max_tokens, curr_v + expired_count)
        redis.call("set", value_key, next_v)
    end
end
```

#### 难点三：Redis Cluster 兼容性（Hash Tag）
**问题**：
- 多维度限流需要多个 Key（如 `ratelimit:{X}:global`、`ratelimit:{X}:ip:1.2.3.4`）
- Redis Cluster 模式下，不同 Key 可能落在不同 Slot
- Lua 脚本要求所有 Key 必须在同一个 Slot

**解决方案**：
- 使用 Hash Tag `{className:methodName}` 包裹公共部分
- 确保所有相关 Key 有相同的 Hash Tag
- Redis Cluster 根据 `{}` 内的内容计算 Slot
- 所有 Key 落在同一 Slot，Lua 脚本正常执行

```java
// RateLimitAspect.java:156-172
private List<String> generateKeys(String className, String methodName, RateLimit.Dimension[] dimensions) {
    List<String> keys = new ArrayList<>();
    // 使用 {} 包含类名和方法名作为 Hash Tag
    // 确保该方法的所有限流 Key 落在同一个 Redis Slot
    String hashTag = "{" + className + ":" + methodName + "}";
    String keyPrefix = "ratelimit:" + hashTag;

    for (RateLimit.Dimension dimension : dimensions) {
        switch (dimension) {
            case GLOBAL -> keys.add(keyPrefix + ":global");
            case IP -> keys.add(keyPrefix + ":ip:" + getClientIp());
            case USER -> keys.add(keyPrefix + ":user:" + getCurrentUserId());
        }
    }

    return keys;
}
```

#### 难点四：Lua 脚本预加载（SHA1 缓存）
**问题**：每次发送完整 Lua 脚本网络开销大。

**解决方案**：
- `@PostConstruct` 时调用 `scriptLoad()` 预加载脚本
- Redis 返回 SHA1 哈希值
- 后续调用使用 `evalSha()` 发送 SHA1 而非完整脚本
- 节省带宽，提高性能

```java
// RateLimitAspect.java:54-61
@jakarta.annotation.PostConstruct
public void init() {
    this.luaScriptSha = redissonClient.getScript(StringCodec.INSTANCE).scriptLoad(LUA_SCRIPT);
    log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
}

// 调用时使用 evalSha
Object resultObj = script.evalSha(
    RScript.Mode.READ_WRITE,
    luaScriptSha,          // SHA1 而非完整脚本
    RScript.ReturnType.VALUE,
    keysList,
    args
);
```

#### 难点五：客户端 IP 真实获取（多层代理）
**问题**：
- 服务部署在 Nginx/Ingress 后面
- `request.getRemoteAddr()` 只能拿到代理 IP
- 需要从 `X-Forwarded-For` 等头获取真实 IP

**解决方案**：
- 按优先级检查多个 Header：
  1. `X-Forwarded-For`（取第一个非 unknown）
  2. `X-Real-IP`
  3. `Proxy-Client-IP`
  4. `WL-Proxy-Client-IP`
  5. 最后 fallback 到 `getRemoteAddr()`
- 多个 IP 时取第一个（最原始客户端）

```java
// RateLimitAspect.java:236-268
private String getClientIp() {
    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
        return "unknown";
    }

    HttpServletRequest request = attributes.getRequest();
    String ip = request.getHeader("X-Forwarded-For");

    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getHeader("X-Real-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getHeader("Proxy-Client-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getHeader("WL-Proxy-Client-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getRemoteAddr();
    }

    // 处理多个 IP 的情况（X-Forwarded-For 可能包含多个 IP）
    if (ip != null && ip.contains(",")) {
        ip = ip.split(",")[0].trim();
    }

    return ip != null ? ip : "unknown";
}
```

#### 难点六：降级方法支持
**问题**：限流触发时不一定想抛异常，可能想执行降级逻辑。

**解决方案**：
- `@RateLimit(fallback = "fallbackMethod")` 指定降级方法名
- 反射查找降级方法：
  - 优先查找同参数列表的方法
  - 找不到则查找无参方法
- 匹配到则调用，否则抛异常

```java
// RateLimitAspect.java:177-206
private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint, RateLimit rateLimit, List<String> keys)
        throws Throwable {
    // 如果配置了降级方法，则调用降级方法
    if (rateLimit.fallback() != null && !rateLimit.fallback().isEmpty()) {
        try {
            Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
            if (fallbackMethod != null) {
                // 如果降级方法有参数，传入原方法的参数
                if (fallbackMethod.getParameterCount() > 0) {
                    return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
                } else {
                    return fallbackMethod.invoke(joinPoint.getTarget());
                }
            }
        } catch (Exception e) {
            log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
        }
    }

    // 没有降级方法或降级失败，抛出限流异常
    throw new RateLimitExceededException("请求过于频繁，请稍后再试");
}

// RateLimitAspect.java:212-234
private Method findFallbackMethod(ProceedingJoinPoint joinPoint, String fallbackName) {
    Class<?> targetClass = joinPoint.getTarget().getClass();
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Class<?>[] parameterTypes = signature.getParameterTypes();

    try {
        // 1. 尝试查找同参数列表的方法
        Method method = targetClass.getDeclaredMethod(fallbackName, parameterTypes);
        method.setAccessible(true);
        return method;
    } catch (NoSuchMethodException e) {
        // 2. 尝试查找无参方法
        try {
            Method method = targetClass.getDeclaredMethod(fallbackName);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            log.warn("未找到降级方法: {}.{}", targetClass.getSimpleName(), fallbackName);
            return null;
        }
    }
}
```

#### 难点七：时间单位灵活配置
**问题**：需要支持毫秒、秒、分钟、小时、天等多种时间单位。

**解决方案**：
- 注解中定义 `TimeUnit` 枚举
- `calculateIntervalMs()` 统一转换为毫秒
- Lua 脚本只处理毫秒（时间戳天然是毫秒）

```java
// RateLimit.java:93-98
enum TimeUnit {
    MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS
}

// RateLimitAspect.java:116-124
private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
    return switch (unit) {
        case MILLISECONDS -> interval;
        case SECONDS -> interval * 1000;
        case MINUTES -> interval * 60 * 1000;
        case HOURS -> interval * 3600 * 1000;
        case DAYS -> interval * 86400 * 1000;
    };
}
```

### 7.3 使用示例

```java
// KnowledgeBaseController.java:84-88
@PostMapping("/api/knowledgebase/query")
@RateLimit(dimensions = {RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP}, count = 10)
public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
    return Result.success(queryService.queryKnowledgeBase(request));
}

// KnowledgeBaseController.java:93-99
@PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RateLimit(dimensions = {RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP}, count = 5)
public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
    return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
}
```

### 7.4 亮点总结

| 亮点 | 说明 |
|------|------|
| 两阶段原子操作 | 预检查 → 扣减，Lua 脚本原子执行 |
| 滑动时间窗口 | ZSet 存储记录，毫秒级精度 |
| 过期令牌回收 | 自动回收并加回配额，无内存泄漏 |
| Hash Tag 设计 | `{className:methodName}` 兼容 Redis Cluster |
| 脚本预加载 | evalSha 节省带宽，提升性能 |
| 多维度组合 | GLOBAL / IP / USER 可任意组合 |
| 真实 IP 获取 | 多层代理支持，5 个 Header 优先级 |
| 降级方法支持 | 同参数 / 无参方法自动适配 |
| 灵活时间单位 | 毫秒 ~ 天自由配置 |
| 注解式使用 | @RateLimit 一行代码，零侵入 |

---

## 总结

该项目在七大技术领域的实现展现出极高的工程质量：

| 技术领域 | 核心亮点 |
|---------|---------|
| **Tika 格式解析** | 嵌入文档隔离、PDF 位置排序、分层清洗策略 |
| **文件处理** | 流式哈希、拼音化文件名、SHA-256 去重、双重验证 |
| **向量数据库** | 分批向量化、动态检索参数、Query Rewrite、短查询验证 |
| **SSE 打字机** | 探测窗口归一化、三阶段持久化、换行符转义 |
| **MapStruct 映射** | JSON 分层处理、@Named 转换器、Function 抽取器 |
| **Redis Stream** | 双模板方法、状态机集成、阻塞读取、内置重试 |
| **Redis + Lua 限流** | 两阶段原子、滑动窗口、Hash Tag、多维度组合 |

整体架构设计体现了**模板方法**、**策略模式**、**分层架构**等经典设计思想，代码复用度高、扩展性好、可观测性强，是企业级 Java 项目的优秀范例。
