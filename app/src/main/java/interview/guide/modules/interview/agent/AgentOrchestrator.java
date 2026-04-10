package interview.guide.modules.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.agent.model.AgentContext;
import interview.guide.modules.interview.agent.model.AgentState;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 协调器
 * 封装 Agent 调用链路，处理超时、重试、降级逻辑，协调会话状态持久化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final MasterInterviewAgent masterAgent;
    private final InterviewSessionCache interviewSessionCache;
    private final ObjectMapper objectMapper;

    /**
     * 初始化 Agent 会话
     */
    public AgentContext initializeSession(String sessionId, Long resumeId, String resumeText, int questionCount) {
        log.info("AgentOrchestrator 初始化会话: sessionId={}, resumeId={}", sessionId, resumeId);

        try {
            // 创建 Agent 上下文
            AgentContext context = AgentContext.create(sessionId, resumeId, resumeText);

            // 调用主 Agent 初始化（生成问题）
            context = masterAgent.initializeSession(context, questionCount);

            // 持久化到 Redis 缓存
            saveToCache(context);

            log.info("AgentOrchestrator 会话初始化完成: sessionId={}", sessionId);
            return context;

        } catch (Exception e) {
            log.error("AgentOrchestrator 初始化会话失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 处理用户回答
     */
    public AgentContext processAnswer(String sessionId, String userAnswer) {
        log.info("AgentOrchestrator 处理回答: sessionId={}", sessionId);

        // 从缓存加载上下文
        AgentContext context = loadFromCache(sessionId);
        if (context == null) {
            throw new IllegalArgumentException("会话不存在或已过期: " + sessionId);
        }

        try {
            // 调用主 Agent 处理回答
            context = masterAgent.processAnswer(context, userAnswer);

            // 保存到缓存
            saveToCache(context);

            log.info("AgentOrchestrator 回答处理完成: sessionId={}, state={}", sessionId, context.getState());
            return context;

        } catch (Exception e) {
            log.error("AgentOrchestrator 处理回答失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            // 保存失败前的状态
            saveToCache(context);
            throw e;
        }
    }

    /**
     * 获取当前会话上下文
     */
    public AgentContext getContext(String sessionId) {
        AgentContext context = loadFromCache(sessionId);
        if (context == null) {
            throw new IllegalArgumentException("会话不存在或已过期: " + sessionId);
        }
        return context;
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        AgentContext context = getContext(sessionId);
        return context.getCurrentQuestion(objectMapper);
    }

    /**
     * 获取所有问题
     */
    public List<InterviewQuestionDTO> getQuestions(String sessionId) {
        AgentContext context = getContext(sessionId);
        return context.getQuestions(objectMapper);
    }

    /**
     * 检查会话是否完成
     */
    public boolean isCompleted(String sessionId) {
        AgentContext context = getContext(sessionId);
        return context.getState() == AgentState.COMPLETED;
    }

    /**
     * 强制完成会话
     */
    public AgentContext forceComplete(String sessionId) {
        AgentContext context = loadFromCache(sessionId);
        if (context == null) {
            throw new IllegalArgumentException("会话不存在或已过期: " + sessionId);
        }

        context.transitionTo(AgentState.SUMMARIZING);
        Integer averageScore = context.calculateAverageScore();
        if (averageScore != null) {
            context.setOverallScore(averageScore);
        }
        context.transitionTo(AgentState.COMPLETED);

        saveToCache(context);
        log.info("AgentOrchestrator 强制完成会话: sessionId={}", sessionId);
        return context;
    }

    // ==================== 私有方法 ====================

    /**
     * 从缓存加载 Agent 上下文
     */
    private AgentContext loadFromCache(String sessionId) {
        // 先尝试从传统缓存获取，转换为 AgentContext
        var cachedSessionOpt = interviewSessionCache.getSession(sessionId);
        if (cachedSessionOpt.isEmpty()) {
            return null;
        }

        var cachedSession = cachedSessionOpt.get();

        // 转换为 AgentContext
        AgentContext context = AgentContext.create(
            cachedSession.getSessionId(),
            cachedSession.getResumeId(),
            cachedSession.getResumeText()
        );

        // 恢复问题列表
        try {
            List<InterviewQuestionDTO> questions = cachedSession.getQuestions(objectMapper);
            context.setQuestions(questions, objectMapper);
        } catch (Exception e) {
            log.warn("恢复问题列表失败: {}", e.getMessage());
        }

        context.setCurrentQuestionIndex(cachedSession.getCurrentIndex());

        // 转换状态
        context.setState(convertToAgentState(cachedSession.getStatus()));

        return context;
    }

    /**
     * 保存 Agent 上下文到缓存
     */
    private void saveToCache(AgentContext context) {
        // 转换为传统缓存格式（保持兼容性）
        List<InterviewQuestionDTO> questions = context.getQuestions(objectMapper);
        interviewSessionCache.saveSession(
            context.getSessionId(),
            context.getResumeText(),
            context.getResumeId(),
            questions,
            context.getCurrentQuestionIndex(),
            convertFromAgentState(context.getState())
        );
    }

    /**
     * 将会话状态转换为 Agent 状态
     */
    private AgentState convertToAgentState(interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus status) {
        if (status == null) {
            return AgentState.INITIALIZING;
        }
        return switch (status) {
            case CREATED -> AgentState.INITIALIZING;
            case IN_PROGRESS -> AgentState.ASKING_QUESTION;
            case COMPLETED -> AgentState.COMPLETED;
            case EVALUATED -> AgentState.COMPLETED;
        };
    }

    /**
     * 从 Agent 状态转换为会话状态
     */
    private interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus convertFromAgentState(AgentState state) {
        if (state == null) {
            return interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus.CREATED;
        }
        return switch (state) {
            case INITIALIZING, GENERATING_QUESTIONS ->
                interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus.CREATED;
            case ASKING_QUESTION, WAITING_FOR_ANSWER, EVALUATING_ANSWER,
                 DECIDING_FOLLOWUP, GENERATING_FOLLOWUP ->
                interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus.IN_PROGRESS;
            case SUMMARIZING, COMPLETED, ERROR ->
                interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus.COMPLETED;
        };
    }
}
