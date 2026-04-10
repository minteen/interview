package interview.guide.modules.interview.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 问题生成请求
 */
public record QuestionGenerationRequest(
    String resumeText,
    int questionCount,
    Map<String, Integer> topicWeights,
    DifficultyDistribution difficultyDistribution,
    List<String> historicalQuestions,
    List<String> focusTopics
) {
    public record DifficultyDistribution(
        int basicPercentage,      // 基础题占比
        int advancedPercentage,   // 进阶题占比
        int expertPercentage     // 专家题占比
    ) {
        public DifficultyDistribution {
            int total = basicPercentage + advancedPercentage + advancedPercentage;
            if (total != 100) {
                throw new IllegalArgumentException("难度分布总和必须为100");
            }
        }

        public static DifficultyDistribution defaultDistribution() {
            return new DifficultyDistribution(30, 50, 20);
        }
    }

    public static QuestionGenerationRequest createDefault(String resumeText, int questionCount) {
        return new QuestionGenerationRequest(
            resumeText,
            questionCount,
            Map.of(),
            DifficultyDistribution.defaultDistribution(),
            List.of(),
            List.of()
        );
    }
}
