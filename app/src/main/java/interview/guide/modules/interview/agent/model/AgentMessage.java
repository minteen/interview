package interview.guide.modules.interview.agent.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 消息
 */
public record AgentMessage(
    Role role,
    AgentMessageType type,
    String content,
    LocalDateTime timestamp,
    Map<String, Object> metadata
) {
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        AGENT
    }

    public static AgentMessage system(AgentMessageType type, String content) {
        return new AgentMessage(Role.SYSTEM, type, content, LocalDateTime.now(), new HashMap<>());
    }

    public static AgentMessage user(AgentMessageType type, String content) {
        return new AgentMessage(Role.USER, type, content, LocalDateTime.now(), new HashMap<>());
    }

    public static AgentMessage assistant(AgentMessageType type, String content) {
        return new AgentMessage(Role.ASSISTANT, type, content, LocalDateTime.now(), new HashMap<>());
    }

    public static AgentMessage agent(AgentMessageType type, String content) {
        return new AgentMessage(Role.AGENT, type, content, LocalDateTime.now(), new HashMap<>());
    }

    public AgentMessage withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put(key, value);
        return new AgentMessage(role, type, content, timestamp, newMetadata);
    }
}
