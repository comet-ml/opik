import { Dataset } from "@/dataset/Dataset";
import { DatasetItemData, DatasetNotFoundError } from "@/dataset";
import { OpikClient } from "@/client/Client";
import {
  resolveEvaluators,
  validateExecutionPolicy,
} from "../suite_evaluators/validators";
import type {
  TestSuiteItem,
  ExecutionPolicy,
} from "./types";
import {
  serializeEvaluators,
  deserializeEvaluators,
  resolveExecutionPolicy,
  resolveItemExecutionPolicy,
  evaluatorsEqual,
  executionPolicyEqual,
} from "./suiteHelpers";
import type { EvaluatorItemLike } from "./suiteHelpers";
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
  tags?: string[];
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

  get projectName(): string | undefined {
    return this.dataset.projectName;
  }

  // ---------------------------------------------------------------------------
  // Static factory methods (replace Client.ts methods — no circular dep)
  // ---------------------------------------------------------------------------

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

    const dataset = new Dataset(
      { id: datasetId, name: options.name, description: options.description, projectName: resolvedProjectName },
      client
    );

    if (resolvedEvaluators || options.globalExecutionPolicy) {
      const evaluators = resolvedEvaluators
        ? serializeEvaluators(resolvedEvaluators)
        : undefined;

      await client.api.datasets.applyDatasetItemChanges(datasetId, {
        override: true,
        body: {
          ...(evaluators && { evaluators }),
          ...(options.globalExecutionPolicy && {
            execution_policy: {
              runs_per_item: options.globalExecutionPolicy.runsPerItem,
              pass_threshold: options.globalExecutionPolicy.passThreshold,
            },
          }),
        },
      });
    }

    return new TestSuite(dataset, client);
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

  async getItems(): Promise<
    Array<{
      id: string;
      data: Record<string, unknown>;
      description?: string;
      assertions: string[];
      executionPolicy: Required<ExecutionPolicy>;
    }>
  > {
    const rawItems = await this.dataset.getRawItems();
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

  async update(options: UpdateTestSuiteOptions): Promise<void> {
    if (options.globalExecutionPolicy) {
      validateExecutionPolicy(options.globalExecutionPolicy, "suite update");
    }

    const resolvedEvaluators = resolveEvaluators(
      options.globalAssertions,
      undefined,
      "suite-level assertions"
    );

    const assertionsProvided = options.globalAssertions !== undefined;

    if (!resolvedEvaluators && !assertionsProvided && !options.globalExecutionPolicy && !options.tags) {
      throw new Error(
        "At least one of 'globalAssertions', 'globalExecutionPolicy', or 'tags' must be provided."
      );
    }

    // Tags are dataset-level, updated separately
    if (options.tags) {
      await this.client.api.datasets.updateDataset(this.dataset.id, {
        name: this.name,
        tags: options.tags,
      });
    }

    const hasVersionUpdates = resolvedEvaluators || assertionsProvided || options.globalExecutionPolicy !== undefined;
    if (hasVersionUpdates) {
      const versionInfo = await this.dataset.getVersionInfo();
      if (!versionInfo) {
        throw new Error(
          `Cannot update test suite '${this.name}': ` +
            "no version info found. Add at least one item first."
        );
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

  async delete(itemIds: string[]): Promise<void> {
    await this.dataset.delete(itemIds);
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
