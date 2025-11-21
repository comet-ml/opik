package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for LiveKit integration.
 */
public final class LiveKitMappingRules {

    private LiveKitMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                // Session & Agent Metadata
                new OpenTelemetryMappingRule("livekit.session.id", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.agent_label", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.speech_id", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.interrupted", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.retry_count", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),

                // LLM & TTS Metrics
                new OpenTelemetryMappingRule("lk.llm_metrics", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.tts_metrics", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.tts.streaming", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.tts.label", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),

                // Input Attributes
                new OpenTelemetryMappingRule("lk.input_text", false, "LiveKit", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("lk.chat_ctx", false, "LiveKit", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("lk.function_tools", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("lk.user_input", false, "LiveKit", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("lk.user_transcript", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.INPUT),

                // Output Attributes
                new OpenTelemetryMappingRule("lk.response.text", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.OUTPUT),
                new OpenTelemetryMappingRule("lk.response.function_calls", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.OUTPUT),

                // Transcription Metadata
                new OpenTelemetryMappingRule("lk.transcript_confidence", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.transcription_delay", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.end_of_turn_delay", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),

                // Function Tool Attributes (with tool span type)
                new OpenTelemetryMappingRule("lk.function_tool.name", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA, SpanType.tool),
                new OpenTelemetryMappingRule("lk.function_tool.arguments", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.INPUT,
                        SpanType.tool),
                new OpenTelemetryMappingRule("lk.function_tool.output", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.OUTPUT, SpanType.tool),
                new OpenTelemetryMappingRule("lk.function_tool.is_error", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA,
                        SpanType.tool),

                // EOU (End of Utterance) Detection
                new OpenTelemetryMappingRule("lk.eou.probability", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.eou.unlikely_threshold", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.eou.endpointing_delay", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("lk.eou.language", false, "LiveKit",
                        OpenTelemetryMappingRule.Outcome.METADATA));
    }
}
