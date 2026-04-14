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
} from "./suiteHelpers";
import type { EvaluatorItemLike } from "./suiteHelpers";
import { DatasetWriteType } from "@/rest_api/api/resources/datasets/types/DatasetWriteType";
import type { Prompt } from "@/prompt/Prompt";
import { generateId } from "@/utils/generateId";

export interface AddItemOptions {
  assertions?: string[];
  description?: string;
  executionPolicy?: ExecutionPolicy;
}

export interface CreateTestSuiteOptions {
  name: string;
  description?: string;
  assertions?: string[];
  executionPolicy?: ExecutionPolicy;
  tags?: string[];
  projectName?: string;
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
  options?: { assertions?: string[]; description?: string; executionPolicy?: ExecutionPolicy }
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
      options.assertions,
      undefined,
      "suite-level assertions"
    );

    if (options.executionPolicy) {
      validateExecutionPolicy(options.executionPolicy, "suite creation");
    }

    const resolvedProjectName = client.resolveProjectName(options.projectName);

    const datasetId = generateId();
    await client.api.datasets.createDataset({
      id: datasetId,
      name: options.name,
      description: options.description,
      type: DatasetWriteType.EvaluationSuite,
      tags: options.tags,
      projectName: resolvedProjectName,
    });

    const dataset = new Dataset(
      { id: datasetId, name: options.name, description: options.description, projectName: resolvedProjectName },
      client
    );

    if (resolvedEvaluators || options.executionPolicy) {
      const evaluators = resolvedEvaluators
        ? serializeEvaluators(resolvedEvaluators)
        : undefined;

      await client.api.datasets.applyDatasetItemChanges(datasetId, {
        override: true,
        body: {
          ...(evaluators && { evaluators }),
          ...(options.executionPolicy && {
            execution_policy: {
              runs_per_item: options.executionPolicy.runsPerItem,
              pass_threshold: options.executionPolicy.passThreshold,
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
      const suite = await TestSuite.get(client, options.name, options.projectName);

      const hasUpdates =
        options.assertions !== undefined ||
        options.executionPolicy !== undefined ||
        options.tags !== undefined;

      if (hasUpdates) {
        await suite.update({
          assertions: options.assertions,
          executionPolicy: options.executionPolicy,
          tags: options.tags,
        });
      }

      return suite;
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

  async addItem(
    data: Record<string, unknown>,
    options?: AddItemOptions
  ): Promise<void> {
    await this.dataset.insert([prepareDatasetItemData(data, options)]);
  }

  async addItems(items: TestSuiteItem[]): Promise<void> {
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
    const suitePolicy = await this.getExecutionPolicy();

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

  async getAssertions(): Promise<string[]> {
    const versionInfo = await this.dataset.getVersionInfo();
    return extractAssertions(versionInfo?.evaluators);
  }

  async getTags(): Promise<string[]> {
    return this.dataset.getTags();
  }

  async getItemsCount(): Promise<number | undefined> {
    return this.dataset.getItemsCount();
  }

  async getExecutionPolicy(): Promise<Required<ExecutionPolicy>> {
    const versionInfo = await this.dataset.getVersionInfo();
    return resolveExecutionPolicy(versionInfo?.executionPolicy);
  }

  async update(options: {
    assertions?: string[];
    executionPolicy?: ExecutionPolicy;
    tags?: string[];
  }): Promise<void> {
    if (options.executionPolicy) {
      validateExecutionPolicy(options.executionPolicy, "suite update");
    }

    const resolvedEvaluators = resolveEvaluators(
      options.assertions,
      undefined,
      "suite-level assertions"
    );

    const assertionsProvided = options.assertions !== undefined;

    if (!resolvedEvaluators && !assertionsProvided && !options.executionPolicy && !options.tags) {
      throw new Error(
        "At least one of 'assertions', 'executionPolicy', or 'tags' must be provided."
      );
    }

    // Tags are dataset-level, updated separately
    if (options.tags) {
      await this.client.api.datasets.updateDataset(this.dataset.id, {
        name: this.name,
        tags: options.tags,
      });
    }

    const hasVersionUpdates = resolvedEvaluators || assertionsProvided || options.executionPolicy;
    if (hasVersionUpdates) {
      const versionInfo = await this.dataset.getVersionInfo();
      if (!versionInfo) {
        throw new Error(
          `Cannot update test suite '${this.name}': ` +
            "no version info found. Add at least one item first."
        );
      }

      // Partial updates: retain current values for omitted params
      const evaluators = resolvedEvaluators ??
        (assertionsProvided
          ? []
          : (versionInfo.evaluators
            ? deserializeEvaluators(versionInfo.evaluators)
            : []));
      const executionPolicy = options.executionPolicy ??
        resolveExecutionPolicy(versionInfo.executionPolicy);

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

  async deleteItems(itemIds: string[]): Promise<void> {
    await this.dataset.delete(itemIds);
  }
}
