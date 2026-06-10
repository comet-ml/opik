package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Trace;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    void specsListedAlphabeticallyByName() {
        var registry = new ToolRegistry(Set.of(stub("zebra"), stub("alpha"), stub("middle")));

        assertThat(registry.specs())
                .extracting(ToolSpecification::name)
                .containsExactly("alpha", "middle", "zebra");
    }

    @Test
    void executeDispatchesToCorrectTool() {
        var captured = new AtomicReference<String>();
        var tool = new RecordingTool("alpha", captured);
        var registry = new ToolRegistry(Set.of(tool));

        var ctx = newContext();
        var result = registry.execute("alpha", "{\"x\":1}", ctx).block();

        assertThat(result).isEqualTo("ok:alpha");
        assertThat(captured.get()).isEqualTo("{\"x\":1}");
    }

    @Test
    void executeReturnsErrorJsonForUnknownTool() {
        var registry = new ToolRegistry(Set.of(stub("alpha")));

        var result = registry.execute("nope", "{}", newContext()).block();

        assertThat(result).isEqualTo("{\"error\": \"Unknown tool: nope\"}");
    }

    @Test
    void executePassesContextThrough() {
        var ctxRef = new AtomicReference<TraceToolContext>();
        var tool = new ToolExecutor() {
            @Override
            public String name() {
                return "ctx_tool";
            }

            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder()
                        .name("ctx_tool")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext ctx) {
                ctxRef.set(ctx);
                return Mono.just("{}");
            }
        };
        var registry = new ToolRegistry(Set.of(tool));

        var ctx = newContext();
        registry.execute("ctx_tool", "{}", ctx).block();

        assertThat(ctxRef.get()).isSameAs(ctx);
    }

    @Test
    void executeReturnsErrorJsonWhenToolThrowsRuntimeException() {
        var tool = new ToolExecutor() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder()
                        .name("boom")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext ctx) {
                throw new IllegalStateException("kaboom");
            }
        };
        var registry = new ToolRegistry(Set.of(tool));

        var result = registry.execute("boom", "{}", newContext()).block();

        // Tool name is fine to surface back to the LLM (it picked the name to begin with),
        // but the raw exception message ("kaboom") is NOT — it could carry ClickHouse query
        // fragments, internal paths, or other implementation details. The contract is:
        // error envelope + tool name + correlation id, nothing more.
        assertThat(result).contains("\"error\"").contains("boom").contains("ref:");
        assertThat(result).doesNotContain("kaboom");
    }

    @Test
    void executeReturnsErrorJsonWhenToolEmitsMonoError() {
        // Tools can also surface failures via Mono.error rather than a synchronous throw —
        // the registry's onErrorResume must convert both into the same error JSON shape so
        // the judge loop stays alive either way.
        var tool = new ToolExecutor() {
            @Override
            public String name() {
                return "async_boom";
            }

            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder()
                        .name("async_boom")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext ctx) {
                return Mono.error(new IllegalStateException("async kaboom"));
            }
        };
        var registry = new ToolRegistry(Set.of(tool));

        var result = registry.execute("async_boom", "{}", newContext()).block();

        // Same redaction contract as the sync-throw test above: tool name + correlation id,
        // no raw exception message even when surfaced via Mono.error.
        assertThat(result).contains("\"error\"").contains("async_boom").contains("ref:");
        assertThat(result).doesNotContain("async kaboom");
    }

    @Test
    void executeLetsErrorsPropagate() {
        var tool = new ToolExecutor() {
            @Override
            public String name() {
                return "fatal";
            }

            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder()
                        .name("fatal")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext ctx) {
                throw new StackOverflowError("recursive tool");
            }
        };
        var registry = new ToolRegistry(Set.of(tool));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> registry.execute("fatal", "{}", newContext()).block())
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void emptyRegistryReturnsEmptySpecsAndUnknownErrors() {
        var registry = new ToolRegistry(Set.of());

        assertThat(registry.specs()).isEmpty();
        assertThat(registry.execute("anything", "{}", newContext()).block())
                .isEqualTo("{\"error\": \"Unknown tool: anything\"}");
    }

    private static ToolExecutor stub(String name) {
        return new ToolExecutor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder()
                        .name(name)
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext ctx) {
                return Mono.just("{}");
            }
        };
    }

    private static TraceToolContext newContext() {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .name("trace")
                .startTime(Instant.now())
                .build();
        return TraceToolContext.forActiveTrace(trace, List.of(), "ws", "user");
    }

    private record RecordingTool(String name, AtomicReference<String> captured) implements ToolExecutor {
        @Override
        public ToolSpecification spec() {
            return ToolSpecification.builder()
                    .name(name)
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
        }

        @Override
        public Mono<String> execute(String arguments, TraceToolContext ctx) {
            captured.set(arguments);
            return Mono.just("ok:" + name);
        }
    }
}
