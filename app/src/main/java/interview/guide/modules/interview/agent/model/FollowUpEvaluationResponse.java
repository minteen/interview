package interview.guide.modules.interview.agent.model;

import java.util.List;

/**
 * 追问评估响应
 */
public record FollowUpEvaluationResponse(
    int score,                          // 得分 0-100
    String feedback,                    // 反馈
    List<String> strengths,             // 优势
    List<String> weaknesses,            // 不足
    boolean shouldFollowUp,             // 是否需要追问
    String followUpQuestion,            // 追问内容（如需要）
    String referenceAnswer,             // 参考答案
    List<ReActThought> reasoningSteps, // ReAct 推理步骤
    InterviewDecision finalDecision     // 最终决策
) {
    public static FollowUpEvaluationResponse evaluateOnly(
            int score,
            String feedback,
            List<String> strengths,
            List<String> weaknesses,
            String referenceAnswer) {
        return new FollowUpEvaluationResponse(
            score, feedback, strengths, weaknesses,
            false, null, referenceAnswer, List.of(), InterviewDecision.CONCLUDE_TOPIC
        );
    }

    public static FollowUpEvaluationResponse withFollowUp(
            int score,
            String feedback,
            List<String> strengths,
            List<String> weaknesses,
            String followUpQuestion,
            String referenceAnswer,
            List<ReActThought> steps) {
        return new FollowUpEvaluationResponse(
            score, feedback, strengths, weaknesses,
            true, followUpQuestion, referenceAnswer, steps, InterviewDecision.ASK_FOLLOWUP
        );
    }

    public static FollowUpEvaluationResponse empty() {
        return new FollowUpEvaluationResponse(
            0, "无法评估", List.of(), List.of(),
            false, null, null, List.of(), InterviewDecision.CONCLUDE_TOPIC
        );
    }
}
