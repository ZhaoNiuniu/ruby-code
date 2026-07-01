package io.github.mengru.agent.core.prompt;

public interface PromptSection {

    String id();

    boolean shouldRender(PromptAssemblyContext context);

    String render(PromptAssemblyContext context);
}
