package interview.guide.modules.interview.agent.model;

/**
 * Agent 状态枚举
 */
public enum AgentState {
    INITIALIZING,           // 初始化中
    GENERATING_QUESTIONS,   // 生成问题中
    ASKING_QUESTION,        // 提问中
    WAITING_FOR_ANSWER,     // 等待用户回答
    EVALUATING_ANSWER,      // 评估回答中
    DECIDING_FOLLOWUP,      // 决策追题中
    GENERATING_FOLLOWUP,    // 生成追问中
    SUMMARIZING,            // 汇总评分中
    COMPLETED,              // 已完成
    ERROR                   // 错误状态
}
