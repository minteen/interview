
<div align="center">


**基于大语言模型的智能简历分析与模拟面试系统**


</div>

## 📋 项目介绍

**AI Interview Platform** 是一个集成了简历分析和模拟面试的智能面试辅助平台。系统利用大语言模型和Agent技术，为求职者提供智能化的简历评估和面试练习服务，为企业提供专业的面试官AI助手。

### ✨ 核心特性

- **🤖 智能简历分析** - 基于大语言模型的深度简历审计与评分
- **🎯 个性化面试** - 根据简历内容自动生成针对性面试问题
- **📚 RAG知识库** - 基于向量检索的增强生成问答系统
- **⚡ 异步处理** - Redis Stream实现的高效异步任务处理
- **📊 实时评估** - 流式响应的面试评估和反馈系统
- **🎨 现代UI** - React + Tailwind CSS构建的响应式前端

## 🏗️ 系统架构

### 整体架构

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   前端应用      │    │   后端服务      │    │    AI服务       │
│   React + TS    │◄──►│ Spring Boot 4.0 │◄──►│ 阿里云DashScope │
│   Tailwind CSS  │    │    Java 21      │    │    Spring AI    │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                    ┌───────────┼───────────┐
                    │           │           │
            ┌───────▼───┐ ┌─────▼─────┐ ┌───▼─────┐
            │ PostgreSQL│ │ RabbitMQ  │ │  MinIO  │
            │ pgvector  │ │           │ │ 对象存储 │
            └───────────┘ └───────────┘ └─────────┘
```

### 技术栈

#### 后端技术栈
- **框架**: Spring Boot 4.0, Spring AI 2.0, Spring Data JPA
- **语言**: Java 21 (虚拟线程)
- **数据库**: PostgreSQL 16 + pgvector (向量检索)
- **缓存**: Redis 7 + Redisson
- **存储**: MinIO (S3兼容对象存储)
- **AI集成**: Spring AI + 阿里云DashScope
- **工具**: Lombok, MapStruct, Apache Tika, iText

#### 前端技术栈
- **框架**: React 18, TypeScript, Vite
- **样式**: Tailwind CSS 4, PostCSS
- **组件**: Lucide React, Framer Motion
- **图表**: Recharts
- **工具**: Axios, React Router, React Markdown

#### 基础设施
- **容器化**: Docker + Docker Compose
- **监控**: Spring Boot Actuator
- **日志**: SLF4J + Logback

## 🚀 快速开始

### 环境要求

- Java 21+
- Node.js 18+
- Docker & Docker Compose
- PostgreSQL 16+ (推荐使用pgvector)
- Redis 7+
- MinIO 或兼容S3的对象存储

### 本地开发环境搭建

#### 1. 克隆项目

```bash
git clone <repository-url>
cd interview-guide
```

#### 2. 配置环境变量

创建 `.env` 文件：

```env
# AI模型配置 (必需)
AI_BAILIAN_API_KEY=your_dashscope_api_key
AI_MODEL=qwen-plus

# 面试参数配置 (可选)
APP_INTERVIEW_FOLLOW_UP_COUNT=1
APP_INTERVIEW_EVALUATION_BATCH_SIZE=8
```

#### 3. 启动基础设施

```bash
# 启动 PostgreSQL, Redis, MinIO
docker-compose up -d postgres redis minio

# 等待服务启动完成
sleep 10

# 创建存储桶
docker-compose up createbuckets
```

#### 4. 启动后端服务

```bash
# 进入后端目录
cd app

# 使用Gradle启动
./gradlew bootRun

# 或使用Docker启动
cd ..
docker-compose up app
```

#### 5. 启动前端服务

```bash
# 进入前端目录
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 或使用Docker启动
cd ..
docker-compose up frontend
```

#### 6. 访问应用

- **前端**: http://localhost:5173
- **后端API**: http://localhost:8080
- **MinIO控制台**: http://localhost:9001 (用户名/密码: minioadmin/minioadmin)

### Docker Compose一键部署

```bash
# 启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f app
```

## 📁 项目结构

```
interview-guide/
├── app/                              # 后端应用
│   ├── src/main/java/interview/guide/
│   │   ├── App.java                  # 主启动类
│   │   ├── common/                   # 通用模块
│   │   │   ├── config/               # 配置类
│   │   │   ├── exception/            # 异常处理
│   │   │   ├── result/               # 统一响应
│   │   │   └── aspect/               # AOP切面
│   │   ├── infrastructure/           # 基础设施
│   │   │   ├── export/               # PDF导出
│   │   │   ├── file/                 # 文件处理
│   │   │   ├── redis/                # Redis服务
│   │   │   ├── storage/               # 对象存储
│   │   │   └── mapper/               # 对象映射
│   │   └── modules/                  # 业务模块
│   │       ├── interview/            # 面试模块
│   │       │   ├── agent/           # AI Agent实现
│   │       │   ├── listener/        # 异步监听器
│   │       │   ├── model/           # 数据模型
│   │       │   ├── repository/      # 数据访问
│   │       │   └── service/         # 业务服务
│   │       ├── knowledgebase/        # 知识库模块
│   │       └── resume/               # 简历模块
│   └── src/main/resources/
│       ├── application.yml           # 应用配置
│       └── prompts/                  # AI提示词模板
│           ├── agent/                # Agent
│           ├── interview-*.st        # 面试相关提示词
│           ├── knowledgebase-*.st    # 知识库提示词
│           └── resume-analysis.st    # 简历分析提示词
│
├── frontend/                         # 前端应用
│   ├── src/
│   │   ├── api/                      # API接口
│   │   ├── components/               # 公共组件
│   │   ├── pages/                    # 页面组件
│   │   ├── types/                    # 类型定义
│   │   └── utils/                    # 工具函数
│   ├── package.json
│   └── vite.config.ts
│
├── docker/                           # Docker配置
│   ├── postgres/                     # PostgreSQL配置
│   └── Dockerfile                    # 构建文件
│
├── docker-compose.yml               # Docker编排配置
├── README.md
└── LICENSE
```

## 🔧 核心功能模块

### 1. 简历分析模块 (Resume Module)

#### 功能特性
- **智能解析**: 支持PDF、DOCX、TXT格式简历解析
- **深度分析**: 基于大语言模型的多维度简历评估
- **评分系统**: 项目深度、技能匹配、内容完整性等维度评分
- **改进建议**: 提供具体可操作的简历优化建议

#### 核心组件
- `ResumeController`: REST API控制器
- `ResumeAnalysisService`: 简历分析服务
- `ResumeGradingService`: 简历评分服务
- `AnalyzeStreamConsumer/Producer`: 异步处理组件

#### 数据结构
```java
@Entity
public class Resume {
    @Id
    private String id;
    private String fileName;
    private String filePath;
    private ResumeStatus status; // PENDING, PROCESSING, COMPLETED, FAILED
    private ResumeAnalysisResult analysisResult;
    private LocalDateTime createdAt;
}
```

### 2. 面试模块 (Interview Module)

#### 功能特性
- **个性化问题生成**: 基于简历内容生成针对性问题
- **多轮对话**: 支持追问和深度交流
- **实时评估**: 流式响应的答案评估
- **面试报告**: 生成详细的面试评估报告

#### Agent架构
- **Master Agent**: 主面试官Agent，负责整体流程控制
- **Question Generation Agent**: 问题生成Agent
- **Follow-up Evaluation Agent**: 追问评估Agent

#### 核心组件
- `InterviewController`: 面试API控制器
- `MasterInterviewAgent`: 主面试官Agent
- `QuestionGenerationAgent`: 问题生成Agent
- `AnswerEvaluationService`: 答案评估服务

### 3. 知识库模块 (Knowledge Base Module)

#### 功能特性
- **文档管理**: 支持多种格式文档上传和管理
- **向量检索**: 基于pgvector的语义搜索
- **RAG问答**: 检索增强生成式问答
- **实时聊天**: 支持流式响应的对话体验

#### 核心组件
- `KnowledgeBaseController`: 知识库控制器
- `RagChatController`: RAG对话控制器
- `KnowledgeBaseVectorService`: 向量服务
- `KnowledgeBaseQueryService`: 查询服务

## 🔄 异步处理流程

### 简历分析流程

```
上传简历 → 保存文件 → 发送消息到RabbitMQ → 立即返回
                                 ↓
                         Consumer消费消息
                                 ↓
                         执行简历分析
                                 ↓
                         更新数据库状态
                                 ↓
                         前端轮询获取最新状态
```

### 状态流转

- **PENDING** → 任务已创建，等待处理
- **PROCESSING** → 正在处理中
- **COMPLETED** → 处理完成
- **FAILED** → 处理失败

### 代码示例

```java
@Service
public class ResumeUploadService {
    private final AnalyzeStreamProducer analyzeProducer;

    public ResumeAnalysisResponse uploadResume(MultipartFile file) {
        // 1. 保存文件到MinIO
        String fileUrl = fileStorageService.store(file);

        // 2. 创建分析任务
        ResumeAnalysisEntity task = createAnalysisTask(fileUrl);

        // 3. 发送异步消息
        analyzeProducer.sendMessage(task.getId());

        // 4. 立即返回任务ID
        return ResumeAnalysisResponse.builder()
            .taskId(task.getId())
            .status(task.getStatus())
            .build();
    }
}
```

## 🤖 AI Agent架构

### 主面试官Agent (Master Interview Agent)

```java
@Component
public class MasterInterviewAgent {
    private final QuestionGenerationAgent questionAgent;
    private final FollowUpEvaluationAgent evaluationAgent;

    public InterviewSessionDTO conductInterview(InterviewContext context) {
        // 1. 生成初始问题
        InterviewQuestion question = questionAgent.generateQuestion(context);

        // 2. 等待用户回答
        String userAnswer = waitForUserAnswer();

        // 3. 评估答案并生成追问
        EvaluationResult evaluation = evaluationAgent.evaluate(userAnswer, question);

        // 4. 生成面试报告
        return generateInterviewReport(evaluation);
    }
}
```

### 提示词工程

#### 问题生成提示词
```
# Role
你是一位拥有10年以上经验的资深Java后端技术专家及大厂面试官。

# Task
请根据候选人的简历内容，生成一套针对性的面试问题集。

# Constraints
- 问题必须与简历内容高度相关
- 遵循"基础→进阶→专家"的难度梯度
- 每个问题都必须提供追问点
```

## 📊 API接口文档

### 简历相关接口

#### 上传简历
```http
POST /api/v1/resume/upload
Content-Type: multipart/form-data

file: resume.pdf

Response:
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "resume_123",
    "status": "PENDING"
  }
}
```

#### 获取简历分析状态
```http
GET /api/v1/resume/status/{taskId}

Response:
{
  "code": 200,
  "data": {
    "taskId": "resume_123",
    "status": "COMPLETED",
    "analysisResult": {
      "overallScore": 85,
      "scoreDetail": {
        "projectScore": 35,
        "skillMatchScore": 18,
        "contentScore": 12,
        "structureScore": 13,
        "expressionScore": 7
      },
      "summary": "技术深度不错，建议加强项目量化描述",
      "strengths": ["Java基础扎实", "Spring经验丰富"],
      "suggestions": [...]
    }
  }
}
```

### 面试相关接口

#### 创建面试会话
```http
POST /api/v1/interview/session
Content-Type: application/json

{
  "resumeId": "resume_123",
  "interviewType": "technical"
}

Response:
{
  "code": 200,
  "data": {
    "sessionId": "session_456",
    "firstQuestion": "请介绍一下你在项目中使用Redis的经验？"
  }
}
```

#### 提交答案
```http
POST /api/v1/interview/answer
Content-Type: application/json

{
  "sessionId": "session_456",
  "questionId": "q_789",
  "answer": "我在项目中主要使用Redis作为缓存..."
}

Response:
{
  "code": 200,
  "data": {
    "evaluation": "回答较好，但可以更深入...",
    "followUpQuestions": ["能具体说说缓存穿透的处理方案吗？"],
    "nextQuestion": "关于Redis的持久化机制..."
  }
}
```

## 🔐 安全特性

### 速率限制
- 基于Redis的令牌桶算法
- 支持IP和用户维度的限流
- 可配置的限流策略

### 文件安全
- 文件类型验证
- 文件大小限制
- 病毒扫描（可选）

### API安全
- CORS配置
- 输入验证
- 异常处理

## 🚀 部署指南

### 生产环境部署

#### 1. 数据库优化
```sql
-- 创建向量索引
CREATE INDEX ON knowledge_base_vectors 
USING hnsw (embedding vector_cosine_ops);

-- 调整PostgreSQL配置
shared_buffers = 256MB
work_mem = 16MB
maintenance_work_mem = 128MB
```

#### 2. Redis配置
```yaml
# redis.conf
databases 16
maxmemory 2gb
maxmemory-policy allkeys-lru
appendonly yes
```

#### 3. 应用配置
```yaml
# application-prod.yml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

app:
  ai:
    structured-max-attempts: 3
  storage:
    endpoint: ${APP_STORAGE_ENDPOINT}
    bucket: ${APP_STORAGE_BUCKET}
```

### 监控与运维

#### 健康检查
```bash
# 应用健康检查
curl http://localhost:8080/actuator/health

# 数据库连接检查
curl http://localhost:8080/actuator/health/db

# Redis连接检查
curl http://localhost:8080/actuator/health/redis
```

#### 日志管理
```bash
# 查看应用日志
docker-compose logs -f app

# 查看特定模块日志
grep "ResumeAnalysisService" app.log
```

## 🧪 测试策略

### 单元测试
```java
@SpringBootTest
class ResumeAnalysisServiceTest {

    @Autowired
    private ResumeAnalysisService service;

    @Test
    void shouldAnalyzeResumeSuccessfully() {
        // Given
        String resumeContent = "Java开发工程师，3年经验...";

        // When
        ResumeAnalysisResult result = service.analyze(resumeContent);

        // Then
        assertThat(result.getOverallScore()).isGreaterThan(0);
        assertThat(result.getSuggestions()).isNotEmpty();
    }
}
```

### 集成测试
```java
@SpringBootTest
@AutoConfigureTestDatabase
class InterviewIntegrationTest {

    @Test
    void shouldCompleteFullInterviewFlow() {
        // 1. 上传简历
        String resumeId = uploadResume();

        // 2. 创建面试
        String sessionId = createInterview(resumeId);

        // 3. 进行面试对话
        InterviewResult result = conductInterview(sessionId);

        // 4. 验证结果
        assertThat(result.getScore()).isNotNull();
    }
}
```

## 📈 性能优化

### 数据库优化
- 使用连接池（HikariCP）
- 合理设置索引
- 批量操作优化

### 缓存策略
- Redis缓存热点数据
- 本地缓存（Caffeine）
- 多级缓存架构

### AI调用优化
- 请求批处理
- 结果缓存
- 超时控制