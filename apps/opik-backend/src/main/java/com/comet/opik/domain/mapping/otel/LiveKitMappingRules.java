package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for LiveKit integration.
 */
@UtilityClass
public final class LiveKitMappingRules {

    public static final String SOURCE = "LiveKit";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            // Session & Agent Metadata
            OpenTelemetryMappingRule.builder()
                    .rule("livekit.session.id").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.agent_label").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.speech_id").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.interrupted").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.retry_count").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),

            // LLM & TTS Metrics
            OpenTelemetryMappingRule.builder()
                    .rule("lk.llm_metrics").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.tts_metrics").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.tts.streaming").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.tts.label").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),

            // Input Attributes
            OpenTelemetryMappingRule.builder()
                    .rule("lk.input_text").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.chat_ctx").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.function_tools").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.user_input").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.user_transcript").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .build(),

            // Output Attributes
            OpenTelemetryMappingRule.builder()
                    .rule("lk.response.text").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.response.function_calls").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),

            // Transcription Metadata
            OpenTelemetryMappingRule.builder()
                    .rule("lk.transcript_confidence").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.transcription_delay").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.end_of_turn_delay").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),

            // Function Tool Attributes (with tool span type)
            OpenTelemetryMappingRule.builder()
                    .rule("lk.function_tool.name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.function_tool.arguments").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.function_tool.output").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.function_tool.is_error").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).spanType(SpanType.tool).build(),

            // EOU (End of Utterance) Detection
            OpenTelemetryMappingRule.builder()
                    .rule("lk.eou.probability").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.eou.unlikely_threshold").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.eou.endpointing_delay").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("lk.eou.language").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
