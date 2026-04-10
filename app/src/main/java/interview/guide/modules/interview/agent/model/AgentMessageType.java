package interview.guide.modules.interview.agent.model;

/**
 * Agent 消息类型枚举
 */
public enum AgentMessageType {
    QUESTION,       // 问题
    ANSWER,         // 回答
    EVALUATION,     // 评估
    FOLLOWUP,       // 追问
    DECISION,       // 决策
    SUMMARY,        // 总结
    SYSTEM          // 系统消息
}
