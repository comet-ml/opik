import { Dataset } from "@/dataset/Dataset";
import { BaseMetric } from "@/evaluation/metrics";
import { generateId } from "@/utils/generateId";

async function evaluate(
    dataset: Dataset,
    task: Task,
    scoringMetrics: BaseMetric[] | undefined = undefined,
    experimentName: string | undefined = undefined,
    projectName: string | undefined = undefined,
    experimentConfig: Record<string, any> | undefined = undefined,
    nbSamples: number | undefined = undefined,
    scoringKeyMapping: Record<string, string> | undefined = undefined,
): EvaluationResult {
    if (!scoringMetrics) {
        scoringMetrics = [];
    }

    const experimentId = generateId();
    const datasetItems = await dataset.__getFullItems(nbSamples);

    const testResults = datasetItems.map((item: any) => {
        const taskOutput = task(item.data);
        
        // Calculate metrics
        let metricInput = {...item.data, ...taskOutput }
        if (scoringKeyMapping) {
            for (let [key, value] of Object.entries(scoringKeyMapping)) {
                metricInput[key] = metricInput[value];
                delete metricInput[value];
            }
        }
        const scores = scoringMetrics.map(metric => metric.score(metricInput));

        return { item, taskOutput, scores };
    });

    return testResults;
}