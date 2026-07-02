package io.github.mengru.agent.core.prompt;

import io.github.mengru.agent.api.AgentMemory;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ConversationMessage;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;
import io.github.mengru.agent.core.task.TaskManager;
import io.github.mengru.agent.core.team.TeamRuntime;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.tool.todo.TodoWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerTest {

    @TempDir
    Path workspace;

    @Test
    void mainPromptIncludesStableRuntimeSections() {
        ToolRegistry tools = ToolRegistry.defaultToolsWithSkills(skillCatalog());
        AgentRequest request = new AgentRequest(
                "change code",
                8,
                Map.of("trace", "abc"),
                "Prefer small diffs.",
                List.of(ConversationMessage.user("previous")),
                AgentMemory.of("## Goal\n\nKeep the session coherent.")
        );

        AgentRequest assembled = assemble(PromptMode.MAIN, tools, MemoryCatalog.empty(workspace), skillCatalog(), request);

        assertThat(assembled.systemPrompt()).contains("## identity");
        assertThat(assembled.systemPrompt()).contains("command-line agent");
        assertThat(assembled.systemPrompt()).contains("## user_instructions");
        assertThat(assembled.systemPrompt()).contains("Prefer small diffs.");
        assertThat(assembled.systemPrompt()).contains("## workspace");
        assertThat(assembled.systemPrompt()).contains(workspace.toAbsolutePath().normalize().toString());
        assertThat(assembled.systemPrompt()).contains("## tools");
        assertThat(assembled.systemPrompt()).contains("todo_write");
        assertThat(assembled.systemPrompt()).contains("## task_system");
        assertThat(assembled.systemPrompt()).contains("Durable project tasks live in .tasks/{id}.json");
        assertThat(assembled.systemPrompt()).contains("claim_task records this runtime agent as owner");
        assertThat(assembled.systemPrompt()).contains("## skill_catalog");
        assertThat(assembled.systemPrompt()).contains("java-agent: Java agent guidance");
        assertThat(assembled.systemPrompt()).contains("## todo_planning");
        assertThat(assembled.systemPrompt()).contains("## session_memory");
        assertThat(assembled.systemPrompt()).contains("Keep the session coherent");
        assertThat(assembled.metadata()).containsEntry("trace", "abc");
        assertThat(assembled.conversationHistory()).containsExactly(ConversationMessage.user("previous"));
        assertThat(assembled.memory().markdown()).contains("Keep the session coherent");
    }

    @Test
    void memoryIndexIsSystemSectionAndRelevantMemoryIsUserTurnContext() throws IOException {
        writeMemory("style.md", "tab-style", "Use tab indentation", "user", "Use tabs instead of spaces.");
        MemoryCatalog catalog = MemoryCatalog.scan(workspace);

        AgentRequest assembled = assemble(
                PromptMode.MAIN,
                ToolRegistry.builder().add(new TodoWriteTool()).build(),
                catalog,
                SkillCatalog.empty(),
                AgentRequest.of("please update indentation style with tabs")
        );

        assertThat(assembled.systemPrompt()).contains("## persistent_memory_index");
        assertThat(assembled.systemPrompt()).contains("tab-style");
        assertThat(assembled.systemPrompt()).doesNotContain("Use tabs instead of spaces.");
        assertThat(assembled.task()).contains("## Relevant Long-Term Memory");
        assertThat(assembled.task()).contains("Use tabs instead of spaces.");
    }

    @Test
    void modelIdentitySectionUsesRuntimeMetadata() {
        AgentRequest request = new AgentRequest(
                "what model are you",
                8,
                Map.of(
                        PromptAssembler.PROVIDER_METADATA_KEY, "openai-compatible",
                        PromptAssembler.MODEL_METADATA_KEY, "deepseek-chat",
                        PromptAssembler.BASE_URL_METADATA_KEY, "https://api.deepseek.com/v1"
                )
        );

        AgentRequest assembled = assemble(
                PromptMode.MAIN,
                ToolRegistry.defaultTools(),
                MemoryCatalog.empty(workspace),
                SkillCatalog.empty(),
                request
        );

        assertThat(assembled.systemPrompt()).contains("## model_identity");
        assertThat(assembled.systemPrompt()).contains("provider: openai-compatible");
        assertThat(assembled.systemPrompt()).contains("model: deepseek-chat");
        assertThat(assembled.systemPrompt()).contains("base_url: https://api.deepseek.com/v1");
        assertThat(assembled.systemPrompt()).contains("Do not claim to be Claude");
    }

    @Test
    void repeatedAssemblyIsIdempotentAndUsesCache() throws IOException {
        writeMemory("style.md", "tab-style", "Use tab indentation", "user", "Use tabs instead of spaces.");
        MemoryCatalog catalog = MemoryCatalog.scan(workspace);
        PromptAssembler assembler = new PromptAssembler();
        AgentRequest request = AgentRequest.of("please update indentation style with tabs");

        AgentRequest first = assembler.assemble(context(
                PromptMode.MAIN,
                ToolRegistry.defaultTools(),
                catalog,
                SkillCatalog.empty(),
                request
        ));
        AgentRequest second = assembler.assemble(context(
                PromptMode.MAIN,
                ToolRegistry.defaultTools(),
                catalog,
                SkillCatalog.empty(),
                first
        ));

        assertThat(second.systemPrompt()).isEqualTo(first.systemPrompt());
        assertThat(second.task()).isEqualTo(first.task());
        assertThat(assembler.cacheSize()).isEqualTo(1);
        assertThat(assembler.cacheHits()).isEqualTo(1);
    }

    @Test
    void changedSessionMemoryMissesCache() {
        PromptAssembler assembler = new PromptAssembler();
        ToolRegistry tools = ToolRegistry.defaultTools();

        assembler.assemble(context(
                PromptMode.MAIN,
                tools,
                MemoryCatalog.empty(workspace),
                SkillCatalog.empty(),
                AgentRequest.of("task").withMemory(AgentMemory.of("first"))
        ));
        assembler.assemble(context(
                PromptMode.MAIN,
                tools,
                MemoryCatalog.empty(workspace),
                SkillCatalog.empty(),
                AgentRequest.of("task").withMemory(AgentMemory.of("second"))
        ));

        assertThat(assembler.cacheSize()).isEqualTo(2);
        assertThat(assembler.cacheHits()).isZero();
    }

    @Test
    void subagentPromptUsesSubagentIdentityAndExpectedOutput() {
        AgentRequest request = new AgentRequest(
                "trace call chain",
                6,
                Map.of(PromptAssembler.SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY, "Include files touched."),
                "Prefer short reports."
        );

        AgentRequest assembled = assemble(
                PromptMode.SUBAGENT,
                ToolRegistry.investigationTools(),
                MemoryCatalog.empty(workspace),
                SkillCatalog.empty(),
                request
        );

        assertThat(assembled.systemPrompt()).contains("## identity");
        assertThat(assembled.systemPrompt()).contains("investigation subagent");
        assertThat(assembled.systemPrompt()).contains("## user_instructions");
        assertThat(assembled.systemPrompt()).contains("Prefer short reports.");
        assertThat(assembled.systemPrompt()).contains("## subagent_expected_output");
        assertThat(assembled.systemPrompt()).contains("Include files touched.");
        assertThat(assembled.systemPrompt()).contains("read_file");
        assertThat(assembled.systemPrompt()).contains("## task_system");
        assertThat(assembled.systemPrompt()).contains("read-only task tools");
        assertThat(assembled.systemPrompt()).doesNotContain("write_file");
        assertThat(assembled.systemPrompt()).doesNotContain("create_task:");
        assertThat(assembled.systemPrompt()).doesNotContain("claim_task:");
        assertThat(assembled.systemPrompt()).doesNotContain("complete_task:");
        assertThat(assembled.systemPrompt()).doesNotContain("Parent agent system prompt");
    }

    @Test
    void leadPromptIncludesTeamGuidanceWhenTeamToolsAreEnabled() {
        try (TeamRuntime teamRuntime = teamRuntime()) {
            ToolRegistry tools = ToolRegistry.defaultToolsWithSubagent(
                    (request, previousSteps, enabledTools) -> AgentStep.finalAnswer("ok"),
                    UserApprover.denyAll(),
                    SkillCatalog.empty(),
                    MemoryCatalog.empty(workspace),
                    null,
                    new TaskManager(workspace),
                    teamRuntime
            );

            AgentRequest assembled = assemble(
                    PromptMode.MAIN,
                    tools,
                    MemoryCatalog.empty(workspace),
                    SkillCatalog.empty(),
                    AgentRequest.of("coordinate work")
            );

            assertThat(assembled.systemPrompt()).contains("## team_system");
            assertThat(assembled.systemPrompt()).contains("spawn_teammate");
            assertThat(assembled.systemPrompt()).contains("At most four teammates");
        }
    }

    @Test
    void teammatePromptUsesTeammateIdentityAndSendMessageGuidance() {
        try (TeamRuntime teamRuntime = teamRuntime()) {
            ToolRegistry tools = ToolRegistry.teammateTools(teamRuntime, new TaskManager(workspace));

            AgentRequest assembled = assemble(
                    PromptMode.TEAMMATE,
                    tools,
                    MemoryCatalog.empty(workspace),
                    SkillCatalog.empty(),
                    AgentRequest.of("handle assigned message")
            );

            assertThat(assembled.systemPrompt()).contains("persistent teammate agent");
            assertThat(assembled.systemPrompt()).contains("## team_system");
            assertThat(assembled.systemPrompt()).contains("to=lead");
            assertThat(assembled.systemPrompt()).contains("send_message");
            assertThat(assembled.systemPrompt()).doesNotContain("spawn_teammate:");
            assertThat(assembled.systemPrompt()).doesNotContain("subagent:");
        }
    }

    private AgentRequest assemble(
            PromptMode mode,
            ToolRegistry tools,
            MemoryCatalog memoryCatalog,
            SkillCatalog skillCatalog,
            AgentRequest request
    ) {
        return new PromptAssembler().assemble(context(mode, tools, memoryCatalog, skillCatalog, request));
    }

    private PromptAssemblyContext context(
            PromptMode mode,
            ToolRegistry tools,
            MemoryCatalog memoryCatalog,
            SkillCatalog skillCatalog,
            AgentRequest request
    ) {
        String userInstructions = request.metadata().getOrDefault(PromptAssembler.USER_INSTRUCTIONS_METADATA_KEY, request.systemPrompt());
        String originalTask = request.metadata().getOrDefault(PromptAssembler.ORIGINAL_TASK_METADATA_KEY, request.task());
        return new PromptAssemblyContext(
                mode,
                request,
                tools,
                memoryCatalog,
                skillCatalog,
                workspace,
                userInstructions,
                originalTask,
                request.metadata().getOrDefault(PromptAssembler.SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY, "")
        );
    }

    private static SkillCatalog skillCatalog() {
        return SkillCatalog.of(List.of(new SkillDefinition(
                "java-agent",
                "Java agent guidance",
                Path.of("skills/java-agent/SKILL.md"),
                "content"
        )));
    }

    private void writeMemory(String fileName, String name, String description, String type, String body) throws IOException {
        Path memoryDir = workspace.resolve(".memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(fileName), """
                ---
                name: %s
                description: %s
                type: %s
                ---
                
                %s
                """.formatted(name, description, type, body), StandardCharsets.UTF_8);
    }

    private TeamRuntime teamRuntime() {
        return new TeamRuntime(
                workspace,
                "main",
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok"),
                UserApprover.denyAll(),
                SkillCatalog.empty(),
                MemoryCatalog.empty(workspace),
                new TaskManager(workspace),
                io.github.mengru.agent.api.ModelOptions.defaults(),
                "",
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")
        );
    }
}
