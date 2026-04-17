import { Dataset } from "@/dataset/Dataset";
import { DatasetItemData, DatasetNotFoundError } from "@/dataset";
import type { OpikClient } from "@/client/Client";
import {
  resolveEvaluators,
  validateExecutionPolicy,
} from "@/evaluation";
import type {
  TestSuiteItem,
  UpdateTestSuiteItem,
  ExecutionPolicy,
} from "./types";
import {
  serializeEvaluators,
  deserializeEvaluators,
  resolveExecutionPolicy,
  resolveItemExecutionPolicy,
} from "@/evaluation";
import {
  evaluatorsEqual,
  executionPolicyEqual,
} from "./suiteHelpers";
import type { EvaluatorItemLike } from "./suiteHelpers";
import type { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import { DatasetWriteType } from "@/rest_api/api/resources/datasets/types/DatasetWriteType";
import type { DatasetItemUpdate } from "@/rest_api/api/types/DatasetItemUpdate";
import { generateId } from "@/utils/generateId";


export interface CreateTestSuiteOptions {
  name: string;
  description?: string;
  globalAssertions?: string[];
  globalExecutionPolicy?: ExecutionPolicy;
  tags?: string[];
  projectName?: string;
}

export interface UpdateTestSuiteOptions {
  globalAssertions?: string[];
  globalExecutionPolicy?: ExecutionPolicy;
}

function validateSuiteName(name: string): void {
  if (!name || name.trim() === "") {
    throw new Error("Test suite name must be a non-empty string");
  }
}

function extractAssertions(evaluators: EvaluatorItemLike[] | undefined): string[] {
  if (!evaluators) return [];
  const judges = deserializeEvaluators(evaluators);
  return judges.flatMap((judge) => judge.assertions);
}

function prepareDatasetItemData(
  data: Record<string, unknown>,
  options?: Omit<TestSuiteItem, "data">
): DatasetItemData {
  if (options?.executionPolicy) {
    validateExecutionPolicy(options.executionPolicy, "item-level execution policy");
  }

  const resolvedEvaluators = resolveEvaluators(
    options?.assertions,
    undefined,
    "item-level assertions"
  );

  const evaluators = resolvedEvaluators
    ? serializeEvaluators(resolvedEvaluators)
    : undefined;

  return {
    ...data,
    ...(options?.description && { description: options.description }),
    ...(evaluators && { evaluators }),
    ...(options?.executionPolicy && { executionPolicy: options.executionPolicy }),
  };
}

export class TestSuite {
  public readonly name: string;
  public readonly description?: string;

  constructor(
    private readonly _dataset: Dataset,
    private readonly _client: OpikClient
  ) {
    this.name = _dataset.name;
    this.description = _dataset.description;
  }

  /** @internal */
  get dataset(): Dataset {
    return this._dataset;
  }

  /** @internal */
  get client(): OpikClient {
    return this._client;
  }

  get id(): string {
    return this.dataset.id;
  }

  /**
   * The name of the project containing this test suite.
   */
  get projectName(): string | undefined {
    return this.dataset.projectName;
  }

  // ---------------------------------------------------------------------------
  // Static factory methods (replace Client.ts methods — no circular dep)
  // ---------------------------------------------------------------------------

  /**
   * Deletes a test suite by name and project name.
   *
   * @param client - The Opik client instance.
   * @param name - The name of the test suite to delete.
   * @param projectName - The name of the project containing the test suite.
   */
  static async delete(client: OpikClient, name: string, projectName?: string): Promise<void> {
    validateSuiteName(name);
    await client.api.datasets.deleteDatasetByName({
      datasetName: name,
      projectName: projectName,
    });
  }

  static async create(
    client: OpikClient,
    options: CreateTestSuiteOptions
  ): Promise<TestSuite> {
    validateSuiteName(options.name);

    const resolvedEvaluators = resolveEvaluators(
      options.globalAssertions,
      undefined,
      "suite-level assertions"
    );

    if (options.globalExecutionPolicy) {
      validateExecutionPolicy(options.globalExecutionPolicy, "suite creation");
    }

    const resolvedProjectName = client.resolveProjectName(options.projectName);

    const datasetId = generateId();
    await client.api.datasets.createDataset({
      id: datasetId,
      name: options.name,
      description: options.description,
      // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
      type: DatasetWriteType.EvaluationSuite,
      tags: options.tags,
      projectName: resolvedProjectName,
    });

    const suite = new TestSuite(
      new Dataset(
        { id: datasetId, name: options.name, description: options.description, projectName: resolvedProjectName },
        client
      ),
      client
    );

    if (resolvedEvaluators || options.globalExecutionPolicy) {
      await suite.createInitialTestSuiteVersion(
        resolvedEvaluators ?? [],
        resolveExecutionPolicy(options.globalExecutionPolicy)
      );
    }

    return suite;
  }

  static async get(
    client: OpikClient,
    name: string,
    projectName?: string
  ): Promise<TestSuite> {
    const dataset = await client.getDataset(name, projectName);
    await dataset.syncHashes();
    return new TestSuite(dataset, client);
  }

  static async getOrCreate(
    client: OpikClient,
    options: CreateTestSuiteOptions
  ): Promise<TestSuite> {
    validateSuiteName(options.name);

    try {
      return await TestSuite.get(client, options.name, options.projectName);
    } catch (error) {
      if (error instanceof DatasetNotFoundError) {
        return TestSuite.create(client, options);
      }
      throw error;
    }
  }

  // ---------------------------------------------------------------------------
  // Instance methods
  // ---------------------------------------------------------------------------

  async insert(items: TestSuiteItem[]): Promise<void> {
    const datasetItems: DatasetItemData[] = items.map((item) =>
      prepareDatasetItemData(item.data, item)
    );

    await this.dataset.insert(datasetItems);
  }

  /**
   * Updates existing items in the test suite. Each item must have an `id` field.
   *
   * @param items - Array of items to update. Each item must have an `id` to identify
   *                the existing item.
   * @throws Error if any item is missing an `id`
   */
  async update(items: UpdateTestSuiteItem[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    for (const item of items) {
      if (!item.id || item.id.trim() === "") {
        throw new Error(
          `Missing id for test suite item to update: ${JSON.stringify(item)}`
        );
      }
    }

    await this.insert(
      items.map((item) => ({
        data: { ...item.data, id: item.id },
        assertions: item.assertions,
        description: item.description,
        executionPolicy: item.executionPolicy,
      }))
    );
  }

  async getItems(nbSamples?: number): Promise<
    Array<{
      id: string;
      data: Record<string, unknown>;
      description?: string;
      assertions: string[];
      executionPolicy: Required<ExecutionPolicy>;
    }>
  > {
    const rawItems = await this.dataset.getRawItems(nbSamples);
    const suitePolicy = await this.getGlobalExecutionPolicy();

    return rawItems.map((item) => {
      const { id, ...data } = item.getContent(true);
      return {
        id: id ?? "",
        data,
        description: item.description,
        assertions: extractAssertions(item.evaluators),
        executionPolicy: resolveItemExecutionPolicy(
          item.executionPolicy,
          suitePolicy
        ),
      };
    });
  }

  async getGlobalAssertions(): Promise<string[]> {
    const versionInfo = await this.dataset.getVersionInfo();
    return extractAssertions(versionInfo?.evaluators);
  }

  async getTags(): Promise<string[]> {
    return this.dataset.getTags();
  }

  async getItemsCount(): Promise<number | undefined> {
    return this.dataset.getItemsCount();
  }

  async getGlobalExecutionPolicy(): Promise<Required<ExecutionPolicy>> {
    const versionInfo = await this.dataset.getVersionInfo();
    return resolveExecutionPolicy(versionInfo?.executionPolicy);
  }

  /**
   * Get the current (latest) version name.
   *
   * @returns The version name (e.g., "v1") or undefined if no versions exist
   */
  async getCurrentVersionName(): Promise<string | undefined> {
    return this.dataset.getCurrentVersionName();
  }

  /**
   * Get the current (latest) version info.
   *
   * @returns The DatasetVersionPublic object or undefined if no versions exist
   */
  async getVersionInfo(): Promise<Awaited<ReturnType<typeof this.dataset.getVersionInfo>>> {
    return this.dataset.getVersionInfo();
  }

  /**
   * Get a read-only view of a specific version.
   *
   * @param versionName The version name to retrieve (e.g., "v1", "v2")
   * @returns A DatasetVersion object for the specified version
   * @throws DatasetVersionNotFoundError if the version doesn't exist
   */
  async getVersionView(versionName: string): Promise<ReturnType<typeof this.dataset.getVersionView>> {
    return this.dataset.getVersionView(versionName);
  }

  async updateTestSettings(options: UpdateTestSuiteOptions): Promise<void> {
    if (options.globalExecutionPolicy) {
      validateExecutionPolicy(options.globalExecutionPolicy, "suite test settings update");
    }

    const resolvedEvaluators = resolveEvaluators(
      options.globalAssertions,
      undefined,
      "suite-level assertions"
    );

    const assertionsProvided = options.globalAssertions !== undefined;

    if (!resolvedEvaluators && !assertionsProvided && !options.globalExecutionPolicy) {
      throw new Error(
        "At least one of 'globalAssertions' or 'globalExecutionPolicy' must be provided."
      );
    }

    const hasVersionUpdates = resolvedEvaluators || assertionsProvided || options.globalExecutionPolicy !== undefined;
    if (hasVersionUpdates) {
      const versionInfo = await this.dataset.getVersionInfo();

      if (!versionInfo) {
        // No version exists yet — create the initial one using the provided values,
        // falling back to defaults for anything not specified.
        const evaluators = resolvedEvaluators ?? [];
        const executionPolicy = resolveExecutionPolicy(options.globalExecutionPolicy);
        await this.createInitialTestSuiteVersion(evaluators, executionPolicy);
        return;
      }

      // Resolve current values once — used both for fallback and change detection
      const currentEvaluators = versionInfo.evaluators
        ? deserializeEvaluators(versionInfo.evaluators)
        : [];
      const currentPolicy = resolveExecutionPolicy(versionInfo.executionPolicy);

      // Partial updates: retain current values for omitted params
      const evaluators = resolvedEvaluators ??
        (assertionsProvided ? [] : currentEvaluators);
      const executionPolicy = options.globalExecutionPolicy
        ? {
            runsPerItem: options.globalExecutionPolicy.runsPerItem ?? currentPolicy.runsPerItem,
            passThreshold: options.globalExecutionPolicy.passThreshold ?? currentPolicy.passThreshold,
          }
        : currentPolicy;

      if (
        evaluatorsEqual(evaluators, currentEvaluators) &&
        executionPolicyEqual(executionPolicy, currentPolicy)
      ) {
        return;
      }

      await this.client.api.datasets.applyDatasetItemChanges(this.dataset.id, {
        override: false,
        body: {
          base_version: versionInfo.id,
          evaluators: serializeEvaluators(evaluators),
          execution_policy: {
            runs_per_item: executionPolicy.runsPerItem,
            pass_threshold: executionPolicy.passThreshold,
          },
        },
      });
    }
  }

  /**
   * Creates the first version for a test suite that has no versions yet.
   * Uses override=true since there is no base version to build on.
   */
  private async createInitialTestSuiteVersion(
    evaluators: LLMJudge[],
    executionPolicy: Required<ExecutionPolicy>
  ): Promise<void> {
    await this.client.api.datasets.applyDatasetItemChanges(this.dataset.id, {
      override: true,
      body: {
        ...(evaluators.length > 0 && { evaluators: serializeEvaluators(evaluators) }),
        execution_policy: {
          runs_per_item: executionPolicy.runsPerItem,
          pass_threshold: executionPolicy.passThreshold,
        },
      },
    });
  }

  async delete(itemIds: string[]): Promise<void> {
    await this.dataset.delete(itemIds);
  }

  /**
   * Deletes all items from the test suite.
   */
  async clear(): Promise<void> {
    await this.dataset.clear();
  }

  async updateItemAssertions(
    itemId: string,
    assertions: string[]
  ): Promise<void> {
    await this.updateItem(itemId, { assertions });
  }

  async updateItemExecutionPolicy(
    itemId: string,
    executionPolicy: ExecutionPolicy
  ): Promise<void> {
    await this.updateItem(itemId, { executionPolicy });
  }

  async updateItem(
    itemId: string,
    options: { assertions?: string[]; executionPolicy?: ExecutionPolicy }
  ): Promise<void> {
    this.validateItemId(itemId);

    if (options.assertions === undefined && options.executionPolicy === undefined) {
      throw new Error(
        "At least one of 'assertions' or 'executionPolicy' must be provided."
      );
    }

    if (options.executionPolicy) {
      validateExecutionPolicy(options.executionPolicy, "item-level execution policy update");
    }

    const update: DatasetItemUpdate = {};

    if (options.assertions !== undefined) {
      update.evaluators = this.resolveAndSerializeEvaluators(options.assertions);
    }

    if (options.executionPolicy !== undefined) {
      update.executionPolicy = options.executionPolicy;
    }

    await this.client.api.datasets.batchUpdateDatasetItems({
      ids: [itemId],
      update,
    });
  }

  private validateItemId(itemId: string): void {
    if (!itemId || itemId.trim() === "") {
      throw new Error("itemId must be a non-empty string");
    }
  }

  private resolveAndSerializeEvaluators(assertions: string[]) {
    const resolvedEvaluators = resolveEvaluators(
      assertions,
      undefined,
      "item-level assertions update"
    );

    return resolvedEvaluators
      ? serializeEvaluators(resolvedEvaluators)
      : [];
  }
}
