package interview.guide.modules.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.interview.agent.AgentOrchestrator;
import interview.guide.modules.interview.agent.model.AgentContext;
import interview.guide.modules.interview.agent.model.AgentState;
import interview.guide.modules.interview.model.*;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Agent 面试服务
 * 对外提供基于 Agent 架构的面试功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentInterviewService {

    private final AgentOrchestrator agentOrchestrator;
    private final InterviewPersistenceService persistenceService;
    private final InterviewHistoryService historyService;
    private final AnswerEvaluationService answerEvaluationService;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Agent 面试会话
     */
    @Transactional
    public InterviewSessionDTO createAgentSession(CreateInterviewRequest request, Long resumeId, String resumeText) {
        log.info("创建 Agent 面试会话，resumeId={}", resumeId);

        // 生成会话 ID
        String sessionId = UUID.randomUUID().toString();
        int questionCount = request.questionCount() != null ? request.questionCount() : 10;

        // 检查是否有未完成会话
        if (!request.forceCreate()) {
            InterviewSessionEntity existingSession = historyService.findUnfinishedSession(resumeId);
            if (existingSession != null) {
                log.info("返回未完成会话: {}", existingSession.getSessionId());
                return convertToDTO(existingSession);
            }
        }

        // 初始化 Agent 会话
        AgentContext context = agentOrchestrator.initializeSession(
            sessionId, resumeId, resumeText, questionCount
        );

        // 获取生成的问题
        List<InterviewQuestionDTO> questions = context.getQuestions(objectMapper);

        // 持久化到数据库
        InterviewSessionEntity session = persistenceService.createSession(
            sessionId, resumeId, questions, questionCount
        );

        log.info("Agent 面试会话创建成功: sessionId={}", sessionId);
        return convertToDTO(session);
    }

    /**
     * 获取 Agent 会话
     */
    public InterviewSessionDTO getAgentSession(String sessionId) {
        log.debug("获取 Agent 会话: {}", sessionId);

        // 先从缓存获取
        try {
            AgentContext context = agentOrchestrator.getContext(sessionId);
            return convertContextToDTO(context);
        } catch (Exception e) {
            log.warn("从缓存获取会话失败，尝试从数据库获取: {}", e.getMessage());
        }

        // 从数据库获取
        InterviewSessionEntity session = historyService.getSessionBySessionId(sessionId);
        return convertToDTO(session);
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        log.debug("获取当前问题: {}", sessionId);
        return agentOrchestrator.getCurrentQuestion(sessionId);
    }

    /**
     * 提交答案
     */
    @Transactional
    public SubmitAnswerResponse submitAgentAnswer(String sessionId, SubmitAnswerRequest request) {
        log.info("提交 Agent 答案: sessionId={}", sessionId);

        // 调用 Agent 处理答案
        AgentContext context = agentOrchestrator.processAnswer(sessionId, request.answer());

        // 获取更新后的问题列表
        List<InterviewQuestionDTO> questions = context.getQuestions(objectMapper);
        InterviewQuestionDTO currentQuestion = context.getCurrentQuestion(objectMapper);

        // 持久化答案
        persistenceService.saveAnswer(sessionId, context.getCurrentQuestionIndex(),
            questions.get(context.getCurrentQuestionIndex()), request.answer());

        // 检查是否完成
        boolean isCompleted = context.getState() == AgentState.COMPLETED;

        if (isCompleted) {
            // 生成最终报告
            completeAndEvaluate(sessionId, questions);
        }

        // 构建响应
        return SubmitAnswerResponse.builder()
            .sessionId(sessionId)
            .currentQuestionIndex(context.getCurrentQuestionIndex())
            .totalQuestions(context.getTotalQuestions())
            .nextQuestion(currentQuestion)
            .isCompleted(isCompleted)
            .build();
    }

    /**
     * 暂存答案（不进下一题）
     */
    @Transactional
    public void saveAgentAnswer(String sessionId, SubmitAnswerRequest request) {
        log.info("暂存 Agent 答案: sessionId={}", sessionId);

        AgentContext context = agentOrchestrator.getContext(sessionId);
        List<InterviewQuestionDTO> questions = context.getQuestions(objectMapper);

        if (context.getCurrentQuestionIndex() >= 0 && context.getCurrentQuestionIndex() < questions.size()) {
            InterviewQuestionDTO updated = questions.get(context.getCurrentQuestionIndex())
                .withAnswer(request.answer());
            questions.set(context.getCurrentQuestionIndex(), updated);
            context.setQuestions(questions, objectMapper);

            // 持久化
            persistenceService.saveAnswer(sessionId, context.getCurrentQuestionIndex(),
                updated, request.answer());
        }
    }

    /**
     * 提前交卷
     */
    @Transactional
    public InterviewReportDTO completeAgentInterview(String sessionId) {
        log.info("提前结束 Agent 面试: {}", sessionId);

        AgentContext context = agentOrchestrator.forceComplete(sessionId);
        List<InterviewQuestionDTO> questions = context.getQuestions(objectMapper);

        return completeAndEvaluate(sessionId, questions);
    }

    /**
     * 完成并生成评估报告
     */
    private InterviewReportDTO completeAndEvaluate(String sessionId, List<InterviewQuestionDTO> questions) {
        log.info("生成 Agent 面试评估报告: {}", sessionId);

        // 更新会话状态
        persistenceService.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 使用传统评估服务生成最终报告
        InterviewReportDTO report = answerEvaluationService.evaluateInterview(sessionId, questions);

        // 持久化报告
        persistenceService.saveReport(sessionId, report);

        return report;
    }

    /**
     * 获取面试报告
     */
    public InterviewReportDTO getAgentReport(String sessionId) {
        log.debug("获取 Agent 面试报告: {}", sessionId);
        return historyService.getInterviewReport(sessionId);
    }

    // ==================== 转换方法 ====================

    private InterviewSessionDTO convertToDTO(InterviewSessionEntity entity) {
        List<InterviewQuestionDTO> questions = persistenceService.parseQuestionsJson(entity.getQuestionsJson());
        return InterviewSessionDTO.builder()
            .sessionId(entity.getSessionId())
            .resumeId(entity.getResume() != null ? entity.getResume().getId() : null)
            .currentQuestionIndex(entity.getCurrentQuestionIndex())
            .totalQuestions(entity.getTotalQuestions())
            .status(entity.getStatus())
            .questions(questions)
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .build();
    }

    private InterviewSessionDTO convertContextToDTO(AgentContext context) {
        List<InterviewQuestionDTO> questions = context.getQuestions(objectMapper);
        return InterviewSessionDTO.builder()
            .sessionId(context.getSessionId())
            .resumeId(context.getResumeId())
            .currentQuestionIndex(context.getCurrentQuestionIndex())
            .totalQuestions(context.getTotalQuestions())
            .status(convertState(context.getState()))
            .questions(questions)
            .build();
    }

    private SessionStatus convertState(AgentState state) {
        if (state == null) {
            return SessionStatus.CREATED;
        }
        return switch (state) {
            case INITIALIZING, GENERATING_QUESTIONS -> SessionStatus.CREATED;
            case ASKING_QUESTION, WAITING_FOR_ANSWER, EVALUATING_ANSWER,
                 DECIDING_FOLLOWUP, GENERATING_FOLLOWUP -> SessionStatus.IN_PROGRESS;
            case SUMMARIZING, COMPLETED, ERROR -> SessionStatus.COMPLETED;
        };
    }
}
