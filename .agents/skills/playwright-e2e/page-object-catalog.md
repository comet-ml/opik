# Page Object Catalog

All page objects are in `tests_end_to_end/typescript-tests/page-objects/`. Always import and use these instead of writing raw Playwright locators in tests.

## BasePage (`base.page.ts`)

Base class for all workspace-aware page objects.

```typescript
constructor(page: Page, path: string, queryParams?: string)
async goto(): Promise<void>          // Navigate to /{workspace}/{path}
getUrl(): string                     // Get full URL
```

All page objects that extend `BasePage` accept `page: Page` in their constructor and have a `goto()` method.

## ProjectsPage (`projects.page.ts`)

**Path**: `/{workspace}/projects` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async clickProject(projectName: string): Promise<void>
async searchProject(projectName: string): Promise<void>
async checkProjectExists(projectName: string): Promise<void>
async checkProjectNotExists(projectName: string): Promise<void>
async checkProjectExistsWithRetry(projectName: string, timeout?: number): Promise<void>
async createNewProject(projectName: string): Promise<void>
async deleteProjectByName(projectName: string): Promise<void>
```

## DatasetsPage (`datasets.page.ts`)

**Path**: `/{workspace}/datasets` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async createDatasetByName(datasetName: string): Promise<void>
async selectDatasetByName(name: string): Promise<void>         // Navigates to items page
async searchDataset(datasetName: string): Promise<void>
async checkDatasetExists(datasetName: string): Promise<void>
async checkDatasetNotExists(datasetName: string): Promise<void>
async deleteDatasetByName(datasetName: string): Promise<void>
```

## DatasetItemsPage (`dataset-items.page.ts`)

**Path**: Inside a dataset detail page | **Does not extend BasePage** (navigated to by clicking a dataset)

```typescript
constructor(page: Page)
async removeDefaultColumns(): Promise<void>
async deleteFirstItemAndGetContent(): Promise<Record<string, string>>
async insertDatasetItem(item: string): Promise<void>            // JSON string
async getAllDatasetItemsOnCurrentPage(): Promise<Array<Record<string, string>>>
async getAllItemsInDataset(): Promise<Array<Record<string, string>>>  // Handles pagination
async waitForEmptyDatasetMessage(): Promise<void>
```

## TracesPage (`traces.page.ts`)

**Path**: Inside a project -> Traces tab | **Does not extend BasePage** (navigated to by clicking a project)

```typescript
constructor(page: Page)
async initialize(): Promise<void>                                // Enable Name column
async getAllTraceNamesOnPage(): Promise<string[]>
async clickFirstTraceWithName(traceName: string): Promise<void>
async checkTraceAttachment(attachmentName?: string): Promise<void>
async getFirstTraceNameOnPage(): Promise<string | null>
async getAllTraceNamesInProject(): Promise<string[]>             // Handles pagination
async getNumberOfTracesOnPage(): Promise<number>
async getTotalNumberOfTracesInProject(): Promise<number>
async waitForTracesToBeVisible(timeout?: number): Promise<void>
async deleteSingleTraceByName(name: string): Promise<void>
```

## ThreadsPage (`threads.page.ts`)

**Path**: Inside a project -> Logs tab -> Threads toggle | **Does not extend BasePage**

```typescript
constructor(page: Page)
async switchToPage(): Promise<void>                              // Click Logs tab then Threads toggle
async getNumberOfThreadsOnPage(): Promise<number>
async openThreadContent(threadId: string): Promise<void>
async checkMessageInThread(message: string, isOutput?: boolean): Promise<void>
async searchForThread(threadId: string): Promise<void>
async deleteThreadFromTable(): Promise<void>
async checkThreadIsDeleted(threadId: string): Promise<void>
async deleteThreadFromThreadContentBar(): Promise<void>
async closeThreadContent(): Promise<void>
```

## ExperimentsPage (`experiments.page.ts`)

**Path**: `/{workspace}/experiments` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async searchExperiment(name: string): Promise<void>
async clearSearch(): Promise<void>
async checkExperimentExists(name: string): Promise<void>
async checkExperimentNotExists(name: string): Promise<void>
async clickExperiment(name: string): Promise<void>              // Navigates to detail page
async deleteExperiment(name: string): Promise<void>
```

## ExperimentItemsPage (`experiment-items.page.ts`)

**Path**: Inside an experiment detail page | **Does not extend BasePage**

```typescript
constructor(page: Page)
async getTotalNumberOfItemsInExperiment(): Promise<number>
async getIdOfNthExperimentItem(n: number): Promise<string>
async getAllItemIdsOnCurrentPage(): Promise<string[]>
async getAllItemIdsInExperiment(): Promise<string[]>             // Handles pagination
```

## PromptsPage (`prompts.page.ts`)

**Path**: `/{workspace}/prompts` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async searchPrompt(name: string): Promise<void>
async clearSearch(): Promise<void>
async checkPromptExists(name: string): Promise<void>
async checkPromptNotExists(name: string): Promise<void>
async clickPrompt(name: string): Promise<void>                  // Navigates to detail page
async deletePrompt(name: string): Promise<void>
```

## PromptDetailsPage (`prompts.page.ts`)

**Path**: Inside a prompt detail page | **Does not extend BasePage**

```typescript
constructor(page: Page)
async editPrompt(newPrompt: string): Promise<void>
async switchToCommitsTab(): Promise<void>
async getAllCommitVersions(): Promise<Record<string, string>>    // {promptText: commitId}
async clickMostRecentCommit(): Promise<void>
async getSelectedCommitPrompt(): Promise<string>
```

## FeedbackScoresPage (`feedback-scores.page.ts`)

**Path**: `/{workspace}/configuration?tab=feedback-definitions` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async searchFeedback(name: string): Promise<void>
async clearSearch(): Promise<void>
async checkFeedbackExists(name: string): Promise<void>
async checkFeedbackNotExists(name: string): Promise<void>
async createFeedbackDefinition(name: string, type: 'categorical' | 'numerical', options?: {...}): Promise<void>
async editFeedbackDefinition(name: string, newName: string, options?: {...}): Promise<void>
async deleteFeedbackDefinition(name: string): Promise<void>
async getFeedbackType(name: string): Promise<string>
async getFeedbackValues(name: string): Promise<string>
```

## AIProvidersConfigPage (`ai-providers-config.page.ts`)

**Path**: `/{workspace}/configuration?tab=ai-provider` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async searchProviderByName(providerName: string): Promise<void>
async addProvider(providerType: 'OpenAI' | 'Anthropic', apiKey: string): Promise<void>
async editProvider(name: string, apiKey?: string): Promise<void>
async deleteProvider(providerName: string): Promise<void>
async checkProviderExists(providerName: string): Promise<boolean>
async checkProviderNotExists(providerName: string): Promise<void>
```

## PlaygroundPage (`playground.page.ts`)

**Path**: `/{workspace}/playground` | **Extends**: `BasePage`

```typescript
constructor(page: Page)
async goto(): Promise<void>
async selectModel(providerName: string, modelName: string): Promise<void>
async verifyModelAvailable(providerName: string, modelName: string): Promise<void>
async verifyModelSelected(expectedModelContains?: string): Promise<void>
async enterPrompt(promptText: string, messageType?: 'user' | 'system' | 'assistant'): Promise<void>
async runPrompt(): Promise<void>
async getResponse(): Promise<string>
async hasError(): Promise<boolean>
async waitForResponseOrError(timeout?: number): Promise<boolean>
validateResponseQuality(responseText: string): {...}            // Sync validation
```

## RulesPage (`rules.page.ts`)

**Path**: Inside a project -> Online Evaluation tab | **Does not extend BasePage**

```typescript
constructor(page: Page)
async navigateToRulesTab(): Promise<void>
async selectModel(providerDisplayName: string, modelUiSelector: string): Promise<void>
async createModerationRule(ruleName: string, providerDisplayName: string, modelUiSelector: string): Promise<void>
async createModerationRuleWithFilters(ruleName: string, ..., filters: Array<{field, operator, value}>): Promise<void>
```

## TracesPageSpansMenu (`traces-spans-menu.page.ts`)

**Path**: Inside trace detail sidebar | **Does not extend BasePage**

```typescript
constructor(page: Page)
getFirstTraceByName(name: string): Locator
getFirstSpanByName(name: string): Locator
async checkSpanExistsByName(name: string): Promise<void>
async checkTagExistsByName(tagName: string): Promise<void>
getInputOutputTab(): Locator
getFeedbackScoresTab(): Locator
getMetadataTab(): Locator
async openSpanContent(spanName: string): Promise<void>
async checkSpanAttachment(attachmentName: string): Promise<void>
```
