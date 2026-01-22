package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Python Metric Parser Test")
class PythonMetricParserTest {

    @Nested
    @DisplayName("Parse Method Tests")
    class ParseTests {

        @Test
        @DisplayName("should parse simple metric class with docstring")
        void parse__whenSimpleMetricClass__shouldExtractMetadata() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric, score_result

                    class Equals(base_metric.BaseMetric):
                        \"\"\"
                        A metric that checks if an output string exactly matches an expected output string.

                        This metric returns a score of 1.0 if the strings match exactly, and 0.0 otherwise.

                        Args:
                            case_sensitive: Whether the comparison should be case-sensitive.
                            name: The name of the metric.
                        \"\"\"

                        def __init__(self, case_sensitive: bool = False, name: str = "equals_metric"):
                            super().__init__(name=name)
                            self._case_sensitive = case_sensitive

                        def score(self, output: str, reference: str, **ignored_kwargs) -> score_result.ScoreResult:
                            \"\"\"
                            Calculate the score based on whether the output exactly matches the expected output.

                            Args:
                                output: The output to check.
                                reference: The expected output to compare against.
                                **ignored_kwargs: Additional keyword arguments that are ignored.

                            Returns:
                                score_result.ScoreResult: A ScoreResult object.
                            \"\"\"
                            if output == reference:
                                return score_result.ScoreResult(value=1.0, name=self.name)
                            return score_result.ScoreResult(value=0.0, name=self.name)
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "equals.py");

            // Then
            assertThat(metrics).hasSize(1);

            CommonMetric metric = metrics.get(0);
            assertThat(metric.id()).isEqualTo("equals");
            assertThat(metric.name()).isEqualTo("Equals");
            assertThat(metric.description()).contains("checks if an output string exactly matches");
            assertThat(metric.code()).contains("class Equals");
            assertThat(metric.parameters()).hasSize(2);

            // Check parameters
            CommonMetric.ScoreParameter outputParam = metric.parameters().stream()
                    .filter(p -> p.name().equals("output"))
                    .findFirst()
                    .orElseThrow();
            assertThat(outputParam.type()).isEqualTo("str");
            assertThat(outputParam.required()).isTrue();
            assertThat(outputParam.description()).contains("output to check");

            CommonMetric.ScoreParameter referenceParam = metric.parameters().stream()
                    .filter(p -> p.name().equals("reference"))
                    .findFirst()
                    .orElseThrow();
            assertThat(referenceParam.type()).isEqualTo("str");
            assertThat(referenceParam.required()).isTrue();
        }

        @Test
        @DisplayName("should parse metric with optional parameters")
        void parse__whenMetricWithOptionalParams__shouldMarkAsNotRequired() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric, score_result

                    class Contains(base_metric.BaseMetric):
                        \"\"\"
                        A metric that checks if a reference string is contained within an output string.
                        \"\"\"

                        def score(self, output: str, reference: str = None, **ignored_kwargs) -> score_result.ScoreResult:
                            \"\"\"
                            Calculate the score.

                            Args:
                                output: The output string to check.
                                reference: The reference string to look for.
                            \"\"\"
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "contains.py");

            // Then
            assertThat(metrics).hasSize(1);

            CommonMetric metric = metrics.get(0);
            assertThat(metric.parameters()).hasSize(2);

            CommonMetric.ScoreParameter outputParam = metric.parameters().stream()
                    .filter(p -> p.name().equals("output"))
                    .findFirst()
                    .orElseThrow();
            assertThat(outputParam.required()).isTrue();

            CommonMetric.ScoreParameter referenceParam = metric.parameters().stream()
                    .filter(p -> p.name().equals("reference"))
                    .findFirst()
                    .orElseThrow();
            assertThat(referenceParam.required()).isFalse();
        }

        @Test
        @DisplayName("should parse multiple metrics from single file")
        void parse__whenMultipleMetricsInFile__shouldExtractAll() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric, score_result

                    class SentenceBLEU(base_metric.BaseMetric):
                        \"\"\"Computes sentence-level BLEU score.\"\"\"

                        def score(self, output: str, reference: str, **ignored_kwargs):
                            pass

                    class CorpusBLEU(base_metric.BaseMetric):
                        \"\"\"Computes corpus-level BLEU score.\"\"\"

                        def score(self, output: List[str], reference: List[str], **ignored_kwargs):
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "bleu.py");

            // Then
            assertThat(metrics).hasSize(2);
            assertThat(metrics).extracting(CommonMetric::name)
                    .containsExactlyInAnyOrder("SentenceBLEU", "CorpusBLEU");
        }

        @Test
        @DisplayName("should skip base classes")
        void parse__whenBaseClass__shouldSkip() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric

                    class BaseBLEU(base_metric.BaseMetric):
                        \"\"\"Base class for BLEU metrics.\"\"\"

                        def score(self, output: str, reference: str, **ignored_kwargs):
                            pass

                    class SentenceBLEU(BaseBLEU):
                        \"\"\"Sentence-level BLEU.\"\"\"

                        def score(self, output: str, reference: str, **ignored_kwargs):
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "bleu.py");

            // Then
            assertThat(metrics).hasSize(1);
            assertThat(metrics.get(0).name()).isEqualTo("SentenceBLEU");
        }

        @Test
        @DisplayName("should handle complex type annotations")
        void parse__whenComplexTypeAnnotations__shouldExtractTypes() {
            // Given
            String pythonCode = """
                    from typing import List, Union, Optional
                    from opik.evaluation.metrics import base_metric, score_result

                    class ROUGE(base_metric.BaseMetric):
                        \"\"\"ROUGE metric.\"\"\"

                        def score(self, output: str, reference: Union[str, List[str]], **ignored_kwargs):
                            \"\"\"
                            Args:
                                output: The output string.
                                reference: The reference string or list.
                            \"\"\"
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "rouge.py");

            // Then
            assertThat(metrics).hasSize(1);

            CommonMetric metric = metrics.get(0);
            CommonMetric.ScoreParameter referenceParam = metric.parameters().stream()
                    .filter(p -> p.name().equals("reference"))
                    .findFirst()
                    .orElseThrow();
            assertThat(referenceParam.type()).contains("Union");
        }

        @Test
        @DisplayName("should return empty list for file without metrics")
        void parse__whenNoMetrics__shouldReturnEmptyList() {
            // Given
            String pythonCode = """
                    # Just some utility functions
                    def helper_function():
                        pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "utils.py");

            // Then
            assertThat(metrics).isEmpty();
        }

        @Test
        @DisplayName("should generate snake_case ID from class name")
        void parse__whenCamelCaseClassName__shouldGenerateSnakeCaseId() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric

                    class LevenshteinRatio(base_metric.BaseMetric):
                        \"\"\"Levenshtein ratio metric.\"\"\"

                        def score(self, output: str, reference: str, **ignored_kwargs):
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "levenshtein.py");

            // Then
            assertThat(metrics).hasSize(1);
            assertThat(metrics.get(0).id()).isEqualTo("levenshtein_ratio");
        }

        @Test
        @DisplayName("should handle metric with single parameter")
        void parse__whenSingleParameter__shouldExtractIt() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric

                    class IsJson(base_metric.BaseMetric):
                        \"\"\"Checks if output is valid JSON.\"\"\"

                        def score(self, output: str, **ignored_kwargs):
                            \"\"\"
                            Args:
                                output: The output string to check.
                            \"\"\"
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "is_json.py");

            // Then
            assertThat(metrics).hasSize(1);
            assertThat(metrics.get(0).parameters()).hasSize(1);
            assertThat(metrics.get(0).parameters().get(0).name()).isEqualTo("output");
        }

        @Test
        @DisplayName("should extract init parameters with default values")
        void parse__whenMetricWithInitParams__shouldExtractInitParameters() {
            // Given
            String pythonCode = """
                    from typing import Optional
                    from opik.evaluation.metrics import base_metric, score_result

                    class Contains(base_metric.BaseMetric):
                        \"\"\"
                        A metric that checks if a reference string is contained within an output string.

                        Args:
                            case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
                            reference: Optional default reference string.
                            name: The name of the metric.
                            track: Whether to track the metric.
                        \"\"\"

                        def __init__(
                            self,
                            case_sensitive: bool = False,
                            reference: Optional[str] = None,
                            name: str = "contains_metric",
                            track: bool = True,
                            project_name: Optional[str] = None,
                        ):
                            super().__init__(name=name, track=track, project_name=project_name)
                            self._case_sensitive = case_sensitive
                            self._default_reference = reference

                        def score(self, output: str, reference: Optional[str] = None, **ignored_kwargs) -> score_result.ScoreResult:
                            \"\"\"
                            Args:
                                output: The output string to check.
                                reference: The reference string to look for.
                            \"\"\"
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "contains.py");

            // Then
            assertThat(metrics).hasSize(1);

            CommonMetric metric = metrics.get(0);

            // Check init parameters (should exclude name, track, project_name)
            assertThat(metric.initParameters()).isNotNull();
            assertThat(metric.initParameters()).hasSize(2);

            // Check case_sensitive parameter
            CommonMetric.InitParameter caseSensitiveParam = metric.initParameters().stream()
                    .filter(p -> p.name().equals("case_sensitive"))
                    .findFirst()
                    .orElseThrow();
            assertThat(caseSensitiveParam.type()).isEqualTo("bool");
            assertThat(caseSensitiveParam.defaultValue()).isEqualTo("False");
            assertThat(caseSensitiveParam.required()).isFalse();
            // Description extraction from docstring is best-effort
            assertThat(caseSensitiveParam.description()).isNotNull();

            // Check reference parameter
            CommonMetric.InitParameter referenceParam = metric.initParameters().stream()
                    .filter(p -> p.name().equals("reference"))
                    .findFirst()
                    .orElseThrow();
            assertThat(referenceParam.type()).isEqualTo("Optional[str]");
            assertThat(referenceParam.defaultValue()).isEqualTo("None");
            assertThat(referenceParam.required()).isFalse();
        }

        @Test
        @DisplayName("should handle metric without init parameters")
        void parse__whenMetricWithoutInitParams__shouldReturnEmptyInitParameters() {
            // Given
            String pythonCode = """
                    from opik.evaluation.metrics import base_metric

                    class SimpleMetric(base_metric.BaseMetric):
                        \"\"\"A simple metric.\"\"\"

                        def score(self, output: str, **ignored_kwargs):
                            pass
                    """;

            // When
            List<CommonMetric> metrics = PythonMetricParser.parse(pythonCode, "simple.py");

            // Then
            assertThat(metrics).hasSize(1);
            assertThat(metrics.get(0).initParameters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("should validate metric has required parameters")
        void isValidForOnlineEvaluation__whenHasRequiredParams__shouldReturnTrue() {
            // Given
            CommonMetric metric = CommonMetric.builder()
                    .id("test")
                    .name("Test")
                    .description("Test metric")
                    .code("class Test: pass")
                    .parameters(List.of(
                            CommonMetric.ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("Output")
                                    .required(true)
                                    .build()))
                    .build();

            // When & Then
            assertThat(PythonMetricParser.isValidForOnlineEvaluation(metric)).isTrue();
        }

        @Test
        @DisplayName("should reject metric without required parameters")
        void isValidForOnlineEvaluation__whenNoRequiredParams__shouldReturnFalse() {
            // Given
            CommonMetric metric = CommonMetric.builder()
                    .id("test")
                    .name("Test")
                    .description("Test metric")
                    .code("class Test: pass")
                    .parameters(List.of(
                            CommonMetric.ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("Output")
                                    .required(false)
                                    .build()))
                    .build();

            // When & Then
            assertThat(PythonMetricParser.isValidForOnlineEvaluation(metric)).isFalse();
        }

        @Test
        @DisplayName("should reject metric without parameters")
        void isValidForOnlineEvaluation__whenNoParams__shouldReturnFalse() {
            // Given
            CommonMetric metric = CommonMetric.builder()
                    .id("test")
                    .name("Test")
                    .description("Test metric")
                    .code("class Test: pass")
                    .parameters(List.of())
                    .build();

            // When & Then
            assertThat(PythonMetricParser.isValidForOnlineEvaluation(metric)).isFalse();
        }
    }
}
