package interview.guide.modules.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.agent.model.*;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主 Agent - 面试流程编排
 * 负责控制面试流程、判断是否需要新问题、汇总评分
 */
@Component
public class MasterInterviewAgent {

    private static final Logger log = LoggerFactory.getLogger(MasterInterviewAgent.class);

    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<MasterDecisionResponse> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final QuestionGenerationAgent questionGenerationAgent;
    private final FollowUpEvaluationAgent followUpEvaluationAgent;
    private final ObjectMapper objectMapper;

    // 内部决策响应 DTO
    private record MasterDecisionResponse(
        String decision,
        String reasoning,
        int confidence,
        String suggestion
    ) {}

    public MasterInterviewAgent(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            QuestionGenerationAgent questionGenerationAgent,
            FollowUpEvaluationAgent followUpEvaluationAgent,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/agent/master-agent-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/agent/master-agent-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.questionGenerationAgent = questionGenerationAgent;
        this.followUpEvaluationAgent = followUpEvaluationAgent;
        this.objectMapper = objectMapper;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(MasterDecisionResponse.class);
    }

    /**
     * 初始化会话 - 生成问题
     */
    public AgentContext initializeSession(AgentContext context, int questionCount) {
        log.info("MasterInterviewAgent 初始化会话: {}", context.getSessionId());
        context.transitionTo(AgentState.GENERATING_QUESTIONS);

        // 构建问题生成请求
        QuestionGenerationRequest request = QuestionGenerationRequest.createDefault(
            context.getResumeText(),
            questionCount
        );

        // 调用问题生成 Agent
        QuestionGenerationResponse response = questionGenerationAgent.generateQuestions(request);

        if (response.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "未能生成面试问题");
        }

        // 设置问题到上下文
        context.setQuestions(response.questions(), objectMapper);
        context.setTotalQuestions(response.questions().size());
        context.transitionTo(AgentState.ASKING_QUESTION);

        log.info("MasterInterviewAgent 会话初始化完成，问题数: {}", context.getTotalQuestions());
        return context;
    }

    /**
     * 处理用户回答
     */
    public AgentContext processAnswer(AgentContext context, String userAnswer) {
        log.info("MasterInterviewAgent 处理回答，会话: {}, 当前问题: {}",
            context.getSessionId(), context.getCurrentQuestionIndex());

        // 获取当前问题
        InterviewQuestionDTO currentQuestion = context.getCurrentQuestion(objectMapper);
        if (currentQuestion == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "当前问题不存在");
        }

        // 添加用户回答到对话历史
        context.addMessage(AgentMessage.user(AgentMessageType.ANSWER, userAnswer));
        context.transitionTo(AgentState.EVALUATING_ANSWER);

        // 调用追问评估 Agent
        FollowUpEvaluationRequest evalRequest = FollowUpEvaluationRequest.withHistory(
            currentQuestion,
            userAnswer,
            context.getConversationHistory(),
            context.getResumeText(),
            context.getFollowUpDepth(),
            context.getMaxFollowUps()
        );

        FollowUpEvaluationResponse evalResponse = followUpEvaluationAgent.evaluateWithReAct(evalRequest);

        // 保存评分
        context.setQuestionScore(
            context.getCurrentQuestionIndex(),
            evalResponse.score(),
            evalResponse.feedback()
        );

        // 更新问题列表中的答案和评分
        updateQuestionWithEvaluation(context, currentQuestion, userAnswer, evalResponse);

        // 根据决策执行下一步
        InterviewDecision decision = evalResponse.finalDecision();
        log.info("MasterInterviewAgent 决策: {}", decision);

        return executeDecision(context, decision, evalResponse);
    }

    /**
     * 执行决策
     */
    private AgentContext executeDecision(AgentContext context, InterviewDecision decision, FollowUpEvaluationResponse evalResponse) {
        switch (decision) {
            case ASK_FOLLOWUP -> {
                if (context.canFollowUp() && evalResponse.followUpQuestion() != null) {
                    return handleFollowUp(context, evalResponse.followUpQuestion());
                } else {
                    // 无法继续追问，进入下一题
                    return proceedToNextQuestion(context);
                }
            }
            case CONTINUE_WITH_NEXT, CONCLUDE_TOPIC -> {
                return proceedToNextQuestion(context);
            }
            case COMPLETE_INTERVIEW -> {
                return completeInterview(context);
            }
            default -> {
                // 默认进入下一题
                return proceedToNextQuestion(context);
            }
        }
    }

    /**
     * 处理追问
     */
    private AgentContext handleFollowUp(AgentContext context, String followUpQuestion) {
        log.info("MasterInterviewAgent 生成追问，会话: {}, 追问深度: {}",
            context.getSessionId(), context.getFollowUpDepth() + 1);

        // 获取当前问题信息
        InterviewQuestionDTO currentQuestion = context.getCurrentQuestion(objectMapper);
        List<InterviewQuestionDTO> questions = new ArrayList<>(context.getQuestions(objectMapper));

        // 创建新的追问问题
        int newIndex = questions.size();
        InterviewQuestionDTO followUp = InterviewQuestionDTO.create(
            newIndex,
            followUpQuestion,
            currentQuestion.type(),
            currentQuestion.category() + "（追问）",
            true,
            context.getCurrentQuestionIndex()
        );

        // 添加到问题列表
        questions.add(followUp);
        context.setQuestions(questions, objectMapper);

        // 更新状态
        context.setCurrentQuestionIndex(newIndex);
        context.incrementFollowUpDepth();
        context.transitionTo(AgentState.ASKING_QUESTION);

        // 添加追问到对话历史
        context.addMessage(AgentMessage.assistant(AgentMessageType.FOLLOWUP, followUpQuestion));

        return context;
    }

    /**
     * 进入下一题
     */
    private AgentContext proceedToNextQuestion(AgentContext context) {
        if (context.hasMoreQuestions()) {
            context.incrementQuestionIndex();
            context.transitionTo(AgentState.ASKING_QUESTION);

            // 发送下一题
            InterviewQuestionDTO nextQuestion = context.getCurrentQuestion(objectMapper);
            if (nextQuestion != null) {
                context.addMessage(AgentMessage.assistant(AgentMessageType.QUESTION, nextQuestion.question()));
            }
        } else {
            // 没有更多问题，完成面试
            completeInterview(context);
        }
        return context;
    }

    /**
     * 完成面试
     */
    private AgentContext completeInterview(AgentContext context) {
        log.info("MasterInterviewAgent 完成面试，会话: {}", context.getSessionId());
        context.transitionTo(AgentState.SUMMARIZING);

        // 计算总分
        Integer averageScore = context.calculateAverageScore();
        if (averageScore != null) {
            context.setOverallScore(averageScore);
        }

        context.transitionTo(AgentState.COMPLETED);
        context.addMessage(AgentMessage.system(AgentMessageType.SUMMARY, "面试已完成"));

        return context;
    }

    /**
     * 更新问题列表中的答案和评分
     */
    private void updateQuestionWithEvaluation(
            AgentContext context,
            InterviewQuestionDTO question,
            String answer,
            FollowUpEvaluationResponse evalResponse) {

        List<InterviewQuestionDTO> questions = new ArrayList<>(context.getQuestions(objectMapper));
        int index = context.getCurrentQuestionIndex();

        if (index >= 0 && index < questions.size()) {
            InterviewQuestionDTO updated = questions.get(index)
                .withAnswer(answer)
                .withEvaluation(evalResponse.score(), evalResponse.feedback());
            questions.set(index, updated);
            context.setQuestions(questions, objectMapper);
        }
    }

    /**
     * 主动决策下一步（可选，用于特殊情况）
     */
    public InterviewDecision decideNextAction(AgentContext context) {
        log.info("MasterInterviewAgent 主动决策下一步，会话: {}", context.getSessionId());

        try {
            String systemPrompt = systemPromptTemplate.render();
            Map<String, Object> variables = buildDecisionVariables(context);
            String userPrompt = userPromptTemplate.render(variables);
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            MasterDecisionResponse decision = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.INTERNAL_ERROR,
                "主 Agent 决策失败：",
                "MasterInterviewAgent Decision",
                log
            );

            return parseDecision(decision.decision());

        } catch (Exception e) {
            log.warn("MasterInterviewAgent 主动决策失败，使用默认逻辑: {}", e.getMessage());
            return defaultDecision(context);
        }
    }

    /**
     * 构建决策变量
     */
    private Map<String, Object> buildDecisionVariables(AgentContext context) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("sessionId", context.getSessionId());
        variables.put("currentState", context.getState());
        variables.put("currentQuestionIndex", context.getCurrentQuestionIndex());
        variables.put("totalQuestions", context.getTotalQuestions());
        variables.put("followUpDepth", context.getFollowUpDepth());
        variables.put("maxFollowUps", context.getMaxFollowUps());

        // 当前问题
        InterviewQuestionDTO currentQuestion = context.getCurrentQuestion(objectMapper);
        variables.put("currentQuestion", currentQuestion != null ? currentQuestion.question() : "无");

        // 评分摘要
        StringBuilder scoresBuilder = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : context.getQuestionScores().entrySet()) {
            scoresBuilder.append("问题 ").append(entry.getKey()).append(": ").append(entry.getValue()).append("分\n");
        }
        variables.put("questionScores", scoresBuilder.length() > 0 ? scoresBuilder.toString() : "暂无评分");

        // 对话历史（最近 5 条）
        List<AgentMessage> history = context.getConversationHistory();
        int start = Math.max(0, history.size() - 5);
        StringBuilder historyBuilder = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            AgentMessage msg = history.get(i);
            historyBuilder.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }
        variables.put("conversationHistory", historyBuilder.length() > 0 ? historyBuilder.toString() : "暂无对话历史");

        // 简历摘要（前 200 字符）
        String resumeText = context.getResumeText();
        variables.put("resumeSummary", resumeText != null && resumeText.length() > 200
            ? resumeText.substring(0, 200) + "..."
            : resumeText != null ? resumeText : "无简历信息");

        return variables;
    }

    /**
     * 解析决策字符串
     */
    private InterviewDecision parseDecision(String decisionStr) {
        if (decisionStr == null) {
            return InterviewDecision.CONTINUE_WITH_NEXT;
        }
        try {
            return InterviewDecision.valueOf(decisionStr);
        } catch (Exception e) {
            log.warn("未知决策: {}, 使用默认值", decisionStr);
            return InterviewDecision.CONTINUE_WITH_NEXT;
        }
    }

    /**
     * 默认决策逻辑
     */
    private InterviewDecision defaultDecision(AgentContext context) {
        if (!context.hasMoreQuestions()) {
            return InterviewDecision.COMPLETE_INTERVIEW;
        }
        return InterviewDecision.CONTINUE_WITH_NEXT;
    }
}
