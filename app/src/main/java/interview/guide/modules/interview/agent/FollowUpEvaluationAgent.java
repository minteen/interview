package interview.guide.modules.interview.agent;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.agent.model.AgentMessage;
import interview.guide.modules.interview.agent.model.FollowUpEvaluationRequest;
import interview.guide.modules.interview.agent.model.FollowUpEvaluationResponse;
import interview.guide.modules.interview.agent.model.InterviewDecision;
import interview.guide.modules.interview.agent.model.ReActThought;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 追问评估子 Agent（ReAct 风格推理）
 * 负责单题内的多轮对话管理、判断是否需要追问、评估答案质量并评分
 */
@Component
public class FollowUpEvaluationAgent {

    private static final Logger log = LoggerFactory.getLogger(FollowUpEvaluationAgent.class);

    // ReAct 模式匹配正则
    private static final Pattern THOUGHT_PATTERN = Pattern.compile("Thought:\\s*(.+?)(?=\\nAction:|\\nObservation:|\\nFinal Answer:|$)", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(.+?)(?=\\nObservation:|\\nFinal Answer:|$)", Pattern.DOTALL);
    private static final Pattern OBSERVATION_PATTERN = Pattern.compile("Observation:\\s*(.+?)(?=\\nThought:|\\nFinal Answer:|$)", Pattern.DOTALL);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile("Final Answer:\\s*(.+)", Pattern.DOTALL);

    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<FollowUpEvaluationAgentResponse> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;

    // 内部响应 DTO
    private record FollowUpEvaluationAgentResponse(
        int score,
        String feedback,
        List<String> strengths,
        List<String> weaknesses,
        boolean shouldFollowUp,
        String followUpQuestion,
        String referenceAnswer,
        String finalDecision
    ) {}

    public FollowUpEvaluationAgent(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            @Value("classpath:prompts/agent/followup-evaluation-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/agent/followup-evaluation-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(FollowUpEvaluationAgentResponse.class);
    }

    /**
     * 使用 ReAct 框架评估回答
     */
    public FollowUpEvaluationResponse evaluateWithReAct(FollowUpEvaluationRequest request) {
        log.info("FollowUpEvaluationAgent 开始评估，问题: {}, 追问次数: {}/{}",
            request.currentQuestion().question(), request.followUpCount(), request.maxFollowUps());

        try {
            // 检查是否是无效回答
            if (isInvalidAnswer(request.userAnswer())) {
                log.info("检测到无效回答，直接返回 0 分");
                return createInvalidAnswerResponse(request);
            }

            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();

            // 构建用户提示词
            Map<String, Object> variables = buildUserPromptVariables(request);
            String userPrompt = userPromptTemplate.render(variables);

            // 调用 AI 获取 ReAct 风格响应
            String reactResponse = callAiForReAct(systemPrompt, userPrompt);

            // 解析 ReAct 响应
            ReActParseResult parseResult = parseReActResponse(reactResponse);

            // 解析最终答案
            FollowUpEvaluationAgentResponse finalAnswer = parseFinalAnswer(parseResult.finalAnswerJson);

            // 构建完整响应
            FollowUpEvaluationResponse response = buildEvaluationResponse(finalAnswer, parseResult.reasoningSteps, request);

            log.info("FollowUpEvaluationAgent 评估完成，得分: {}, 是否追问: {}",
                response.score(), response.shouldFollowUp());

            return response;

        } catch (Exception e) {
            log.error("FollowUpEvaluationAgent 评估失败: {}", e.getMessage(), e);
            return FollowUpEvaluationResponse.empty();
        }
    }

    /**
     * 检查是否是无效回答
     */
    private boolean isInvalidAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String lowerAnswer = answer.toLowerCase().trim();
        String[] invalidKeywords = {
            "不知道", "忘记了", "不会", "不清楚", "没学过", "跳过",
            "don't know", "forgot", "no idea", "not sure", "skip"
        };
        for (String keyword : invalidKeywords) {
            if (lowerAnswer.contains(keyword)) {
                return true;
            }
        }
        return lowerAnswer.length() < 5; // 太短的回答也视为无效
    }

    /**
     * 创建无效回答响应
     */
    private FollowUpEvaluationResponse createInvalidAnswerResponse(FollowUpEvaluationRequest request) {
        return new FollowUpEvaluationResponse(
            0,
            "候选人表示不了解该问题或放弃作答。",
            List.of(),
            List.of("建议加强该技术领域的学习"),
            false,
            null,
            generateDefaultReferenceAnswer(request),
            List.of(),
            InterviewDecision.CONCLUDE_TOPIC
        );
    }

    /**
     * 生成默认参考答案
     */
    private String generateDefaultReferenceAnswer(FollowUpEvaluationRequest request) {
        return "关于「" + request.currentQuestion().question() + "」的详细解答建议查阅相关技术文档和最佳实践。";
    }

    /**
     * 构建用户提示词变量
     */
    private Map<String, Object> buildUserPromptVariables(FollowUpEvaluationRequest request) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("question", request.currentQuestion().question());
        variables.put("userAnswer", request.userAnswer());
        variables.put("followUpCount", request.followUpCount());
        variables.put("maxFollowUps", request.maxFollowUps());
        variables.put("questionType", request.currentQuestion().type());
        variables.put("questionCategory", request.currentQuestion().category());

        // 对话历史
        List<AgentMessage> history = request.conversationHistory();
        if (history != null && !history.isEmpty()) {
            StringBuilder historyBuilder = new StringBuilder();
            for (AgentMessage msg : history) {
                historyBuilder.append(msg.role()).append(": ").append(msg.content()).append("\n");
            }
            variables.put("conversationHistory", historyBuilder.toString());
        } else {
            variables.put("conversationHistory", "暂无对话历史");
        }

        // 简历上下文（截取前 500 字符）
        String resumeText = request.resumeText();
        if (resumeText != null && resumeText.length() > 500) {
            variables.put("resumeContext", resumeText.substring(0, 500) + "...");
        } else {
            variables.put("resumeContext", resumeText != null ? resumeText : "无简历信息");
        }

        return variables;
    }

    /**
     * 调用 AI 获取 ReAct 响应
     */
    private String callAiForReAct(String systemPrompt, String userPrompt) {
        try {
            return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        } catch (Exception e) {
            log.error("ReAct AI 调用失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "ReAct 推理失败: " + e.getMessage());
        }
    }

    /**
     * 解析 ReAct 响应
     */
    private ReActParseResult parseReActResponse(String response) {
        List<ReActThought> reasoningSteps = new ArrayList<>();
        String finalAnswerJson = "";

        if (response == null || response.isBlank()) {
            return new ReActParseResult(reasoningSteps, "{}");
        }

        // 提取思考步骤
        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
        Matcher actionMatcher = ACTION_PATTERN.matcher(response);
        Matcher observationMatcher = OBSERVATION_PATTERN.matcher(response);

        int step = 1;
        while (thoughtMatcher.find()) {
            String thought = thoughtMatcher.group(1).trim();
            String action = actionMatcher.find() ? actionMatcher.group(1).trim() : null;
            String observation = observationMatcher.find() ? observationMatcher.group(1).trim() : null;

            reasoningSteps.add(ReActThought.create(step++, thought, action, observation));
        }

        // 提取最终答案
        Matcher finalAnswerMatcher = FINAL_ANSWER_PATTERN.matcher(response);
        if (finalAnswerMatcher.find()) {
            finalAnswerJson = finalAnswerMatcher.group(1).trim();
        } else {
            // 如果没有明确的 Final Answer 标记，尝试直接解析整个响应
            finalAnswerJson = response.trim();
        }

        // 清理 JSON（移除可能的 Markdown 代码块）
        finalAnswerJson = cleanJson(finalAnswerJson);

        log.debug("解析到 {} 个 ReAct 思考步骤", reasoningSteps.size());
        return new ReActParseResult(reasoningSteps, finalAnswerJson);
    }

    /**
     * 清理 JSON 字符串
     */
    private String cleanJson(String json) {
        if (json == null) {
            return "{}";
        }
        // 移除 Markdown 代码块
        json = json.replaceAll("```json", "").replaceAll("```", "").trim();
        // 确保以 { 开头
        int startIdx = json.indexOf('{');
        if (startIdx > 0) {
            json = json.substring(startIdx);
        }
        return json;
    }

    /**
     * 解析最终答案
     */
    private FollowUpEvaluationAgentResponse parseFinalAnswer(String json) {
        try {
            return outputConverter.convert(json);
        } catch (Exception e) {
            log.warn("解析最终答案失败，使用默认值: {}", e.getMessage());
            // 返回一个保守的默认响应
            return new FollowUpEvaluationAgentResponse(
                60,
                "回答基本正确，但缺乏深度。",
                List.of("对基本概念有一定了解"),
                List.of("需要加强对技术原理的深入理解"),
                false,
                null,
                "建议进一步学习相关技术的底层原理。",
                "CONCLUDE_TOPIC"
            );
        }
    }

    /**
     * 构建评估响应
     */
    private FollowUpEvaluationResponse buildEvaluationResponse(
            FollowUpEvaluationAgentResponse agentResponse,
            List<ReActThought> reasoningSteps,
            FollowUpEvaluationRequest request) {

        // 解析决策
        InterviewDecision decision;
        try {
            decision = InterviewDecision.valueOf(agentResponse.finalDecision());
        } catch (Exception e) {
            decision = agentResponse.shouldFollowUp() ? InterviewDecision.ASK_FOLLOWUP : InterviewDecision.CONCLUDE_TOPIC;
        }

        // 如果达到最大追问次数，强制结束
        if (request.followUpCount() >= request.maxFollowUps()) {
            decision = InterviewDecision.CONCLUDE_TOPIC;
        }

        return new FollowUpEvaluationResponse(
            Math.max(0, Math.min(100, agentResponse.score())), // 确保分数在 0-100 之间
            agentResponse.feedback() != null ? agentResponse.feedback() : "暂无反馈",
            agentResponse.strengths() != null ? agentResponse.strengths() : List.of(),
            agentResponse.weaknesses() != null ? agentResponse.weaknesses() : List.of(),
            decision == InterviewDecision.ASK_FOLLOWUP,
            decision == InterviewDecision.ASK_FOLLOWUP ? agentResponse.followUpQuestion() : null,
            agentResponse.referenceAnswer() != null ? agentResponse.referenceAnswer() : "",
            reasoningSteps,
            decision
        );
    }

    // 内部解析结果记录
    private record ReActParseResult(
        List<ReActThought> reasoningSteps,
        String finalAnswerJson
    ) {}
}
