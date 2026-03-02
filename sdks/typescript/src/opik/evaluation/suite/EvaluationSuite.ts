import { Dataset } from "@/dataset/Dataset";
import { DatasetItemData, DatasetNotFoundError } from "@/dataset";
import { OpikClient } from "@/client/Client";
import { LLMJudge } from "../suite_evaluators/LLMJudge";
import {
  validateEvaluators,
  validateExecutionPolicy,
} from "../suite_evaluators/validators";
import type { EvaluationSuiteResult, ExecutionPolicy } from "./types";
import { buildSuiteResult } from "./suiteResultConstructor";
import { evaluateSuite } from "./evaluateSuite";
import {
  serializeEvaluators,
  deserializeEvaluators,
  resolveExecutionPolicy,
  resolveItemExecutionPolicy,
} from "./suiteHelpers";
import type { EvaluationTask } from "../types";
import { DatasetWriteType } from "@/rest_api/api/resources/datasets/types/DatasetWriteType";
import type { Prompt } from "@/prompt/Prompt";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";

export interface EvaluationSuiteRunOptions {
  experimentName?: string;
  projectName?: string;
  experimentConfig?: Record<string, unknown>;
  prompts?: Prompt[];
  evaluatorModel?: string;
}

export interface AddItemOptions {
  evaluators?: LLMJudge[];
  executionPolicy?: ExecutionPolicy;
}

export interface CreateEvaluationSuiteOptions {
  name: string;
  description?: string;
  evaluators?: LLMJudge[];
  executionPolicy?: ExecutionPolicy;
}

function validateSuiteName(name: string): void {
  if (!name || name.trim() === "") {
    throw new Error("Evaluation suite name must be a non-empty string");
  }
}

export class EvaluationSuite {
  public readonly name: string;
  public readonly description?: string;

  constructor(
    private readonly dataset: Dataset,
    private readonly client: OpikClient
  ) {
    this.name = dataset.name;
    this.description = dataset.description;
  }

  get id(): string {
    return this.dataset.id;
  }

  // ---------------------------------------------------------------------------
  // Static factory methods (replace Client.ts methods — no circular dep)
  // ---------------------------------------------------------------------------

  static async create(
    client: OpikClient,
    options: CreateEvaluationSuiteOptions
  ): Promise<EvaluationSuite> {
    validateSuiteName(options.name);

    if (options.evaluators) {
      validateEvaluators(options.evaluators, "suite-level evaluators");
    }

    if (options.executionPolicy) {
      validateExecutionPolicy(options.executionPolicy, "suite creation");
    }

    const datasetId = generateId();
    await client.api.datasets.createDataset({
      id: datasetId,
      name: options.name,
      description: options.description,
      type: DatasetWriteType.EvaluationSuite,
    });

    const dataset = new Dataset(
      { id: datasetId, name: options.name, description: options.description },
      client
    );

    if (options.evaluators || options.executionPolicy) {
      const evaluators = options.evaluators
        ? serializeEvaluators(options.evaluators)
        : undefined;

      const executionPolicy = options.executionPolicy;

      await client.api.datasets.applyDatasetItemChanges(datasetId, {
        override: true,
        body: {
          ...(evaluators && { evaluators }),
          ...(executionPolicy && {
            execution_policy: {
              runs_per_item: executionPolicy.runsPerItem,
              pass_threshold: executionPolicy.passThreshold,
            },
          }),
        },
      });
    }

    return new EvaluationSuite(dataset, client);
  }

  static async get(
    client: OpikClient,
    name: string
  ): Promise<EvaluationSuite> {
    const dataset = await client.getDataset(name);
    await dataset.syncHashes();
    return new EvaluationSuite(dataset, client);
  }

  static async getOrCreate(
    client: OpikClient,
    options: CreateEvaluationSuiteOptions
  ): Promise<EvaluationSuite> {
    validateSuiteName(options.name);

    try {
      return await EvaluationSuite.get(client, options.name);
    } catch (error) {
      if (error instanceof DatasetNotFoundError) {
        return EvaluationSuite.create(client, options);
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
    if (options?.evaluators) {
      validateEvaluators(options.evaluators, "item-level evaluators");
    }

    if (options?.executionPolicy) {
      validateExecutionPolicy(
        options.executionPolicy,
        "item-level execution policy"
      );
    }

    const evaluators = options?.evaluators
      ? serializeEvaluators(options.evaluators)
      : undefined;

    const executionPolicy = options?.executionPolicy
      ? {
          runsPerItem: options.executionPolicy.runsPerItem,
          passThreshold: options.executionPolicy.passThreshold,
        }
      : undefined;

    const itemData: DatasetItemData = {
      ...data,
      ...(evaluators && { evaluators }),
      ...(executionPolicy && { executionPolicy }),
    };

    await this.dataset.insert([itemData]);
  }

  async run(
    task: EvaluationTask,
    options?: EvaluationSuiteRunOptions
  ): Promise<EvaluationSuiteResult> {
    const evalResult = await evaluateSuite({
      dataset: this.dataset,
      task,
      experimentName: options?.experimentName,
      projectName: options?.projectName,
      experimentConfig: options?.experimentConfig,
      prompts: options?.prompts,
      evaluatorModel: options?.evaluatorModel,
      client: this.client,
    });

    return buildSuiteResult(evalResult);
  }

  async getItems(
    evaluatorModel?: string
  ): Promise<
    Array<{
      id: string;
      data: Record<string, unknown>;
      evaluators: LLMJudge[];
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
        evaluators: item.evaluators
          ? deserializeEvaluators(item.evaluators, evaluatorModel)
          : [],
        executionPolicy: resolveItemExecutionPolicy(
          item.executionPolicy,
          suitePolicy
        ),
      };
    });
  }

  async getEvaluators(evaluatorModel?: string): Promise<LLMJudge[]> {
    const versionInfo = await this.dataset.getVersionInfo();
    if (!versionInfo?.evaluators) {
      return [];
    }
    return deserializeEvaluators(versionInfo.evaluators, evaluatorModel);
  }

  async getExecutionPolicy(): Promise<Required<ExecutionPolicy>> {
    const versionInfo = await this.dataset.getVersionInfo();
    return resolveExecutionPolicy(versionInfo?.executionPolicy);
  }

  async update(options: {
    evaluators: LLMJudge[];
    executionPolicy: ExecutionPolicy;
  }): Promise<void> {
    validateEvaluators(options.evaluators, "suite-level evaluators");
    validateExecutionPolicy(options.executionPolicy, "suite update");

    const versionInfo = await this.dataset.getVersionInfo();
    const baseVersion = versionInfo?.id;

    await this.client.api.datasets.applyDatasetItemChanges(this.dataset.id, {
      override: false,
      body: {
        base_version: baseVersion,
        evaluators: serializeEvaluators(options.evaluators),
        execution_policy: {
          runs_per_item: options.executionPolicy.runsPerItem,
          pass_threshold: options.executionPolicy.passThreshold,
        },
      },
    });
  }

  async deleteItems(itemIds: string[]): Promise<void> {
    await this.dataset.delete(itemIds);
  }
}
