package interview.guide.modules.interview.agent.model;

/**
 * ReAct 思考步骤
 */
public record ReActThought(
    int step,
    String thought,
    String action,
    String observation
) {
    public enum ActionType {
        ANALYZE_ANSWER,       // 分析回答
        CHECK_RESUME_CONTEXT,     // 检查简历上下文
        CALCULATE_SCORE,        // 计算得分
        DECIDE_FOLLOWUP,       // 决策追问
        RETRIEVE_KNOWLEDGE,     // 检索知识
        FINALIZE                 // 完成
    }

    public static ReActThought create(int step, String thought, String action, String observation) {
        return new ReActThought(step, thought, action, observation);
    }

    public static ReActThought thoughtOnly(int step, String thought) {
        return new ReActThought(step, thought, null, null);
    }

    public static ReActThought actionOnly(int step, String action) {
        return new ReActThought(step, null, action, null);
    }
}
