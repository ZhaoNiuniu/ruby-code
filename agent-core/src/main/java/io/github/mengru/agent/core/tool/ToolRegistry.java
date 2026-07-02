package io.github.mengru.agent.core.tool;

import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.scheduler.ScheduledTaskManager;
import io.github.mengru.agent.core.skill.LoadSkillTool;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.team.TeamRuntime;
import io.github.mengru.agent.core.task.TaskManager;
import io.github.mengru.agent.core.tool.local.BashTool;
import io.github.mengru.agent.core.tool.local.EditFileTool;
import io.github.mengru.agent.core.tool.local.GlobTool;
import io.github.mengru.agent.core.tool.local.ReadFileTool;
import io.github.mengru.agent.core.tool.local.WriteFileTool;
import io.github.mengru.agent.core.tool.schedule.CancelScheduledTaskTool;
import io.github.mengru.agent.core.tool.schedule.ListScheduledTasksTool;
import io.github.mengru.agent.core.tool.schedule.ScheduleTaskTool;
import io.github.mengru.agent.core.tool.subagent.SubagentTool;
import io.github.mengru.agent.core.tool.task.CanStartTool;
import io.github.mengru.agent.core.tool.task.ClaimTaskTool;
import io.github.mengru.agent.core.tool.task.CompleteTaskTool;
import io.github.mengru.agent.core.tool.task.CreateTaskTool;
import io.github.mengru.agent.core.tool.task.GetTaskTool;
import io.github.mengru.agent.core.tool.task.ListTasksTool;
import io.github.mengru.agent.core.tool.team.ListTeammatesTool;
import io.github.mengru.agent.core.tool.team.SendMessageTool;
import io.github.mengru.agent.core.tool.team.SpawnTeammateTool;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> toolsByName;

    private ToolRegistry(Collection<Tool> tools) {
        LinkedHashMap<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Objects.requireNonNull(tool, "tool must not be null");
            if (tool.name() == null || tool.name().isBlank()) {
                throw new IllegalArgumentException("Tool name must not be blank");
            }
            Tool previous = byName.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
        }
        this.tools = List.copyOf(byName.values());
        this.toolsByName = Map.copyOf(byName);
    }

    public static ToolRegistry defaultTools() {
        return defaultToolsWithSkills(SkillCatalog.empty());
    }

    public static ToolRegistry defaultToolsWithSkills(SkillCatalog skillCatalog) {
        return defaultToolsWithSkills(skillCatalog, TaskManager.defaultManager());
    }

    public static ToolRegistry defaultToolsWithSkills(SkillCatalog skillCatalog, TaskManager taskManager) {
        Builder builder = builder()
                .add(new TodoWriteTool());
        addLoadSkillTool(builder, skillCatalog);
        addTaskTools(builder, taskManager);
        return builder
                .add(new BashTool())
                .add(new ReadFileTool())
                .add(new WriteFileTool())
                .add(new EditFileTool())
                .add(new GlobTool())
                .build();
    }

    public static ToolRegistry defaultToolsWithSubagent(ModelClient modelClient, UserApprover userApprover) {
        return defaultToolsWithSubagent(modelClient, userApprover, SkillCatalog.empty());
    }

    public static ToolRegistry defaultToolsWithSubagent(ModelClient modelClient, UserApprover userApprover, SkillCatalog skillCatalog) {
        return defaultToolsWithSubagent(modelClient, userApprover, skillCatalog, MemoryCatalog.empty(java.nio.file.Path.of("")));
    }

    public static ToolRegistry defaultToolsWithSubagent(
            ModelClient modelClient,
            UserApprover userApprover,
            SkillCatalog skillCatalog,
            MemoryCatalog memoryCatalog
    ) {
        return defaultToolsWithSubagent(modelClient, userApprover, skillCatalog, memoryCatalog, null);
    }

    public static ToolRegistry defaultToolsWithSubagent(
            ModelClient modelClient,
            UserApprover userApprover,
            SkillCatalog skillCatalog,
            MemoryCatalog memoryCatalog,
            ScheduledTaskManager scheduledTaskManager
    ) {
        return defaultToolsWithSubagent(modelClient, userApprover, skillCatalog, memoryCatalog, scheduledTaskManager, TaskManager.defaultManager());
    }

    public static ToolRegistry defaultToolsWithSubagent(
            ModelClient modelClient,
            UserApprover userApprover,
            SkillCatalog skillCatalog,
            MemoryCatalog memoryCatalog,
            ScheduledTaskManager scheduledTaskManager,
            TaskManager taskManager
    ) {
        return defaultToolsWithSubagent(
                modelClient,
                userApprover,
                skillCatalog,
                memoryCatalog,
                scheduledTaskManager,
                taskManager,
                null
        );
    }

    public static ToolRegistry defaultToolsWithSubagent(
            ModelClient modelClient,
            UserApprover userApprover,
            SkillCatalog skillCatalog,
            MemoryCatalog memoryCatalog,
            ScheduledTaskManager scheduledTaskManager,
            TaskManager taskManager,
            TeamRuntime teamRuntime
    ) {
        Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
        Objects.requireNonNull(memoryCatalog, "memoryCatalog must not be null");
        Objects.requireNonNull(taskManager, "taskManager must not be null");
        Builder builder = builder()
                .add(new TodoWriteTool());
        addLoadSkillTool(builder, skillCatalog);
        addTaskTools(builder, taskManager);
        builder.add(new SubagentTool(
                Objects.requireNonNull(modelClient, "modelClient must not be null"),
                Objects.requireNonNull(userApprover, "userApprover must not be null"),
                skillCatalog,
                memoryCatalog,
                taskManager
        ));
        addScheduleTools(builder, scheduledTaskManager);
        addLeadTeamTools(builder, teamRuntime);
        return builder
                .add(new BashTool())
                .add(new ReadFileTool())
                .add(new WriteFileTool())
                .add(new EditFileTool())
                .add(new GlobTool())
                .build();
    }

    public static ToolRegistry teammateTools(TeamRuntime teamRuntime, TaskManager taskManager) {
        Objects.requireNonNull(teamRuntime, "teamRuntime must not be null");
        Objects.requireNonNull(taskManager, "taskManager must not be null");
        Builder builder = builder()
                .add(new SendMessageTool(teamRuntime));
        addTaskTools(builder, taskManager);
        return builder
                .add(new BashTool())
                .add(new ReadFileTool())
                .add(new WriteFileTool())
                .add(new EditFileTool())
                .add(new GlobTool())
                .build();
    }

    public static ToolRegistry investigationTools() {
        return investigationTools(SkillCatalog.empty());
    }

    public static ToolRegistry investigationTools(SkillCatalog skillCatalog) {
        return investigationTools(skillCatalog, TaskManager.defaultManager());
    }

    public static ToolRegistry investigationTools(SkillCatalog skillCatalog, TaskManager taskManager) {
        Builder builder = builder()
                .add(new TodoWriteTool());
        addLoadSkillTool(builder, skillCatalog);
        addReadOnlyTaskTools(builder, taskManager);
        return builder
                .add(new ReadFileTool())
                .add(new GlobTool())
                .add(new BashTool())
                .build();
    }

    private static void addLoadSkillTool(Builder builder, SkillCatalog skillCatalog) {
        Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
        if (!skillCatalog.isEmpty()) {
            builder.add(new LoadSkillTool(skillCatalog));
        }
    }

    private static void addScheduleTools(Builder builder, ScheduledTaskManager scheduledTaskManager) {
        if (scheduledTaskManager != null) {
            builder.add(new ScheduleTaskTool(scheduledTaskManager))
                    .add(new ListScheduledTasksTool(scheduledTaskManager))
                    .add(new CancelScheduledTaskTool(scheduledTaskManager));
        }
    }

    private static void addLeadTeamTools(Builder builder, TeamRuntime teamRuntime) {
        if (teamRuntime != null) {
            builder.add(new SpawnTeammateTool(teamRuntime))
                    .add(new SendMessageTool(teamRuntime))
                    .add(new ListTeammatesTool(teamRuntime));
        }
    }

    private static void addTaskTools(Builder builder, TaskManager taskManager) {
        Objects.requireNonNull(taskManager, "taskManager must not be null");
        addReadOnlyTaskTools(builder, taskManager);
        builder.add(new CreateTaskTool(taskManager))
                .add(new ClaimTaskTool(taskManager))
                .add(new CompleteTaskTool(taskManager));
    }

    private static void addReadOnlyTaskTools(Builder builder, TaskManager taskManager) {
        Objects.requireNonNull(taskManager, "taskManager must not be null");
        builder.add(new ListTasksTool(taskManager))
                .add(new GetTaskTool(taskManager))
                .add(new CanStartTool(taskManager));
    }

    public static ToolRegistry of(Collection<Tool> tools) {
        return new ToolRegistry(Objects.requireNonNull(tools, "tools must not be null"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Tool> tools() {
        return tools;
    }

    public Optional<Tool> findByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public static final class Builder {

        private final List<Tool> tools = new ArrayList<>();

        public Builder add(Tool tool) {
            tools.add(Objects.requireNonNull(tool, "tool must not be null"));
            return this;
        }

        public Builder addAll(Collection<Tool> tools) {
            this.tools.addAll(Objects.requireNonNull(tools, "tools must not be null"));
            return this;
        }

        public ToolRegistry build() {
            return new ToolRegistry(tools);
        }
    }
}
