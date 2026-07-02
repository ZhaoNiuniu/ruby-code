package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBusTest {

    @TempDir
    Path workspace;

    @Test
    void appendAndConsumeJsonlMessagesInOrder() {
        MessageBus bus = new MessageBus(workspace, "team_test");

        bus.send(TeamMessage.create("team_test", TeamMessageType.PLAIN_TEXT, "lead", "worker", "first", "", JsonNodeFactory.instance.objectNode()));
        bus.send(TeamMessage.create("team_test", TeamMessageType.STATUS_UPDATE, "lead", "worker", "second", "", JsonNodeFactory.instance.objectNode()));

        List<TeamMessage> messages = bus.consume("worker");

        assertThat(messages).extracting(TeamMessage::content).containsExactly("first", "second");
        assertThat(bus.consume("worker")).isEmpty();
    }

    @Test
    void eachInboxEntryIsSingleLineJsonAndGitIgnoreIsUpdated() throws Exception {
        MessageBus bus = new MessageBus(workspace, "team_test");

        bus.send(TeamMessage.create("team_test", TeamMessageType.PLAIN_TEXT, "lead", "worker", "hello", "", JsonNodeFactory.instance.objectNode()));

        Path inbox = workspace.resolve(".teams/team_test/inboxes/worker.jsonl");
        assertThat(Files.readAllLines(inbox, StandardCharsets.UTF_8)).hasSize(1);
        assertThat(Files.readString(workspace.resolve(".gitignore"), StandardCharsets.UTF_8)).contains(".teams/");
    }

    @Test
    void concurrentAppendDoesNotCorruptJsonl() throws Exception {
        MessageBus bus = new MessageBus(workspace, "team_test");
        int count = 12;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < count; i++) {
            int index = i;
            executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                bus.send(TeamMessage.create(
                        "team_test",
                        TeamMessageType.PLAIN_TEXT,
                        "lead",
                        "worker",
                        "message-" + index,
                        "",
                        JsonNodeFactory.instance.objectNode()
                ));
                return null;
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        List<TeamMessage> messages = bus.consume("worker");

        assertThat(messages).hasSize(count);
        assertThat(messages).allSatisfy(message -> assertThat(message.content()).startsWith("message-"));
    }
}
