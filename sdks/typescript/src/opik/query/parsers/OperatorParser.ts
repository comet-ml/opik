/**
 * Parser for operator tokens (e.g., "=", "contains", ">=")
 */

import { QueryTokenizer } from "../tokenizer";
import { validateOperator } from "../validators";
import type { OperatorToken } from "../types";
import type { OQLConfig } from "../configs";

/**
 * Parses an operator (symbolic or word-based)
 */
export class OperatorParser {
  static parse(
    tokenizer: QueryTokenizer,
    field: string,
    config: OQLConfig
  ): OperatorToken {
    tokenizer.skipWhitespace();

    const currentChar = tokenizer.peekChar();

    // Try to parse symbolic operators first
    const symbolicOperator = this.tryParseSymbolicOperator(
      tokenizer,
      currentChar
    );
    if (symbolicOperator) {
      validateOperator(field, symbolicOperator, config);
      return { operator: symbolicOperator };
    }

    // Parse word operators (contains, not_contains, starts_with, ends_with)
    const wordOperator = this.parseWordOperator(tokenizer);
    validateOperator(field, wordOperator, config);

    return { operator: wordOperator };
  }

  private static tryParseSymbolicOperator(
    tokenizer: QueryTokenizer,
    currentChar: string
  ): string | null {
    // Single character operators that might have compound forms
    if (currentChar === "=") {
      tokenizer.advance();
      return "=";
    }

    if (currentChar === "<" || currentChar === ">") {
      const operator = currentChar;
      tokenizer.advance();

      // Check for compound operators (<=, >=)
      if (tokenizer.peekChar() === "=") {
        tokenizer.advance();
        return operator + "=";
      }

      return operator;
    }

    if (currentChar === "!") {
      // Check for !=
      if (tokenizer.peekCharAt(1) === "=") {
        tokenizer.advance(2);
        return "!=";
      }
    }

    return null;
  }

  private static parseWordOperator(tokenizer: QueryTokenizer): string {
    return tokenizer.consumeWhile(
      (char) => !QueryTokenizer.isWhitespaceChar(char)
    );
  }
}
