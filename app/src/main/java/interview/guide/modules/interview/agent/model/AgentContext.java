package interview.guide.modules.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 上下文 - 包含会话状态、对话历史、评分等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext implements Serializable {

    private static final long serialVersionUID = 1L;

    // ========== 基础信息 ==========
    private String sessionId;
    private String resumeText;
    private Long resumeId;

    // ========== 状态信息 ==========
    private AgentState state;
    private int currentQuestionIndex;
    private int totalQuestions;

    // ========== 对话与问题 ==========
    @Builder.Default
    private List<AgentMessage> conversationHistory = new ArrayList<>();

    private String questionsJson;  // 序列化的问题列表

    // ========== 评分与评估 ==========
    @Builder.Default
    private Map<Integer, Integer> questionScores = new HashMap<>();  // questionIndex -> score

    @Builder.Default
    private Map<Integer, String> questionFeedbacks = new HashMap<>();  // questionIndex -> feedback

    private String overallFeedback;
    @Builder.Default
    private List<String> strengths = new ArrayList<>();
    @Builder.Default
    private List<String> improvements = new ArrayList<>();
    private Integer overallScore;

    // ========== 话题覆盖 ==========
    @Builder.Default
    private Map<String, Integer> topicCoverage = new HashMap<>();  // topic -> coverageLevel (0-100)

    // ========== 元数据 ==========
    private long createdAt;
    private long lastUpdatedAt;
    private int followUpDepth;  // 当前追问深度
    @Builder.Default
    private int maxFollowUps = 2;  // 最大追问次数

    // ========== 临时状态（不序列化） ==========
    @JsonIgnore
    @Builder.Default
    private transient List<InterviewQuestionDTO> cachedQuestions = null;

    // ========== 工厂方法 ==========
    public static AgentContext create(String sessionId, Long resumeId, String resumeText) {
        long now = System.currentTimeMillis();
        return AgentContext.builder()
            .sessionId(sessionId)
            .resumeId(resumeId)
            .resumeText(resumeText)
            .state(AgentState.INITIALIZING)
            .currentQuestionIndex(0)
            .totalQuestions(0)
            .createdAt(now)
            .lastUpdatedAt(now)
            .followUpDepth(0)
            .maxFollowUps(2)
            .build();
    }

    // ========== 问题列表操作 ==========
    public List<InterviewQuestionDTO> getQuestions(ObjectMapper objectMapper) {
        if (cachedQuestions != null) {
            return cachedQuestions;
        }
        if (questionsJson == null || questionsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            cachedQuestions = objectMapper.readValue(
                questionsJson,
                new TypeReference<List<InterviewQuestionDTO>>() {}
            );
            return cachedQuestions;
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    public void setQuestions(List<InterviewQuestionDTO> questions, ObjectMapper objectMapper) {
        try {
            this.questionsJson = objectMapper.writeValueAsString(questions);
            this.cachedQuestions = new ArrayList<>(questions);
            this.totalQuestions = questions.size();
            this.lastUpdatedAt = System.currentTimeMillis();
        } catch (JsonProcessingException e) {
            this.questionsJson = "[]";
            this.cachedQuestions = new ArrayList<>();
        }
    }

    public InterviewQuestionDTO getCurrentQuestion(ObjectMapper objectMapper) {
        List<InterviewQuestionDTO> questions = getQuestions(objectMapper);
        if (currentQuestionIndex >= 0 && currentQuestionIndex < questions.size()) {
            return questions.get(currentQuestionIndex);
        }
        return null;
    }

    // ========== 对话历史操作 ==========
    public void addMessage(AgentMessage message) {
        conversationHistory.add(message);
        lastUpdatedAt = System.currentTimeMillis();
    }

    // ========== 评分操作 ==========
    public void setQuestionScore(int questionIndex, int score, String feedback) {
        questionScores.put(questionIndex, score);
        questionFeedbacks.put(questionIndex, feedback);
        lastUpdatedAt = System.currentTimeMillis();
    }

    public Integer calculateAverageScore() {
        if (questionScores.isEmpty()) {
            return null;
        }
        int sum = questionScores.values().stream().mapToInt(Integer::intValue).sum();
        return sum / questionScores.size();
    }

    // ========== 状态转换 ==========
    public void transitionTo(AgentState newState) {
        this.state = newState;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    public void incrementQuestionIndex() {
        this.currentQuestionIndex++;
        this.followUpDepth = 0;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    public void incrementFollowUpDepth() {
        this.followUpDepth++;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    public boolean canFollowUp() {
        return followUpDepth < maxFollowUps;
    }

    public boolean hasMoreQuestions() {
        return currentQuestionIndex < totalQuestions - 1;
    }

    // ========== 快照方法 ==========
    public AgentContext snapshot() {
        return AgentContext.builder()
            .sessionId(sessionId)
            .resumeId(resumeId)
            .resumeText(resumeText)
            .state(state)
            .currentQuestionIndex(currentQuestionIndex)
            .totalQuestions(totalQuestions)
            .conversationHistory(new ArrayList<>(conversationHistory))
            .questionsJson(questionsJson)
            .questionScores(new HashMap<>(questionScores))
            .questionFeedbacks(new HashMap<>(questionFeedbacks))
            .overallFeedback(overallFeedback)
            .strengths(new ArrayList<>(strengths))
            .improvements(new ArrayList<>(improvements))
            .overallScore(overallScore)
            .topicCoverage(new HashMap<>(topicCoverage))
            .createdAt(createdAt)
            .lastUpdatedAt(lastUpdatedAt)
            .followUpDepth(followUpDepth)
            .maxFollowUps(maxFollowUps)
            .build();
    }

    // ========== 报告生成辅助 ==========
    public void applyReport(InterviewReportDTO report) {
        this.overallScore = report.overallScore();
        this.overallFeedback = report.overallFeedback();
        this.strengths = new ArrayList<>(report.strengths());
        this.improvements = new ArrayList<>(report.improvements());
        this.lastUpdatedAt = System.currentTimeMillis();
    }
}
