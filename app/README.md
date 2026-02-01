# AI 面试平台 - 项目开发文档

## 1. 项目概述

### 1.1 项目简介
AI 面试平台（AI Interview Platform）是一款基于 Spring Boot 4.0 + Spring AI 2.0 构建的智能面试系统。系统提供简历智能分析、模拟面试、RAG 知识库问答三大核心功能，通过 AI 技术实现自动化面试评估和个性化知识检索。

### 1.2 核心功能
| 功能模块 | 描述 |
|---------|------|
| 简历管理 | 支持 PDF/DOCX/TXT 等格式简历上传、解析、AI 智能评分 |
| 模拟面试 | 基于简历内容生成定制化面试问题，实时评估回答并生成报告 |
| RAG 知识库 | 支持多格式文档向量化存储，基于 pgvector 的语义检索和流式问答 |
| 面试历史 | 完整的面试记录追踪，支持 PDF 报告导出 |

### 1.3 技术架构特点
- **Java 21 虚拟线程**：显著提升 I/O 密集型场景（AI 调用、SSE 长连接）的并发能力
- **Spring AI 2.0**：统一封装阿里云 DashScope（OpenAI 兼容模式）的 Chat 和 Embedding 能力
- **Redis Stream 异步任务**：简历分析、知识库向量化、面试评估均采用异步处理
- **RustFS 对象存储**：通过 S3 兼容协议实现文件持久化
- **pgvector 向量数据库**：基于 PostgreSQL 的 HNSW 索引实现高效相似度搜索

---

## 2. 技术栈与依赖

### 2.1 核心技术栈
| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 语言运行时，启用虚拟线程 |
| Spring Boot | 4.0 | 应用框架 |
| Spring AI | 2.0 | AI 能力封装（Chat/Embedding/VectorStore） |
| PostgreSQL | - | 关系型数据库 |
| pgvector | - | 向量存储与检索 |
| Redis/Redisson | 4.0 | 缓存 + Redis Stream 异步任务 |
| RustFS (S3) | - | 对象存储 |

### 2.2 关键依赖
```gradle
// Spring Boot Starters
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

// Spring AI
implementation "org.springframework.ai:spring-ai-starter-model-openai:${springAiVersion}"
implementation "org.springframework.ai:spring-ai-starter-vector-store-pgvector:${springAiVersion}"

// 文档解析
implementation libs.tika.core
implementation libs.tika.parsers

// 对象存储
implementation "software.amazon.awssdk:s3:${awsSdkVersion}"

// 汉字转拼音
implementation libs.pinyin4j

// Redis 客户端
implementation "org.redisson:redisson-spring-boot-starter:${redissonVersion}"

// PDF 导出
implementation "com.itextpdf:itext-core:${itextVersion}"
implementation "com.itextpdf:font-asian:${itextVersion}"

// 对象映射
implementation "org.mapstruct:mapstruct:${mapstructVersion}"
```

---

## 3. 目录结构说明

```
app/
├── src/main/java/interview/guide/
│   ├── App.java                          # 主启动类
│   ├── common/                           # 通用模块
│   │   ├── ai/                           # AI 能力封装
│   │   │   └── StructuredOutputInvoker.java  # 结构化输出重试封装
│   │   ├── annotation/                   # 自定义注解
│   │   │   └── RateLimit.java            # 限流注解
│   │   ├── aspect/                       # AOP 切面
│   │   │   └── RateLimitAspect.java      # 限流切面实现
│   │   ├── async/                        # 异步任务基类
│   │   │   ├── AbstractStreamConsumer.java  # Stream 消费者模板
│   │   │   └── AbstractStreamProducer.java  # Stream 生产者模板
│   │   ├── config/                       # 配置类
│   │   │   ├── AppConfigProperties.java  # 应用配置属性
│   │   │   ├── CorsConfig.java           # CORS 配置
│   │   │   ├── S3Config.java             # S3 客户端配置
│   │   │   └── StorageConfigProperties.java  # 存储配置
│   │   ├── constant/                     # 常量定义
│   │   │   ├── AsyncTaskStreamConstants.java  # 异步任务常量
│   │   │   └── CommonConstants.java      # 通用常量
│   │   ├── exception/                    # 异常处理
│   │   │   ├── BusinessException.java    # 业务异常
│   │   │   ├── ErrorCode.java            # 错误码枚举
│   │   │   ├── GlobalExceptionHandler.java  # 全局异常处理器
│   │   │   └── RateLimitExceededException.java  # 限流异常
│   │   ├── model/                        # 通用模型
│   │   │   └── AsyncTaskStatus.java      # 异步任务状态枚举
│   │   ├── result/                       # 统一响应
│   │   │   └── Result.java               # 统一响应结果
│   │   └── async/                        # 异步任务
│   ├── infrastructure/                   # 基础设施层
│   │   ├── export/                       # PDF 导出
│   │   │   ├── InterviewReportPdfExporter.java
│   │   │   └── ResumeAnalysisPdfExporter.java
│   │   ├── file/                         # 文件处理
│   │   │   ├── ContentTypeDetectionService.java   # 内容类型检测
│   │   │   ├── DocumentParseService.java          # 文档解析
│   │   │   ├── FileHashService.java               # 文件哈希计算
│   │   │   ├── FileStorageService.java            # RustFS 存储
│   │   │   ├── FileValidationService.java         # 文件验证
│   │   │   ├── NoOpEmbeddedDocumentExtractor.java # PDF 嵌入文档提取
│   │   │   └── TextCleaningService.java           # 文本清洗
│   │   ├── mapper/                       # MapStruct 映射器
│   │   │   ├── InterviewMapper.java
│   │   │   ├── KnowledgeBaseMapper.java
│   │   │   ├── RagChatMapper.java
│   │   │   └── ResumeMapper.java
│   │   └── redis/                        # Redis 服务
│   │       ├── InterviewSessionCache.java   # 面试会话缓存
│   │       └── RedisService.java            # Redis 工具封装
│   └── modules/                          # 业务模块
│       ├── interview/                    # 面试模块
│       │   ├── InterviewController.java
│       │   ├── listener/                 # Redis Stream 监听器
│       │   │   ├── EvaluateStreamProducer.java
│       │   │   └── EvaluateStreamConsumer.java
│       │   ├── model/                    # 数据模型
│       │   │   ├── CreateInterviewRequest.java
│       │   │   ├── InterviewSessionEntity.java
│       │   │   ├── InterviewAnswerEntity.java
│       │   │   ├── InterviewReportDTO.java
│       │   │   └── ...
│       │   ├── repository/               # 数据访问层
│       │   │   ├── InterviewSessionRepository.java
│       │   │   └── InterviewAnswerRepository.java
│       │   └── service/                  # 业务服务层
│       │       ├── InterviewSessionService.java   # 会话管理
│       │       ├── InterviewQuestionService.java  # 问题生成
│       │       ├── AnswerEvaluationService.java   # 答案评估
│       │       ├── InterviewPersistenceService.java  # 持久化
│       │       └── InterviewHistoryService.java   # 历史记录
│       ├── knowledgebase/                # 知识库模块
│       │   ├── KnowledgeBaseController.java
│       │   ├── RagChatController.java
│       │   ├── listener/
│       │   │   ├── VectorizeStreamProducer.java
│       │   │   └── VectorizeStreamConsumer.java
│       │   ├── model/
│       │   │   ├── KnowledgeBaseEntity.java
│       │   │   ├── RagChatSessionEntity.java
│       │   │   ├── QueryRequest.java
│       │   │   └── ...
│       │   ├── repository/
│       │   │   ├── KnowledgeBaseRepository.java
│       │   │   ├── RagChatSessionRepository.java
│       │   │   └── VectorRepository.java
│       │   └── service/
│       │       ├── KnowledgeBaseUploadService.java
│       │       ├── KnowledgeBaseParseService.java
│       │       ├── KnowledgeBaseVectorService.java
│       │       ├── KnowledgeBaseQueryService.java
│       │       └── RagChatSessionService.java
│       └── resume/                       # 简历模块
│           ├── ResumeController.java
│           ├── listener/
│           │   ├── AnalyzeStreamProducer.java
│           │   └── AnalyzeStreamConsumer.java
│           ├── model/
│           │   ├── ResumeEntity.java
│           │   ├── ResumeAnalysisEntity.java
│           │   └── ...
│           ├── repository/
│           │   ├── ResumeRepository.java
│           │   └── ResumeAnalysisRepository.java
│           └── service/
│               ├── ResumeUploadService.java
│               ├── ResumeParseService.java
│               ├── ResumeGradingService.java
│               └── ResumeHistoryService.java
├── src/main/resources/
│   ├── application.yml                   # 应用配置
│   ├── fonts/
│   │   └── ZhuqueFangsong-Regular.ttf    # PDF 中文字体
│   ├── prompts/                          # AI 提示词模板
│   │   ├── interview-evaluation-system.st
│   │   ├── interview-evaluation-user.st
│   │   ├── knowledgebase-query-system.st
│   │   └── ...
│   └── scripts/
├── build.gradle                          # Gradle 构建配置
├── Dockerfile                            # Docker 多阶段构建
└── Task.md                               # 任务说明
```

---

## 4. 核心模块功能详解

### 4.1 简历模块 (Resume Module)

#### 职责
- 简历文件上传、解析、去重
- AI 智能评分（维度：专业技能、项目经验、沟通能力等）
- 简历分析历史追踪

#### 核心流程
```
上传 → 验证 → 哈希去重 → 解析文本 → 存储 RustFS → 
发送 Stream 任务 → 异步 AI 分析 → 更新状态
```

#### 关键类说明

| 类名 | 职责 |
|------|------|
| `ResumeUploadService` | 上传流程编排，文件去重，发送异步任务 |
| `ResumeParseService` | 使用 Apache Tika 解析 PDF/DOCX/TXT |
| `ResumeGradingService` | 调用 AI 进行简历评分 |
| `AnalyzeStreamConsumer` | 消费 Redis Stream，执行异步分析 |

#### 数据结构
```java
ResumeEntity {
    Long id;
    String fileHash;           // SHA-256 哈希，用于去重
    String originalFilename;
    Long fileSize;
    String storageKey;         // RustFS 存储键
    String resumeText;         // 解析后的文本
    AsyncTaskStatus analyzeStatus;  // PENDING/PROCESSING/COMPLETED/FAILED
    String analyzeError;
}
```

---

### 4.2 面试模块 (Interview Module)

#### 职责
- 基于简历生成定制化面试问题
- 管理面试会话状态（Redis 缓存 + 数据库持久化）
- 实时评估用户回答
- 生成综合面试报告

#### 核心流程
```
创建会话 → AI 生成问题 → 缓存到 Redis → 
用户作答 → 暂存/提交 → 全部完成后触发异步评估 → 生成报告
```

#### 会话状态机
```
CREATED → IN_PROGRESS → COMPLETED → EVALUATED
   ↑           ↑            ↑
   └───────────┴────────────┘ (可提前交卷)
```

#### 关键类说明

| 类名 | 职责 |
|------|------|
| `InterviewSessionService` | 会话生命周期管理，缓存/数据库双写 |
| `InterviewQuestionService` | 基于简历和历史问题生成新题目 |
| `AnswerEvaluationService` | 分批评估回答，汇总生成报告 |
| `InterviewSessionCache` | Redis 缓存会话状态（TTL 24 小时） |
| `EvaluateStreamConsumer` | 消费评估任务，调用 AI 生成报告 |

#### 数据结构
```java
InterviewSessionEntity {
    String sessionId;        // 16 位 UUID
    Long resumeId;
    Integer totalQuestions;
    Integer currentQuestionIndex;
    String questionsJson;    // 问题列表 JSON
    SessionStatus status;
    AsyncTaskStatus evaluateStatus;
    String evaluateError;
}

InterviewAnswerEntity {
    String sessionId;
    Integer questionIndex;
    String question;
    String category;         // 问题类别
    String userAnswer;
    Integer score;           // 得分
    String feedback;         // AI 反馈
}
```

---

### 4.3 知识库模块 (Knowledge Base Module)

#### 职责
- 多格式文档上传、解析、分块
- 向量化存储（pgvector）
- RAG 检索增强生成
- 流式 SSE 问答

#### 核心流程
```
上传 → 验证 → 哈希去重 → 解析文本 → 存储 RustFS → 
发送 Stream 任务 → 异步向量化 → 更新状态
```

#### RAG 查询流程
```
用户提问 → Query Rewrite → 动态参数检索 → 
向量相似度搜索 → 构建上下文 → AI 生成回答 → 流式输出
```

#### 关键类说明

| 类名 | 职责 |
|------|------|
| `KnowledgeBaseUploadService` | 上传流程编排，发送向量化任务 |
| `KnowledgeBaseVectorService` | 文档分块、批量向量化、相似度搜索 |
| `KnowledgeBaseQueryService` | RAG 查询，支持 Query Rewrite 和动态 TopK |
| `RagChatSessionService` | RAG 聊天会话管理，流式消息处理 |
| `VectorizeStreamConsumer` | 消费向量化任务 |

#### 向量检索策略
```java
// 根据问题长度动态调整检索参数
if (compactLength <= 4) {
    topK = 20, minScore = 0.18;   // 短查询，放宽阈值
} else if (compactLength <= 12) {
    topK = 12, minScore = 0.28;   // 中等查询
} else {
    topK = 8, minScore = 0.28;    // 长查询，精简结果
}
```

#### 数据结构
```java
KnowledgeBaseEntity {
    Long id;
    String fileHash;
    String name;
    String category;           // 分类
    String storageKey;
    VectorStatus vectorStatus; // PENDING/PROCESSING/COMPLETED/FAILED
    Integer chunkCount;        // 向量分块数量
}

RagChatSessionEntity {
    Long id;
    String title;
    Set<KnowledgeBaseEntity> knowledgeBases;  // 多对多关联
    Boolean isPinned;
}
```

---

### 4.4 基础设施层 (Infrastructure)

#### 4.4.1 文件服务
| 服务类 | 职责 |
|--------|------|
| `FileStorageService` | RustFS(S3) 上传/下载/删除，文件名拼音化处理 |
| `DocumentParseService` | Apache Tika 解析 PDF/DOCX/TXT，提取嵌入文档 |
| `FileHashService` | 计算 SHA-256 哈希用于去重 |
| `FileValidationService` | 文件类型验证（MIME + 扩展名白名单） |

#### 4.4.2 Redis 服务
| 服务类 | 职责 |
|--------|------|
| `RedisService` | Redis 基础操作封装（Set/Get/Stream） |
| `InterviewSessionCache` | 面试会话缓存管理，简历 ID→会话 ID 映射 |

#### 4.4.3 异步任务框架
基于 **Redis Stream** 的异步任务处理框架：

```java
// 生产者模板
AbstractStreamProducer<T> {
    sendTask(T payload);  // 发送任务到 Stream
}

// 消费者模板
AbstractStreamConsumer<T> {
    consumeLoop();        // 轮询消息
    processMessage();     // 处理 + ACK + 重试
}
```

**三种异步任务类型：**
| 任务类型 | Stream Key | Consumer Group | 最大重试 |
|---------|-----------|----------------|---------|
| 简历分析 | `resume:analyze:stream` | `analyze-group` | 3 次 |
| 知识库向量化 | `knowledgebase:vectorize:stream` | `vectorize-group` | 3 次 |
| 面试评估 | `interview:evaluate:stream` | `evaluate-group` | 3 次 |

---

## 5. 核心业务流程说明

### 5.1 简历上传与分析流程

```
用户 → 控制器 → 上传服务 → 验证文件 → 计算哈希 → 检查重复
                                        ↓
                                        ← 重复：返回历史结果
                                        ↓ 新文件
                                    存储 RustFS → 保存数据库 (PENDING)
                                        ↓
                                    发送 Stream 任务
                                        ↓
                                    异步消费者 ← AI 评分 → 保存结果 → 更新状态 (COMPLETED)
```

### 5.2 模拟面试流程

```
用户 → 创建会话 → AI 生成问题 → 缓存 Redis → 持久化数据库
          ↓
      答题循环：
          ↓ 提交答案
          ↓ 更新缓存 + 数据库
          ↓ 返回下一题
          ↓
      完成面试 → 发送评估 Stream → 异步 AI 评估 → 保存报告
          ↓
      获取报告 → 返回评估结果
```

### 5.3 RAG 知识库查询流程

```
用户提问 → Query Rewrite → 动态参数检索 → 向量相似度搜索
                                              ↓
                                          pgvector 检索
                                              ↓
                                          构建上下文
                                              ↓
                                          AI 生成回答
                                              ↓
                                          流式输出 (SSE)
```

---

## 6. 关键函数/接口说明

### 6.1 REST API 接口

#### 简历模块
| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/resumes/upload` | POST | 上传简历并异步分析 |
| `/api/resumes` | GET | 获取简历列表 |
| `/api/resumes/{id}/detail` | GET | 获取简历详情（含分析历史） |
| `/api/resumes/{id}/export` | GET | 导出分析报告 PDF |
| `/api/resumes/{id}` | DELETE | 删除简历 |
| `/api/resumes/{id}/reanalyze` | POST | 重新分析（手动重试） |

#### 面试模块
| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/interview/sessions` | POST | 创建面试会话 |
| `/api/interview/sessions/{sessionId}` | GET | 获取会话信息 |
| `/api/interview/sessions/{sessionId}/question` | GET | 获取当前问题 |
| `/api/interview/sessions/{sessionId}/answers` | POST | 提交答案（进入下一题） |
| `/api/interview/sessions/{sessionId}/answers` | PUT | 暂存答案（不进入下一题） |
| `/api/interview/sessions/{sessionId}/complete` | POST | 提前交卷 |
| `/api/interview/sessions/{sessionId}/report` | GET | 生成面试报告 |
| `/api/interview/sessions/{sessionId}/export` | GET | 导出报告 PDF |
| `/api/interview/sessions/unfinished/{resumeId}` | GET | 查找未完成会话 |

#### 知识库模块
| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/knowledgebase/upload` | POST | 上传知识库文件 |
| `/api/knowledgebase/list` | GET | 获取知识库列表 |
| `/api/knowledgebase/{id}` | GET | 获取知识库详情 |
| `/api/knowledgebase/{id}/download` | GET | 下载知识库文件 |
| `/api/knowledgebase/{id}` | DELETE | 删除知识库 |
| `/api/knowledgebase/query` | POST | 基于知识库问答 |
| `/api/knowledgebase/query/stream` | POST | 流式问答 (SSE) |
| `/api/knowledgebase/{id}/revectorize` | POST | 重新向量化（手动重试） |
| `/api/knowledgebase/categories` | GET | 获取所有分类 |
| `/api/knowledgebase/category/{category}` | GET | 按分类获取知识库 |
| `/api/knowledgebase/{id}/category` | PUT | 更新分类 |

#### RAG 聊天模块
| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/rag-chat/sessions` | POST | 创建聊天会话 |
| `/api/rag-chat/sessions` | GET | 获取会话列表 |
| `/api/rag-chat/sessions/{sessionId}` | GET | 获取会话详情（含消息历史） |
| `/api/rag-chat/sessions/{sessionId}/title` | PUT | 更新会话标题 |
| `/api/rag-chat/sessions/{sessionId}/pin` | PUT | 切换置顶状态 |
| `/api/rag-chat/sessions/{sessionId}/knowledge-bases` | PUT | 更新会话知识库 |
| `/api/rag-chat/sessions/{sessionId}/messages/stream` | POST | 发送消息（流式 SSE） |
| `/api/rag-chat/sessions/{sessionId}` | DELETE | 删除会话 |

### 6.2 核心服务方法

#### InterviewSessionService
```java
// 创建面试会话
InterviewSessionDTO createSession(CreateInterviewRequest request)

// 获取会话（缓存未命中时从数据库恢复）
InterviewSessionDTO getSession(String sessionId)

// 提交答案并进入下一题
SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request)

// 暂存答案（不进入下一题）
void saveAnswer(SubmitAnswerRequest request)

// 提前交卷，触发异步评估
void completeInterview(String sessionId)

// 生成评估报告
InterviewReportDTO generateReport(String sessionId)
```

#### KnowledgeBaseQueryService
```java
// RAG 查询（完整响应）
QueryResponse queryKnowledgeBase(QueryRequest request)

// RAG 流式查询（SSE）
Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question)

// 内部方法：构建查询上下文（Query Rewrite + 动态参数）
QueryContext buildQueryContext(String originalQuestion)

// 内部方法：检索相关文档
List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds)
```

#### AnswerEvaluationService
```java
// 评估完整面试
InterviewReportDTO evaluateInterview(String sessionId, String resumeText, List<InterviewQuestionDTO> questions)

// 内部方法：分批评估（避免 token 超限）
List<BatchEvaluationResult> evaluateInBatches(...)

// 内部方法：汇总批次结果
FinalSummaryDTO summarizeBatchResults(...)
```

---

## 7. 数据结构/数据库设计

### 7.1 数据库表结构

#### resumes (简历表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| file_hash | VARCHAR(64) | SHA-256 哈希，唯一索引 |
| original_filename | VARCHAR | 原始文件名 |
| file_size | BIGINT | 文件大小（字节） |
| content_type | VARCHAR | MIME 类型 |
| storage_key | VARCHAR(500) | RustFS 存储键 |
| storage_url | VARCHAR(1000) | RustFS 访问 URL |
| resume_text | TEXT | 解析后的简历文本 |
| uploaded_at | TIMESTAMP | 上传时间 |
| last_accessed_at | TIMESTAMP | 最后访问时间 |
| access_count | INT | 访问次数 |
| analyze_status | VARCHAR(20) | 分析状态 (PENDING/PROCESSING/COMPLETED/FAILED) |
| analyze_error | VARCHAR(500) | 分析错误信息 |

#### interview_sessions (面试会话表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| session_id | VARCHAR(16) | 会话 ID，唯一索引 |
| resume_id | BIGINT | 关联简历 ID |
| total_questions | INT | 总问题数 |
| current_question_index | INT | 当前问题索引 |
| questions_json | TEXT | 问题列表 JSON |
| strengths_json | TEXT | 优势列表 JSON |
| improvements_json | TEXT | 改进建议 JSON |
| reference_answers_json | TEXT | 参考答案 JSON |
| overall_score | INT | 总分 |
| status | VARCHAR(20) | 会话状态 |
| evaluate_status | VARCHAR(20) | 评估状态 |
| evaluate_error | VARCHAR(500) | 评估错误 |
| created_at | TIMESTAMP | 创建时间 |
| completed_at | TIMESTAMP | 完成时间 |

#### interview_answers (面试答案表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| session_id | VARCHAR(16) | 会话 ID，索引 |
| question_index | INT | 问题索引 |
| question | TEXT | 问题内容 |
| category | VARCHAR(100) | 问题类别 |
| user_answer | TEXT | 用户回答 |
| score | INT | 得分 |
| feedback | TEXT | AI 反馈 |
| key_points_json | TEXT | 关键要点 JSON |

#### knowledge_bases (知识库表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| file_hash | VARCHAR(64) | SHA-256 哈希，唯一索引 |
| name | VARCHAR | 知识库名称 |
| category | VARCHAR(100) | 分类，索引 |
| original_filename | VARCHAR | 原始文件名 |
| file_size | BIGINT | 文件大小 |
| content_type | VARCHAR | MIME 类型 |
| storage_key | VARCHAR(500) | RustFS 存储键 |
| storage_url | VARCHAR(1000) | RustFS 访问 URL |
| uploaded_at | TIMESTAMP | 上传时间 |
| last_accessed_at | TIMESTAMP | 最后访问时间 |
| access_count | INT | 访问次数 |
| question_count | INT | 提问次数 |
| vector_status | VARCHAR(20) | 向量化状态 |
| vector_error | VARCHAR(500) | 向量化错误 |
| chunk_count | INT | 向量分块数 |

#### rag_chat_sessions (RAG 聊天会话表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| title | VARCHAR | 会话标题 |
| message_count | INT | 消息数量 |
| is_pinned | BOOLEAN | 是否置顶 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

#### rag_chat_sessions_knowledge_bases (关联表)
| 字段 | 类型 | 说明 |
|------|------|------|
| session_id | BIGINT | 会话 ID，联合主键 |
| knowledge_base_id | BIGINT | 知识库 ID，联合主键 |

#### rag_chat_messages (RAG 聊天消息表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| session_id | BIGINT | 会话 ID，索引 |
| type | VARCHAR(20) | 消息类型 (USER/ASSISTANT) |
| content | TEXT | 消息内容 |
| message_order | INT | 消息顺序 |
| completed | BOOLEAN | 是否完成 |
| created_at | TIMESTAMP | 创建时间 |

#### pgvector 向量表 (Spring AI 自动创建)
```sql
CREATE TABLE vector_store (
    id TEXT PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding VECTOR(1024)  -- text-embedding-v3 维度
);

CREATE INDEX vector_store_index ON vector_store 
USING hnsw (embedding vector_cosine_ops);
```

### 7.2 Redis 缓存结构

#### 面试会话缓存
```
Key: interview:session:{sessionId}
Type: Hash
TTL: 24 小时
Value: {
    sessionId: String,
    resumeText: String,
    resumeId: Long,
    questionsJson: String,
    currentIndex: int,
    status: SessionStatus
}
```

#### 简历 ID→会话 ID 映射
```
Key: interview:resume:{resumeId}
Type: String
TTL: 24 小时
Value: {sessionId}
```

#### Redis Stream 结构
```
Stream: resume:analyze:stream
  Consumer Group: analyze-group
  Consumers: analyze-consumer-{uuid}
  Message: {
    resumeId: "123",
    content: "{简历文本}",
    retryCount: "0"
  }

Stream: knowledgebase:vectorize:stream
  Consumer Group: vectorize-group
  Consumers: vectorize-consumer-{uuid}
  Message: {
    kbId: "456",
    content: "{知识库文本}",
    retryCount: "0"
  }

Stream: interview:evaluate:stream
  Consumer Group: evaluate-group
  Consumers: evaluate-consumer-{uuid}
  Message: {
    sessionId: "abc123",
    retryCount: "0"
  }
```

---

## 8. 部署与运行方式

### 8.1 环境要求
- **JDK**: 21+
- **PostgreSQL**: 14+ (需安装 pgvector 扩展)
- **Redis**: 6+
- **RustFS (S3)**: 兼容 S3 协议的对象存储

### 8.2 环境变量配置
```bash
# 数据库
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# AI 配置
AI_MODEL=qwen-plus

# 对象存储
APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=123456
APP_STORAGE_SECRET_KEY=123456
APP_STORAGE_BUCKET=interview-guide
APP_STORAGE_REGION=us-east-1

# CORS
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:80
```

### 8.3 Docker 部署

#### 构建镜像
```bash
docker build -t ai-interview-platform:latest .
```

#### docker-compose.yml 示例
```yaml
version: '3.8'

services:
  app:
    image: ai-interview-platform:latest
    ports:
      - "8080:8080"
    environment:
      - POSTGRES_HOST=postgres
      - REDIS_HOST=redis
      - APP_STORAGE_ENDPOINT=http://minio:9000
    depends_on:
      - postgres
      - redis
      - minio

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      - POSTGRES_PASSWORD=123456
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    volumes:
      - redis_data:/data

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      - MINIO_ROOT_USER=123456
      - MINIO_ROOT_PASSWORD=123456
    volumes:
      - minio_data:/data

volumes:
  postgres_data:
  redis_data:
  minio_data:
```

### 8.4 本地开发运行

#### 1. 启动依赖服务
```bash
# PostgreSQL (需安装 pgvector)
docker run -d --name postgres \
  -e POSTGRES_PASSWORD=123456 \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# Redis
docker run -d --name redis \
  -p 6379:6379 \
  redis:7-alpine

# MinIO (本地 S3)
docker run -d --name minio \
  -e MINIO_ROOT_USER=123456 \
  -e MINIO_ROOT_PASSWORD=123456 \
  -p 9000:9000 -p 9001:9001 \
  minio/minio server /data --console-address ":9001"
```

#### 2. 创建存储桶
```bash
# 访问 MinIO 控制台 http://localhost:9001
# 创建 bucket: interview-guide
```

#### 3. 运行应用
```bash
# 设置环境变量
export POSTGRES_HOST=localhost
export REDIS_HOST=localhost
export APP_STORAGE_ENDPOINT=http://localhost:9000
export APP_STORAGE_ACCESS_KEY=123456
export APP_STORAGE_SECRET_KEY=123456

# 启动应用
./gradlew bootRun
```

---

## 9. 常见问题与注意事项

### 9.1 开发注意事项

#### 1. 数据库初始化
- 首次启动时 `spring.jpa.hibernate.ddl-auto=create` 会自动建表
- 表创建完成后应改为 `update` 避免数据丢失
- pgvector 扩展需手动安装：`CREATE EXTENSION IF NOT EXISTS vector;`

#### 2. 异步任务处理
- 所有耗时操作（AI 调用、向量化）均通过 Redis Stream 异步处理
- 消费者失败后会自动重试（最多 3 次）
- 前端需轮询状态接口获取最新进度

#### 3. 文件去重机制
- 使用 SHA-256 哈希值判断文件是否重复
- 重复文件直接返回历史分析结果，节省 AI 调用成本

#### 4. 虚拟线程配置
```yaml
spring:
  threads:
    virtual:
      enabled: true  # Java 21+ 虚拟线程，提升 I/O 并发能力
```

### 9.2 常见问题

#### Q1: 简历分析/向量化状态一直为 PENDING
**原因**: Redis Stream 消费者未启动或消费失败  
**解决**: 检查 Redis 连接，查看消费者日志是否有异常

#### Q2: AI 调用失败
**原因**: API Key 无效或网络问题  
**解决**: 检查 `application.yml` 中 `spring.ai.openai.api-key` 配置

#### Q3: 向量搜索返回空结果
**原因**: 
1. 知识库未完成向量化
2. 查询阈值过高  
**解决**: 检查 `vector_status` 状态，调整 `min-score` 配置

#### Q4: PDF 导出中文乱码
**原因**: 缺少中文字体  
**解决**: 确保 `src/main/resources/fonts/ZhuqueFangsong-Regular.ttf` 存在

#### Q5: Redis Stream 消息堆积
**原因**: 消费者处理速度慢于生产速度  
**解决**: 增加消费者实例（Consumer Group 支持水平扩展）

### 9.3 性能优化建议

1. **批量向量化**: 阿里云 Embedding API 限制 batch size ≤ 10，已实现分批处理
2. **面试评估分批**: 单次评估问题数过多会导致 token 超限，默认每批 8 题
3. **缓存策略**: 面试会话缓存 TTL 24 小时，超时后从数据库恢复
4. **流式响应**: RAG 问答和面试评估均支持 SSE 流式输出，提升用户体验

### 9.4 安全建议

1. **API Key 管理**: 生产环境应使用环境变量注入，避免硬编码
2. **限流保护**: 所有 AI 调用接口均配置了限流注解 `@RateLimit`
3. **文件上传限制**: 简历 10MB，知识库 50MB，需根据实际需求调整
4. **CORS 配置**: 生产环境应限制允许的前端域名

---

**文档版本**: 1.0  
**最后更新**: 2026 年 3 月 15 日  
**适用版本**: ai-interview-platform 0.0.1-SNAPSHOT
