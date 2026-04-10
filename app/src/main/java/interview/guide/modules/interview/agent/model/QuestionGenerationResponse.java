package interview.guide.modules.interview.agent.model;

import interview.guide.modules.interview.model.InterviewQuestionDTO;

import java.util.List;

/**
 * 问题生成响应
 */
public record QuestionGenerationResponse(
    List<InterviewQuestionDTO> questions,
    String reasoning,
    List<String> coveredTopics,
    List<String> suggestedTopics
) {
    public static QuestionGenerationResponse create(
            List<InterviewQuestionDTO> questions,
            String reasoning) {
        return new QuestionGenerationResponse(questions, reasoning, List.of(), List.of());
    }

    public static QuestionGenerationResponse empty(String reason) {
        return new QuestionGenerationResponse(List.of(), reason, List.of(), List.of());
    }
}
