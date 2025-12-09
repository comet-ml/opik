package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * Represents a content part in an LLM-as-Judge message.
 * Supports text, image_url, and video_url content types.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmAsJudgeMessageContent(
        @JsonView( {
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull String type,
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) String text,
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) ImageUrl imageUrl,
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) VideoUrl videoUrl,
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) AudioUrl audioUrl){

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ImageUrl(
            @JsonView( {
                    AutomationRuleEvaluator.View.Public.class,
                    AutomationRuleEvaluator.View.Write.class}) @NotNull String url,
            @JsonView({
                    AutomationRuleEvaluator.View.Public.class,
                    AutomationRuleEvaluator.View.Write.class}) String detail){
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VideoUrl(
            @JsonView( {
                    AutomationRuleEvaluator.View.Public.class,
                    AutomationRuleEvaluator.View.Write.class}) @NotNull String url){
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AudioUrl(
            @JsonView( {
                    AutomationRuleEvaluator.View.Public.class,
                    AutomationRuleEvaluator.View.Write.class}) @NotNull String url){
    }
}
