package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentRunResponse;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 角色活动轨迹持久化：{@code role_activities} JSON 按角色去重追加，失败静默降级。
 */
@Service
public class AgentRoleRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentRoleRecorder.class);

    private final AgentRunRepository repository;
    private final ObjectMapper objectMapper;

    public AgentRoleRecorder(AgentRunRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 记录启动上下文中的角色（先按 runId+userId 重新读取最新 Run，避免覆盖并发更新）。 */
    public void record(PreparedRun prepared, String role) {
        AgentRun current = repository.findRunOwned(prepared.runId(), prepared.userId()).orElse(null);
        if (current != null) {
            record(current, role);
        }
    }

    public void record(AgentRun run, String role) {
        try {
            List<AgentRunResponse.RoleActivity> roles = new ArrayList<>(parseRoles(run.roleActivities()));
            if (roles.stream().noneMatch(item -> item.role().equals(role))) {
                roles.add(new AgentRunResponse.RoleActivity(role, "DONE"));
            }
            String rolesJson = objectMapper.writeValueAsString(roles);
            repository.updateRunRoute(run.id(), run.route(), rolesJson, run.knowledgeSources());
        } catch (IOException | RuntimeException ex) {
            log.debug("agent_role_persist_skipped runId={} role={}", run.id(), role);
        }
    }

    public List<AgentRunResponse.RoleActivity> parseRoles(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentRunResponse.RoleActivity>>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }
}
