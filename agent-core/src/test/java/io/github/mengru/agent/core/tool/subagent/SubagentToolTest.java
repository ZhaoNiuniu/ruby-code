package io.github.mengru.agent.core.tool.subagent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentToolTest {

    @Test
    void exposesJsonSchemaForSubagentTask() {
        SubagentTool tool = new SubagentTool(finalAnswerModel("ok"), UserApprover.denyAll());

        assertThat(tool.parametersSchema().at("/properties/description/type").asText()).isEqualTo("string");
        assertThat(tool.parametersSchema().at("/properties/expectedOutput/type").asText()).isEqualTo("string");
        assertThat(tool.parametersSchema().at("/properties/maxSteps/maximum").asInt()).isEqualTo(12);
    }

    @Test
    void rejectsMissingOrBlankDescription() {
        SubagentTool tool = new SubagentTool(finalAnswerModel("ok"), UserApprover.denyAll());

        ToolResult missing = tool.execute(request(args()));
        ObjectNode blankArguments = args();
        blankArguments.put("description", " ");
        ToolResult blank = tool.execute(request(blankArguments));

        assertThat(missing.success()).isFalse();
        assertThat(missing.error()).contains("description must not be blank");
        assertThat(blank.success()).isFalse();
        assertThat(blank.error()).contains("description must not be blank");
    }

    @Test
    void usesDefaultMaxStepsAndCapsRequestedMaxSteps() {
        AtomicReference<Integer> firstMaxSteps = new AtomicReference<>();
        SubagentTool defaultTool = new SubagentTool(capturingModel(firstMaxSteps), UserApprover.denyAll());
        ObjectNode defaultArguments = args();
        defaultArguments.put("description", "inspect");

        ToolResult defaultResult = defaultTool.execute(request(defaultArguments));

        AtomicReference<Integer> cappedMaxSteps = new AtomicReference<>();
        SubagentTool cappedTool = new SubagentTool(capturingModel(cappedMaxSteps), UserApprover.denyAll());
        ObjectNode cappedArguments = args();
        cappedArguments.put("description", "inspect");
        cappedArguments.put("maxSteps", 99);

        ToolResult cappedResult = cappedTool.execute(request(cappedArguments));

        assertThat(defaultResult.success()).isTrue();
        assertThat(firstMaxSteps.get()).isEqualTo(6);
        assertThat(cappedResult.success()).isTrue();
        assertThat(cappedMaxSteps.get()).isEqualTo(12);
    }

    @Test
    void expectedOutputAndParentSystemPromptAreIncludedInChildPrompt() {
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        ModelClient model = (request, previousSteps, tools) -> {
            systemPrompt.set(request.systemPrompt());
            return AgentStep.finalAnswer("Summary:\nok\nEvidence:\nnone\nRecommended next step:\ndone");
        };
        SubagentTool tool = new SubagentTool(model, UserApprover.denyAll());
        ObjectNode arguments = args();
        arguments.put("description", "inspect");
        arguments.put("expectedOutput", "include concrete file references");

        ToolResult result = tool.execute(request(arguments));

        assertThat(result.success()).isTrue();
        assertThat(systemPrompt.get()).contains("Expected output:");
        assertThat(systemPrompt.get()).contains("include concrete file references");
        assertThat(systemPrompt.get()).contains("Parent agent system prompt:");
        assertThat(systemPrompt.get()).contains("parent prompt");
    }

    @Test
    void childAgentStartsWithEmptyStepsAndReturnsOnlyFinalOutput() {
        AtomicBoolean firstCallHadEmptySteps = new AtomicBoolean(false);
        ModelClient model = (request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                firstCallHadEmptySteps.set(true);
                return AgentStep.thought("intermediate trace that should stay inside the child");
            }
            return AgentStep.finalAnswer("Summary:\nfinal only\nEvidence:\none file\nRecommended next step:\ncontinue");
        };
        SubagentTool tool = new SubagentTool(model, UserApprover.denyAll());
        ObjectNode arguments = args();
        arguments.put("description", "trace calls");

        ToolResult result = tool.execute(request(arguments));

        assertThat(firstCallHadEmptySteps).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("final only");
        assertThat(result.output()).doesNotContain("intermediate trace");
    }

    @Test
    void childToolScopeIsInvestigationOnlyAndNonRecursive() {
        AtomicReference<List<String>> toolNames = new AtomicReference<>();
        ModelClient model = (request, previousSteps, tools) -> {
            toolNames.set(tools.stream().map(Tool::name).toList());
            return AgentStep.finalAnswer("Summary:\nok\nEvidence:\ntools\nRecommended next step:\ncontinue");
        };
        SubagentTool tool = new SubagentTool(model, UserApprover.denyAll());
        ObjectNode arguments = args();
        arguments.put("description", "inspect tools");

        ToolResult result = tool.execute(request(arguments));

        assertThat(result.success()).isTrue();
        assertThat(toolNames.get()).containsExactly("todo_write", "read_file", "glob", "bash");
        assertThat(toolNames.get()).doesNotContain("write_file", "edit_file", "subagent");
    }

    @Test
    void childToolScopeIncludesLoadSkillWhenCatalogIsAvailable() {
        AtomicReference<List<String>> toolNames = new AtomicReference<>();
        ModelClient model = (request, previousSteps, tools) -> {
            toolNames.set(tools.stream().map(Tool::name).toList());
            return AgentStep.finalAnswer("Summary:\nok\nEvidence:\ntools\nRecommended next step:\ncontinue");
        };
        SubagentTool tool = new SubagentTool(model, UserApprover.denyAll(), skillCatalog());
        ObjectNode arguments = args();
        arguments.put("description", "inspect tools");

        ToolResult result = tool.execute(request(arguments));

        assertThat(result.success()).isTrue();
        assertThat(toolNames.get()).containsExactly("todo_write", "load_skill", "read_file", "glob", "bash");
        assertThat(toolNames.get()).doesNotContain("write_file", "edit_file", "subagent");
    }

    @Test
    void incompleteChildRunReturnsToolFailure() {
        SubagentTool tool = new SubagentTool((request, previousSteps, tools) -> AgentStep.thought("still working"), UserApprover.denyAll());
        ObjectNode arguments = args();
        arguments.put("description", "never finishes");
        arguments.put("maxSteps", 1);

        ToolResult result = tool.execute(request(arguments));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("subagent did not complete");
        assertThat(result.error()).contains("still working");
    }

    @Test
    void childBashToolStillPassesThroughPermissionHook() {
        AtomicBoolean approvalAsked = new AtomicBoolean(false);
        ObjectNode bashArguments = JsonNodeFactory.instance.objectNode();
        bashArguments.put("command", "printf hello");
        ModelClient model = (request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-bash", "bash", bashArguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        };
        SubagentTool tool = new SubagentTool(model, (permissionRequest, decision) -> {
            approvalAsked.set(true);
            return false;
        });
        ObjectNode arguments = args();
        arguments.put("description", "run a safe shell query");

        ToolResult result = tool.execute(request(arguments));

        assertThat(approvalAsked).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("permission denied");
        assertThat(result.output()).contains("user rejected approval");
    }

    private static ModelClient finalAnswerModel(String output) {
        return (request, previousSteps, tools) -> AgentStep.finalAnswer(output);
    }

    private static ModelClient capturingModel(AtomicReference<Integer> maxSteps) {
        return (request, previousSteps, tools) -> {
            maxSteps.set(request.maxSteps());
            return AgentStep.finalAnswer("Summary:\nok\nEvidence:\nnone\nRecommended next step:\ndone");
        };
    }

    private static ToolRequest request(ObjectNode arguments) {
        return new ToolRequest(
                SubagentTool.NAME,
                arguments,
                Map.of(SubagentTool.PARENT_SYSTEM_PROMPT_METADATA_KEY, "parent prompt")
        );
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static SkillCatalog skillCatalog() {
        return SkillCatalog.of(List.of(new SkillDefinition(
                "java-agent",
                "Java agent guidance",
                Path.of("skills/java-agent/SKILL.md"),
                "content"
        )));
    }
}
