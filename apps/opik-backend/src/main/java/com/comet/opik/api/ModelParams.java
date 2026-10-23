package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import org.apache.commons.lang3.ObjectUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelParams(
        NumberParam temperature,
        NumberParam topP,
        NumberParam topK,
        NumberParam frequencyPenalty,
        NumberParam presencePenalty,
        NumberParam maxOutputTokens,
        FlagParam stop,
        FlagParam seed,
        EffortParam effort,
        ToolsParam tools,
        Boolean samplingExclusive,
        Boolean samplingAtEffortNone,
        Boolean thinksByDefault,
        Map<String, ModelParams> routes) {

    public static final String RESPONSES_API_ROUTE = "responses_api";

    public ModelParams {
        if (routes != null) {
            var copy = new LinkedHashMap<String, ModelParams>();
            routes.forEach((route, params) -> {
                if (params != null) {
                    copy.put(route, params);
                }
            });
            routes = Collections.unmodifiableMap(copy);
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NumberParam(
            Boolean accepted,
            BigDecimal min,
            BigDecimal max,
            @JsonProperty("default") BigDecimal defaultValue) {

        static NumberParam overlay(NumberParam base, NumberParam top) {
            if (base == null || top == null) {
                return ObjectUtils.getIfNull(top, base);
            }
            return new NumberParam(
                    ObjectUtils.getIfNull(top.accepted, base.accepted),
                    ObjectUtils.getIfNull(top.min, base.min),
                    ObjectUtils.getIfNull(top.max, base.max),
                    ObjectUtils.getIfNull(top.defaultValue, base.defaultValue));
        }

        NumberParam settled() {
            return Boolean.FALSE.equals(accepted)
                    ? new NumberParam(false, null, null, null)
                    : toBuilder().accepted(true).build();
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlagParam(Boolean accepted) {

        static FlagParam overlay(FlagParam base, FlagParam top) {
            if (base == null || top == null) {
                return ObjectUtils.getIfNull(top, base);
            }
            return new FlagParam(ObjectUtils.getIfNull(top.accepted, base.accepted));
        }

        FlagParam settled() {
            return new FlagParam(ObjectUtils.getIfNull(accepted, Boolean.TRUE));
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EffortParam(
            Boolean accepted,
            List<String> levels,
            @JsonProperty("default") String defaultLevel) {

        public EffortParam {
            levels = levels == null ? null : List.copyOf(levels);
        }

        static EffortParam overlay(EffortParam base, EffortParam top) {
            if (base == null || top == null) {
                return ObjectUtils.getIfNull(top, base);
            }
            return new EffortParam(
                    ObjectUtils.getIfNull(top.accepted, base.accepted),
                    ObjectUtils.getIfNull(top.levels, base.levels),
                    ObjectUtils.getIfNull(top.defaultLevel, base.defaultLevel));
        }

        EffortParam settled() {
            var isAccepted = ObjectUtils.getIfNull(accepted, levels != null && !levels.isEmpty());
            return isAccepted
                    ? toBuilder().accepted(true).build()
                    : new EffortParam(false, null, null);
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolsParam(ChatCompletionsTools chatCompletions, FlagParam forcedChoice) {

        static ToolsParam overlay(ToolsParam base, ToolsParam top) {
            if (base == null || top == null) {
                return ObjectUtils.getIfNull(top, base);
            }
            return new ToolsParam(
                    ObjectUtils.getIfNull(top.chatCompletions, base.chatCompletions),
                    FlagParam.overlay(base.forcedChoice, top.forcedChoice));
        }

        ToolsParam settled() {
            return new ToolsParam(chatCompletions, forcedChoice == null ? null : forcedChoice.settled());
        }
    }

    /** Only Chat Completions has a say: OpenAI's Responses API takes function tools at any effort. */
    public enum ChatCompletionsTools {
        @JsonProperty("effortNoneOnly")
        EFFORT_NONE_ONLY,
        @JsonProperty("refused")
        REFUSED
    }

    /** Lists are replaced whole: a model's three effort levels must not merge with its profile's five. */
    public static ModelParams overlay(ModelParams base, ModelParams top) {
        if (base == null || top == null) {
            return ObjectUtils.getIfNull(top, base);
        }
        return new ModelParams(
                NumberParam.overlay(base.temperature, top.temperature),
                NumberParam.overlay(base.topP, top.topP),
                NumberParam.overlay(base.topK, top.topK),
                NumberParam.overlay(base.frequencyPenalty, top.frequencyPenalty),
                NumberParam.overlay(base.presencePenalty, top.presencePenalty),
                NumberParam.overlay(base.maxOutputTokens, top.maxOutputTokens),
                FlagParam.overlay(base.stop, top.stop),
                FlagParam.overlay(base.seed, top.seed),
                EffortParam.overlay(base.effort, top.effort),
                ToolsParam.overlay(base.tools, top.tools),
                ObjectUtils.getIfNull(top.samplingExclusive, base.samplingExclusive),
                ObjectUtils.getIfNull(top.samplingAtEffortNone, base.samplingAtEffortNone),
                ObjectUtils.getIfNull(top.thinksByDefault, base.thinksByDefault),
                overlayRoutes(base.routes, top.routes));
    }

    private static Map<String, ModelParams> overlayRoutes(Map<String, ModelParams> base, Map<String, ModelParams> top) {
        if (base == null || top == null) {
            return ObjectUtils.getIfNull(top, base);
        }
        var merged = new LinkedHashMap<>(base);
        top.forEach((route, params) -> merged.merge(route, params, ModelParams::overlay));
        return merged;
    }

    /**
     * Each route becomes the whole set of params for that route, and an unset {@code accepted} becomes explicit, so
     * a reader needs no merge rules of its own.
     */
    public ModelParams settled() {
        var self = withoutRoutes().settledFields();
        if (routes == null || routes.isEmpty()) {
            return self;
        }
        var settledRoutes = new LinkedHashMap<String, ModelParams>();
        routes.forEach((route, params) -> settledRoutes.put(route,
                overlay(withoutRoutes(), params.withoutRoutes()).settledFields()));
        return self.toBuilder().routes(settledRoutes).build();
    }

    private ModelParams withoutRoutes() {
        return toBuilder().routes(null).build();
    }

    private ModelParams settledFields() {
        return new ModelParams(
                temperature == null ? null : temperature.settled(),
                topP == null ? null : topP.settled(),
                topK == null ? null : topK.settled(),
                frequencyPenalty == null ? null : frequencyPenalty.settled(),
                presencePenalty == null ? null : presencePenalty.settled(),
                maxOutputTokens == null ? null : maxOutputTokens.settled(),
                stop == null ? null : stop.settled(),
                seed == null ? null : seed.settled(),
                effort == null ? null : effort.settled(),
                tools == null ? null : tools.settled(),
                samplingExclusive,
                samplingAtEffortNone,
                thinksByDefault,
                routes);
    }
}
