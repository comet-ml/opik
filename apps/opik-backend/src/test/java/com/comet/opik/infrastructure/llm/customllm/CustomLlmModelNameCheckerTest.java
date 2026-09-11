package com.comet.opik.infrastructure.llm.customllm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CustomLlmModelNameChecker Tests")
class CustomLlmModelNameCheckerTest {

    @Nested
    @DisplayName("isCustomLlmModel() Tests")
    class IsCustomLlmModelTests {

        @Test
        @DisplayName("Should return true for valid custom LLM model with simple name")
        void shouldReturnTrue_whenValidCustomLlmModel_withSimpleName() {
            // When
            boolean result = CustomLlmModelNameChecker.isCustomLlmModel("custom-llm/llama-3.2");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return true for valid custom LLM model with provider name")
        void shouldReturnTrue_whenValidCustomLlmModel_withProviderName() {
            // When
            boolean result = CustomLlmModelNameChecker.isCustomLlmModel("custom-llm/ollama/llama-3.2");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return true for valid custom LLM model with slashes")
        void shouldReturnTrue_whenValidCustomLlmModel_withSlashes() {
            // When
            boolean result = CustomLlmModelNameChecker
                    .isCustomLlmModel("custom-llm/mistralai/Mistral-7B-Instruct-v0.3");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return true for model that is exactly the prefix (edge case)")
        void shouldReturnTrue_whenModelIsExactlyThePrefix() {
            // When - This is an edge case that passes startsWith() but should fail later validation
            boolean result = CustomLlmModelNameChecker.isCustomLlmModel("custom-llm/");

            // Then
            assertThat(result).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "gpt-4",
                "claude-3-opus-20240229",
                "gemini-pro",
                "custom",
                "custom-llm",
                "CUSTOM-LLM/model",
                "Custom-Llm/model",
                ""
        })
        @DisplayName("Should return false for non-custom LLM models")
        void shouldReturnFalse_whenNotCustomLlmModel(String model) {
            // When
            boolean result = CustomLlmModelNameChecker.isCustomLlmModel(model);

            // Then
            assertThat(result).isFalse();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("Should throw NullPointerException when model is null")
        void shouldThrowNullPointerException_whenModelIsNull(String model) {
            // When & Then
            assertThatThrownBy(() -> CustomLlmModelNameChecker.isCustomLlmModel(model))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("model");
        }
    }

    @Nested
    @DisplayName("extractModelName() Tests")
    class ExtractModelNameTests {

        @Nested
        @DisplayName("Legacy Format (providerName = null)")
        class LegacyFormatTests {

            @Test
            @DisplayName("Should extract simple model name")
            void shouldExtractModelName_whenSimpleModelName() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName("custom-llm/llama-3.2", null);

                // Then
                assertThat(result).isEqualTo("llama-3.2");
            }

            @Test
            @DisplayName("Should extract model name with slashes")
            void shouldExtractModelName_whenModelNameWithSlashes() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/mistralai/Mistral-7B-Instruct-v0.3", null);

                // Then
                assertThat(result).isEqualTo("mistralai/Mistral-7B-Instruct-v0.3");
            }

            @Test
            @DisplayName("Should extract model name with multiple slashes")
            void shouldExtractModelName_whenModelNameWithMultipleSlashes() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/org/repo/model-v1", null);

                // Then
                assertThat(result).isEqualTo("org/repo/model-v1");
            }
        }

        @Nested
        @DisplayName("New Format (providerName set)")
        class NewFormatTests {

            @Test
            @DisplayName("Should extract simple model name")
            void shouldExtractModelName_whenSimpleModelName() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/ollama/llama-3.2", "ollama");

                // Then
                assertThat(result).isEqualTo("llama-3.2");
            }

            @Test
            @DisplayName("Should extract model name with slashes")
            void shouldExtractModelName_whenModelNameWithSlashes() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/vllm/mistralai/Mistral-7B-Instruct-v0.3", "vllm");

                // Then
                assertThat(result).isEqualTo("mistralai/Mistral-7B-Instruct-v0.3");
            }

            @Test
            @DisplayName("Should extract model name with multiple slashes")
            void shouldExtractModelName_whenModelNameWithMultipleSlashes() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/vllm/org/repo/model-v1", "vllm");

                // Then
                assertThat(result).isEqualTo("org/repo/model-v1");
            }

            @Test
            @DisplayName("Should return model without provider prefix when provider name doesn't match")
            void shouldReturnModelWithoutProviderPrefix_whenProviderNameDoesNotMatch() {
                // When - This logs a warning but returns the model without custom-llm/ prefix
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/ollama/llama-3.2", "vllm");

                // Then
                assertThat(result).isEqualTo("ollama/llama-3.2");
            }
        }

        @Nested
        @DisplayName("Edge Cases and Error Handling")
        class EdgeCasesTests {

            @ParameterizedTest
            @ValueSource(strings = {
                    "gpt-4",
                    "claude-3-opus-20240229",
                    "gemini-pro",
                    "custom",
                    "custom-llm"
            })
            @DisplayName("Should throw IllegalArgumentException for non-custom LLM models")
            void shouldThrowIllegalArgumentException_whenNotCustomLlmModel(String model) {
                // When & Then
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName(model, null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Not a custom LLM model");
            }

            @Test
            @DisplayName("Should throw IllegalArgumentException when model is just the prefix")
            void shouldThrowIllegalArgumentException_whenModelIsJustThePrefix() {
                // When & Then
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName("custom-llm/", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("model name is blank after prefix");
            }

            @ParameterizedTest
            @ValueSource(strings = {
                    "custom-llm/   ",
                    "custom-llm/\t",
                    "custom-llm/\n",
                    "custom-llm/ \t\n "
            })
            @DisplayName("Should throw IllegalArgumentException when model name is whitespace only")
            void shouldThrowIllegalArgumentException_whenModelNameIsWhitespaceOnly(String model) {
                // When & Then
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName(model, null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("model name is blank after prefix");
            }

            @Test
            @DisplayName("Should throw IllegalArgumentException when model is shorter than prefix (edge case)")
            void shouldThrowIllegalArgumentException_whenModelIsShorterThanPrefix() {
                // This test verifies the edge case mentioned in the code review comment
                // Even though isCustomLlmModel would return false for these, we test the behavior
                // When & Then
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName("custom", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Not a custom LLM model");
            }

            @Test
            @DisplayName("Should throw IllegalArgumentException when model is empty string")
            void shouldThrowIllegalArgumentException_whenModelIsEmptyString() {
                // When & Then
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName("", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Not a custom LLM model");
            }

            @ParameterizedTest
            @NullSource
            @DisplayName("Should throw NullPointerException when model is null")
            void shouldThrowNullPointerException_whenModelIsNull(String model) {
                // When & Then
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName(model, null))
                        .isInstanceOf(NullPointerException.class)
                        .hasMessageContaining("model");
            }
        }

        @Nested
        @DisplayName("Case Sensitivity Tests")
        class CaseSensitivityTests {

            @Test
            @DisplayName("Should be case-sensitive for prefix matching")
            void shouldBeCaseSensitive_forPrefixMatching() {
                // When & Then - Uppercase prefix should not match
                assertThatThrownBy(() -> CustomLlmModelNameChecker.extractModelName("CUSTOM-LLM/model", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Not a custom LLM model");
            }

            @Test
            @DisplayName("Should preserve case in extracted model name")
            void shouldPreserveCase_inExtractedModelName() {
                // When
                String result = CustomLlmModelNameChecker.extractModelName(
                        "custom-llm/Llama-3.2-Instruct", null);

                // Then
                assertThat(result).isEqualTo("Llama-3.2-Instruct");
            }
        }
    }
}
