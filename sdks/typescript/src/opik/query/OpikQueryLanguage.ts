/**
 * This file contains the OQL parser and validator. It is currently limited in scope to only support
 * simple filters without "and" or "or" operators.
 *
 * The parser is organized into focused modules:
 * - types.ts: Type definitions
 * - constants.ts: Field and operator definitions
 * - tokenizer.ts: Low-level character parsing
 * - validators.ts: Validation logic
 * - parsers/: Specialized parsers for fields, operators, and values
 */

import type {
  FilterExpression,
  FieldToken,
  OperatorToken,
  ValueToken,
} from "./types";
import { QueryTokenizer } from "./tokenizer";
import { validateConnector } from "./validators";
import { FieldParser, OperatorParser, ValueParser } from "./parsers";
import {
  OQLConfig,
  TraceOQLConfig,
  SpanOQLConfig,
  ThreadOQLConfig,
  PromptOQLConfig,
} from "./configs";
import { OPERATORS_WITHOUT_VALUES } from "./constants";

// Re-export types for backward compatibility
export type { FilterExpression };

/**
 * This class implements a parser that can be used to convert a filter string into a list of filters that the BE expects.
 *
 * For example, this class allows you to convert the query string: `input contains "hello"` into
 * `[{field: 'input', operator: 'contains', value: 'hello'}]` as expected by the BE.
 *
 * The parser follows a standard architecture:
 * 1. Tokenization: Convert string into characters (QueryTokenizer)
 * 2. Parsing: Extract structured tokens (FieldParser, OperatorParser, ValueParser)
 * 3. Validation: Ensure tokens are valid (validators)
 * 4. Assembly: Build final filter expressions
 */
export class OpikQueryLanguage {
  private readonly filterExpressions: FilterExpression[] | null;
  public readonly parsedFilters: string | null;
  private readonly config: OQLConfig;

  constructor(queryString?: string, config?: OQLConfig) {
    const normalizedQuery = queryString || "";
    this.config = config || new TraceOQLConfig();

    this.filterExpressions = normalizedQuery
      ? this.parse(normalizedQuery)
      : null;

    this.parsedFilters = this.filterExpressions
      ? JSON.stringify(this.filterExpressions)
      : null;
  }

  /**
   * Create an OpikQueryLanguage instance for trace filtering
   */
  static forTraces(queryString?: string): OpikQueryLanguage {
    return new OpikQueryLanguage(queryString, new TraceOQLConfig());
  }

  /**
   * Create an OpikQueryLanguage instance for span filtering
   */
  static forSpans(queryString?: string): OpikQueryLanguage {
    return new OpikQueryLanguage(queryString, new SpanOQLConfig());
  }

  /**
   * Create an OpikQueryLanguage instance for trace thread filtering
   */
  static forThreads(queryString?: string): OpikQueryLanguage {
    return new OpikQueryLanguage(queryString, new ThreadOQLConfig());
  }

  /**
   * Create an OpikQueryLanguage instance for prompt filtering
   */
  static forPrompts(queryString?: string): OpikQueryLanguage {
    return new OpikQueryLanguage(queryString, new PromptOQLConfig());
  }

  /**
   * Returns the parsed filter expressions
   */
  public getFilterExpressions(): FilterExpression[] | null {
    return this.filterExpressions;
  }

  /**
   * Main parsing method that orchestrates the parsing process
   */
  private parse(queryString: string): FilterExpression[] {
    const tokenizer = new QueryTokenizer(queryString);
    const expressions: FilterExpression[] = [];

    while (!tokenizer.isAtEnd()) {
      const expression = this.parseExpression(tokenizer);
      expressions.push(expression);

      tokenizer.skipWhitespace();

      if (!tokenizer.isAtEnd()) {
        const shouldContinue = this.parseConnector(tokenizer);
        if (!shouldContinue) {
          break;
        }
      }
    }

    return expressions;
  }

  /**
   * Parses a single filter expression (field operator value)
   */
  private parseExpression(tokenizer: QueryTokenizer): FilterExpression {
    const field = FieldParser.parse(tokenizer, this.config);
    const operator = OperatorParser.parse(
      tokenizer,
      this.getFieldName(field),
      this.config
    );
    const value = (OPERATORS_WITHOUT_VALUES as readonly string[]).includes(operator.operator)
      ? { value: null }
      : ValueParser.parse(tokenizer);

    return this.buildExpression(field, operator, value);
  }

  /**
   * Extracts the field name from a FieldToken for operator validation
   */
  private getFieldName(field: FieldToken): string {
    return field.field;
  }

  /**
   * Builds a FilterExpression from parsed tokens
   */
  private buildExpression(
    field: FieldToken,
    operator: OperatorToken,
    value: ValueToken
  ): FilterExpression {
    const expression: FilterExpression = {
      field: field.field,
      operator: operator.operator,
      value: value.value,
      type: field.columnType,
    };

    if (field.type === "nested") {
      expression.key = field.key;
    }

    return expression;
  }

  /**
   * Parses a connector (AND/OR) between expressions
   * @returns true if parsing should continue, false otherwise
   */
  private parseConnector(tokenizer: QueryTokenizer): boolean {
    const startPos = tokenizer.getPosition();
    const connector = tokenizer.consumeWhile((char) =>
      QueryTokenizer.isLetterChar(char)
    );

    try {
      return validateConnector(connector);
    } catch (error) {
      // Add context about where the error occurred
      if (
        error instanceof Error &&
        error.message.includes("trailing characters")
      ) {
        const remaining = tokenizer.getFullInput().slice(startPos);
        throw new Error(
          `Invalid filter string, trailing characters ${remaining}`
        );
      }
      throw error;
    }
  }
}
