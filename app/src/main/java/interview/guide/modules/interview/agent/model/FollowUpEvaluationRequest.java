package interview.guide.modules.interview.agent.model;

import interview.guide.modules.interview.model.InterviewQuestionDTO;

import java.util.List;

/**
 * 追问评估请求
 */
public record FollowUpEvaluationRequest(
    InterviewQuestionDTO currentQuestion,
    String userAnswer,
    List<AgentMessage> conversationHistory,
    String resumeText,
    int followUpCount,
    int maxFollowUps
) {
    public static FollowUpEvaluationRequest create(
            InterviewQuestionDTO question,
            String answer,
            String resumeText) {
        return new FollowUpEvaluationRequest(question, answer, List.of(), resumeText, 0, 2);
    }

    public static FollowUpEvaluationRequest withHistory(
            InterviewQuestionDTO question,
            String answer,
            List<AgentMessage> history,
            String resumeText,
            int followUpCount,
            int maxFollowUps) {
        return new FollowUpEvaluationRequest(question, answer, history, resumeText, followUpCount, maxFollowUps);
    }
}
