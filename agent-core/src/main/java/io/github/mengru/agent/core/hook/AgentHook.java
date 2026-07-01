package io.github.mengru.agent.core.hook;

@FunctionalInterface
public interface AgentHook<T> {

    HookResult<T> apply(T context);
}
