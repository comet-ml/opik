import { Page, expect, Locator } from '@playwright/test';
import { BasePage } from './base.page';

export class PlaygroundPage extends BasePage {
  readonly modelSelector: Locator;
  readonly promptInput: Locator;
  readonly runButton: Locator;
  readonly outputArea: Locator;
  readonly outputResponse: Locator;
  readonly errorMessage: Locator;
  readonly apiErrorMessages: string[];

  constructor(page: Page) {
    super(page, 'playground');
    this.modelSelector = page.getByRole('combobox').first();
    this.promptInput = page.getByRole('textbox').first();
    this.runButton = page.getByRole('button', { name: 'Run' });
    this.outputArea = page.locator('p:text("Output") ~ div').first();
    this.outputResponse = page.locator('p:text("Output")').locator('xpath=following-sibling::div[1]');
    this.errorMessage = page.locator('text=Please select an LLM model for your prompt');
    this.apiErrorMessages = [
      'messages: at least one message is required',
      'API key',
      'error',
      'failed',
      'Invalid',
      'unauthorized',
      'model: not found',
      'model: ',
    ];
  }

  async goto(): Promise<void> {
    await super.goto();
  }

  async selectModel(providerName: string, modelName: string): Promise<void> {
    console.log(`Selecting model: ${providerName} -> ${modelName}`);

    await this.modelSelector.click();

    const providerElement = this.page.getByText(providerName, { exact: true });
    await providerElement.hover();

    try {
      const options = await this.page.getByRole('option').all();
      let targetOption = null;

      for (const option of options) {
        const textContent = (await option.innerText()).trim();
        if (textContent === modelName) {
          targetOption = option;
          break;
        }
      }

      if (targetOption) {
        await targetOption.click();
      } else {
        await this.page.getByRole('option').filter({ hasText: modelName }).first().click();
      }
    } catch (error) {
      console.warn(`Failed to select model with exact matching, trying fallback: ${error}`);
      await this.page.getByRole('option').filter({ hasText: modelName }).first().click();
    }

    console.log(`Successfully selected ${providerName} -> ${modelName}`);
  }

  async verifyModelAvailable(providerName: string, modelName: string): Promise<void> {
    console.log(`Verifying model availability: ${providerName} -> ${modelName}`);

    await this.modelSelector.click();

    const providerElement = this.page.getByText(providerName, { exact: true });
    await expect(providerElement).toBeVisible();

    await providerElement.hover();

    const modelElement = this.page.getByRole('option').filter({ hasText: modelName });
    await expect(modelElement).toBeVisible();

    await this.page.keyboard.press('Escape');

    console.log(`Model ${providerName} -> ${modelName} is available`);
  }

  async verifyModelSelected(expectedModelContains?: string): Promise<void> {
    await expect(this.modelSelector).toBeVisible();

    if (expectedModelContains) {
      const modelText = await this.modelSelector.innerText();
      console.log(`Current model selection: ${modelText}`);
      expect(modelText).toContain(expectedModelContains);
      console.log(`Model verified to contain '${expectedModelContains}'`);
    }
  }

  async enterPrompt(promptText: string, messageType: 'user' | 'system' | 'assistant' = 'user'): Promise<void> {
    console.log(`Entering ${messageType} prompt: ${promptText}`);

    const existingButtons = this.page
      .getByRole('button')
      .filter({ hasText: /^(System|User|Assistant)$/ });

    if ((await existingButtons.count()) > 0) {
      const firstButton = existingButtons.first();
      const currentType = (await firstButton.innerText()).trim();

      if (currentType.toLowerCase() !== messageType.toLowerCase()) {
        console.log(`Changing message type from ${currentType} to ${messageType}`);
        await firstButton.click();
        await this.page
          .getByRole('menuitemcheckbox', { name: messageType.charAt(0).toUpperCase() + messageType.slice(1) })
          .click();
      }

      const firstTextbox = this.page.getByRole('textbox').first();
      await firstTextbox.click();
      await firstTextbox.fill(promptText);
      console.log(`Filled first message box (${messageType}) with: ${promptText.substring(0, 50)}...`);
    } else {
      console.log('No existing message boxes found, adding new user message');
      const messageButton = this.page.getByRole('button', { name: 'Message', exact: true });
      if (await messageButton.isVisible()) {
        await messageButton.click();
        await this.page.waitForTimeout(500);
      }

      const userTextboxes = await this.page.getByRole('textbox').all();
      if (userTextboxes.length > 0) {
        const targetTextbox = userTextboxes[userTextboxes.length - 1];
        await targetTextbox.click();
        await targetTextbox.fill(promptText);
        console.log(`Filled new user message with: ${promptText.substring(0, 50)}...`);
      } else {
        throw new Error('Could not find any textbox to fill');
      }
    }
  }

  async runPrompt(): Promise<void> {
    console.log('Waiting for Run button to be enabled...');

    try {
      await expect(this.runButton).toBeEnabled({ timeout: 10000 });
      console.log('Run button is enabled, clicking...');
      await this.runButton.click();
    } catch (error) {
      const buttonText = await this.runButton.innerText();
      const isDisabled = await this.runButton.isDisabled();
      console.error(`Run button not enabled. Text: '${buttonText}', Disabled: ${isDisabled}`);

      const textboxes = await this.page.getByRole('textbox').all();
      console.error(`Found ${textboxes.length} textboxes`);
      for (let i = 0; i < textboxes.length; i++) {
        const content = await textboxes[i].inputValue();
        console.error(`Textbox ${i}: '${content.substring(0, 50)}...'`);
      }

      throw new Error(`Run button not enabled after 10 seconds: ${error}`);
    }

    console.log('Waiting for response...');
    await this.page.waitForTimeout(2000);
  }

  async getResponse(): Promise<string> {
    console.log('Getting response text');

    await expect(this.outputArea).toBeVisible({ timeout: 5000 });

    const responseText = await this.outputArea.innerText();

    const responseExcerpt = responseText.length > 100 ? responseText.substring(0, 100) + '...' : responseText;
    console.log(`Response received: ${responseExcerpt}`);

    return responseText;
  }

  async hasError(): Promise<boolean> {
    const hasSelectError = await this.errorMessage.isVisible();
    if (hasSelectError) {
      const errorText = await this.errorMessage.innerText();
      console.warn(`Model selection error detected: ${errorText}`);
      return true;
    }

    for (const errorPattern of this.apiErrorMessages) {
      if (await this.page.getByText(errorPattern).isVisible()) {
        console.warn(`API error detected: ${errorPattern}`);
        return true;
      }
    }

    return false;
  }

  async waitForResponseOrError(timeout: number = 30): Promise<boolean> {
    console.log('Waiting for response or error...');

    for (let i = 0; i < timeout; i++) {
      if (await this.outputResponse.isVisible()) {
        const responseText = (await this.outputResponse.innerText()).trim();
        if (responseText && responseText.length > 10) {
          console.log('Response received successfully');
          return true;
        }
      }

      if (await this.hasError()) {
        console.warn('Error detected while waiting for response');
        return false;
      }

      await this.page.waitForTimeout(1000);
    }

    console.warn(`Timeout after ${timeout} seconds waiting for response`);
    return false;
  }

  validateResponseQuality(responseText: string): {
    hasContent: boolean;
    minLength: boolean;
    coherentSentences: boolean;
    noTruncation: boolean;
    containsLlmInfo: boolean;
    responseLength: number;
    sentenceCount: number;
  } {
    const validation = {
      hasContent: Boolean(responseText && responseText.trim()),
      minLength: responseText.length >= 25,
      coherentSentences: responseText.includes('.') && responseText.split('.').length >= 2,
      noTruncation: !responseText.endsWith('...'),
      containsLlmInfo: ['language model', 'llm', 'artificial intelligence', 'ai', 'neural'].some((term) =>
        responseText.toLowerCase().includes(term)
      ),
      responseLength: responseText.length,
      sentenceCount: responseText.split('.').filter((s) => s.trim()).length,
    };

    return validation;
  }
}
