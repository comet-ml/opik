import { Locator, Page } from "@playwright/test";
import { expect } from "@e2e/fixtures";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinitionCategoricalDetails,
  FeedbackDefinitionData,
  FeedbackDefinitionNumericalDetails,
} from "@e2e/entities";
import { Search } from "../components/Search";
import { Table } from "../components/Table";
import { Columns } from "@e2e/pages/components/Columns";

export class FeedbackDefinitionsTab {
  readonly search: Search;
  readonly table: Table;
  readonly columns: Columns;

  constructor(readonly page: Page) {
    this.search = new Search(page);
    this.table = new Table(page);
    this.columns = new Columns(page);
  }

  async goto() {
    await this.page.goto("/default/configuration?tab=feedback-definitions");
  }

  async fillCategoricalData(details: FeedbackDefinitionCategoricalDetails) {
    const categories = Object.entries(details.categories);

    const len = categories.length;

    for (let j = 0; j < len; j++) {
      // by default two categories is presented
      if (j > 1) {
        await this.page.getByRole("button", { name: "Add category" }).click();
      }
      await this.page
        .getByPlaceholder("Name", { exact: true })
        .nth(j)
        .fill(categories[j][0]);
      await this.page
        .getByPlaceholder("0.0")
        .nth(j)
        .fill(String(categories[j][1]));
    }
  }

  async fillNumericalData(details: FeedbackDefinitionNumericalDetails) {
    await this.page.getByPlaceholder("Min").fill(String(details.min));
    await this.page.getByPlaceholder("Max").fill(String(details.max));
  }

  async selectType(type: FEEDBACK_DEFINITION_TYPE) {
    const name = type === "numerical" ? "Numerical" : "Categorical";
    await this.page.getByRole("combobox").click();
    await this.page.getByLabel(name).click();
  }

  async addFeedbackDefinition(data: FeedbackDefinitionData) {
    await this.page
      .getByRole("button", {
        name: "Create new feedback definition",
      })
      .first()
      .click();
    await this.page
      .getByPlaceholder("Feedback definition name")
      .fill(data.name);
    await this.selectType(data.type);

    switch (data.type) {
      case "categorical":
        await this.fillCategoricalData(
          data.details as FeedbackDefinitionCategoricalDetails,
        );
        break;
      case "numerical":
        await this.fillNumericalData(
          data.details as FeedbackDefinitionNumericalDetails,
        );
        break;
    }

    await this.page
      .getByRole("button", { name: "Create feedback definition" })
      .click();
  }

  async editFeedbackDefinition(name: string, data: FeedbackDefinitionData) {
    await this.table.openRowActionsByCellText(name);
    await this.page.getByRole("menuitem", { name: "Edit" }).click();

    await this.page
      .getByPlaceholder("Feedback definition name")
      .fill(data.name);
    await this.selectType(data.type);

    switch (data.type) {
      case "categorical":
        await this.fillCategoricalData(
          data.details as FeedbackDefinitionCategoricalDetails,
        );
        break;
      case "numerical":
        await this.fillNumericalData(
          data.details as FeedbackDefinitionNumericalDetails,
        );
        break;
    }

    await this.page
      .getByRole("button", { name: "Update feedback definition" })
      .click();
  }

  async checkNumericValueColumn(data: FeedbackDefinitionData) {
    const cell = this.table
      .getRowLocatorByCellText(data.name)
      .locator("[data-cell-id$='_values']");
    const details = data.details as FeedbackDefinitionNumericalDetails;
    await expect(cell).toHaveText(`Min: ${details.min}, Max: ${details.max}`);
  }

  async checkCategoricalValueColumn(data: FeedbackDefinitionData) {
    const cell = this.table
      .getRowLocatorByCellText(data.name)
      .locator("[data-cell-id$='_values']");
    const details = data.details as FeedbackDefinitionCategoricalDetails;
    await expect(cell).toHaveText(
      Object.keys(details.categories).sort().join(", "),
    );
  }

  async deleteFeedbackDefinition(data: FeedbackDefinitionData) {
    await this.table.openRowActionsByCellText(data.name);
    await this.page.getByRole("menuitem", { name: "Delete" }).click();
    await this.page
      .getByRole("button", { name: "Delete feedback definition" })
      .click();
  }
}
