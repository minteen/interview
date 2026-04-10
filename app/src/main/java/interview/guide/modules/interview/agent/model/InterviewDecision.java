package interview.guide.modules.interview.agent.model;

/**
 * 面试决策枚举
 */
public enum InterviewDecision {
    CONTINUE_WITH_NEXT,    // 继续下一题
    ASK_FOLLOWUP,          // 追问
    CONCLUDE_TOPIC,        // 结束本话题
    COMPLETE_INTERVIEW     // 结束面试
}
