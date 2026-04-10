package interview.guide.modules.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.agent.model.QuestionGenerationRequest;
import interview.guide.modules.interview.agent.model.QuestionGenerationResponse;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionType;
import interview.guide.modules.interview.service.InterviewQuestionService;
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
import java.util.stream.Collectors;

/**
 * 问题生成子 Agent
 * 基于简历内容生成结构化的面试问题列表
 */
@Component
public class QuestionGenerationAgent {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationAgent.class);

    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<QuestionGenerationAgentResponse> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewQuestionService interviewQuestionService;
    private final ObjectMapper objectMapper;

    // 内部响应 DTO
    private record QuestionGenerationAgentResponse(
        List<QuestionDTO> questions,
        String reasoning,
        List<String> coveredTopics,
        List<String> suggestedTopics
    ) {}

    private record QuestionDTO(
        String question,
        String type,
        String category,
        List<String> followUps
    ) {}

    public QuestionGenerationAgent(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewQuestionService interviewQuestionService,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/agent/question-generation-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/agent/question-generation-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.interviewQuestionService = interviewQuestionService;
        this.objectMapper = objectMapper;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(QuestionGenerationAgentResponse.class);
    }

    /**
     * 生成面试问题
     */
    public QuestionGenerationResponse generateQuestions(QuestionGenerationRequest request) {
        log.info("QuestionGenerationAgent 开始生成问题，简历长度: {}, 问题数量: {}",
            request.resumeText().length(), request.questionCount());

        try {
            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();

            // 构建用户提示词变量
            Map<String, Object> variables = buildUserPromptVariables(request);
            String userPrompt = userPromptTemplate.render(variables);

            // 添加格式指令
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            // 调用 AI
            QuestionGenerationAgentResponse response;
            try {
                response = structuredOutputInvoker.invoke(
                    chatClient,
                    systemPromptWithFormat,
                    userPrompt,
                    outputConverter,
                    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "Agent 问题生成失败：",
                    "QuestionGenerationAgent",
                    log
                );
            } catch (Exception e) {
                log.warn("QuestionGenerationAgent AI 调用失败，降级使用传统服务: {}", e.getMessage());
                return fallbackToTraditionalService(request);
            }

            // 转换为业务对象
            List<InterviewQuestionDTO> questions = convertToQuestions(response);
            log.info("QuestionGenerationAgent 成功生成 {} 个问题", questions.size());

            return new QuestionGenerationResponse(
                questions,
                response.reasoning(),
                response.coveredTopics() != null ? response.coveredTopics() : List.of(),
                response.suggestedTopics() != null ? response.suggestedTopics() : List.of()
            );

        } catch (Exception e) {
            log.error("QuestionGenerationAgent 生成问题失败: {}", e.getMessage(), e);
            return fallbackToTraditionalService(request);
        }
    }

    /**
     * 降级到传统问题生成服务
     */
    private QuestionGenerationResponse fallbackToTraditionalService(QuestionGenerationRequest request) {
        log.info("QuestionGenerationAgent 降级使用传统问题生成服务");
        List<InterviewQuestionDTO> questions = interviewQuestionService.generateQuestions(
            request.resumeText(),
            request.questionCount(),
            request.historicalQuestions()
        );
        return QuestionGenerationResponse.create(questions, "使用传统问题生成服务（降级）");
    }

    /**
     * 构建用户提示词变量
     */
    private Map<String, Object> buildUserPromptVariables(QuestionGenerationRequest request) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", request.resumeText());
        variables.put("questionCount", request.questionCount());

        // 难度分布
        QuestionGenerationRequest.DifficultyDistribution distribution = request.difficultyDistribution();
        if (distribution != null) {
            variables.put("basicPct", distribution.basicPercentage());
            variables.put("advancedPct", distribution.advancedPercentage());
            variables.put("expertPct", distribution.expertPercentage());
        } else {
            variables.put("basicPct", 30);
            variables.put("advancedPct", 50);
            variables.put("expertPct", 20);
        }

        // 历史问题
        List<String> historicalQuestions = request.historicalQuestions();
        if (historicalQuestions != null && !historicalQuestions.isEmpty()) {
            variables.put("historicalQuestions", String.join("\n", historicalQuestions));
        } else {
            variables.put("historicalQuestions", "暂无历史提问");
        }

        // 重点话题
        List<String> focusTopics = request.focusTopics();
        if (focusTopics != null && !focusTopics.isEmpty()) {
            variables.put("focusTopics", String.join(", ", focusTopics));
        } else {
            variables.put("focusTopics", "无特定重点话题");
        }

        return variables;
    }

    /**
     * 转换为业务问题对象
     */
    private List<InterviewQuestionDTO> convertToQuestions(QuestionGenerationAgentResponse response) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (response == null || response.questions() == null) {
            return questions;
        }

        for (QuestionDTO q : response.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }

            QuestionType type = parseQuestionType(q.type());
            int mainQuestionIndex = index;

            // 添加主问题
            questions.add(InterviewQuestionDTO.create(
                index++,
                q.question(),
                type,
                q.category(),
                false,
                null
            ));

            // 添加追问
            List<String> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                    index++,
                    followUps.get(i),
                    type,
                    buildFollowUpCategory(q.category(), i + 1),
                    true,
                    mainQuestionIndex
                ));
            }
        }

        return questions;
    }

    private QuestionType parseQuestionType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return QuestionType.JAVA_BASIC;
        }
        try {
            return QuestionType.valueOf(typeStr.toUpperCase());
        } catch (Exception e) {
            log.warn("未知的问题类型: {}, 使用默认值 JAVA_BASIC", typeStr);
            return QuestionType.JAVA_BASIC;
        }
    }

    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(2) // 最多 2 个追问
            .collect(Collectors.toList());
    }

    private String buildFollowUpCategory(String category, int order) {
        String baseCategory = (category == null || category.isBlank()) ? "追问" : category;
        return baseCategory + "（追问" + order + "）";
    }
}
