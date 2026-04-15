import type { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { DatasetPublicType } from "@/rest_api/api/types/DatasetPublicType";
import { TestSuite } from "./TestSuite";

const PAGE_SIZE = 100;

/**
 * Fetches all test suites for the given project, paginating through all results.
 * Only datasets with the type "evaluation_suite" are returned.
 * Matches the pagination logic of the Python SDK's get_test_suites().
 *
 * @param client - The Opik client instance.
 * @param maxResults - Maximum number of test suites to return.
 * @param projectName - Resolved project name (already passed through resolveProjectName).
 * @param projectId - Optional resolved project ID for API filtering.
 */
export async function getTestSuites(
  client: OpikClient,
  maxResults: number,
  projectName: string,
  projectId: string | undefined
): Promise<TestSuite[]> {
  const suites: TestSuite[] = [];
  let page = 1;

  while (suites.length < maxResults) {
    const response = await client.api.datasets.findDatasets({
      page,
      size: PAGE_SIZE,
      ...(projectId && { projectId }),
    });

    const content = response.content ?? [];
    if (content.length === 0) {
      break;
    }

    for (const datasetData of content) {
      if (suites.length >= maxResults) {
        break;
      }
      if (datasetData.type !== DatasetPublicType.EvaluationSuite) {
        continue;
      }
      suites.push(
        new TestSuite(
          new Dataset({ ...datasetData, projectName }, client),
          client
        )
      );
    }

    page++;
  }

  return suites;
}
