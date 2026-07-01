package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.api.AgentRequest;

public final class TodoReminderHook implements AgentHook<UserPromptSubmitContext> {

    static final String MARKER = "[todo_write reminder]";

    private static final String REMINDER = """
            [todo_write reminder]
            For complex, multi-step tasks, or before running shell commands or modifying files, call todo_write with a complete todo list first. Keep the list current as work progresses. Skip todo_write for simple questions or one-step read-only tasks.
            """.strip();

    @Override
    public HookResult<UserPromptSubmitContext> apply(UserPromptSubmitContext context) {
        AgentRequest request = context.request();
        if (request.systemPrompt().contains(MARKER)) {
            return HookResult.continueWith(context);
        }

        String systemPrompt = request.systemPrompt().isBlank()
                ? REMINDER
                : request.systemPrompt() + "\n\n" + REMINDER;
        return HookResult.replace(new UserPromptSubmitContext(new AgentRequest(
                request.task(),
                request.maxSteps(),
                request.metadata(),
                systemPrompt,
                request.conversationHistory()
        )));
    }
}
