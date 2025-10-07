/**
 * Parser for field tokens (e.g., "name", "metadata.key", "usage.total_tokens")
 */

import { QueryTokenizer } from "../tokenizer";
import {
  validateFieldExists,
  validateFieldKey,
  validateClosingQuote,
} from "../validators";
import { COLUMNS } from "../constants";
import type { FieldToken } from "../types";

/**
 * Parses a field reference, which may include a key (e.g., "metadata.version")
 */
export class FieldParser {
  static parse(tokenizer: QueryTokenizer): FieldToken {
    tokenizer.skipWhitespace();

    const field = this.parseFieldName(tokenizer);
    validateFieldExists(field);

    // Check if there's a key (dot notation)
    if (tokenizer.peekChar() === ".") {
      return this.parseFieldWithKey(tokenizer, field);
    }

    return {
      type: "simple",
      field,
      columnType: COLUMNS[field],
    };
  }

  private static parseFieldName(tokenizer: QueryTokenizer): string {
    return tokenizer.consumeWhile((char) => QueryTokenizer.isFieldChar(char));
  }

  private static parseFieldWithKey(
    tokenizer: QueryTokenizer,
    field: string
  ): FieldToken {
    // Skip the dot
    tokenizer.advance();

    const key = this.parseKey(tokenizer);
    validateFieldKey(field, key);

    // Special handling for usage fields - they become top-level fields
    if (field === "usage") {
      const fullField = `usage.${key}`;
      return {
        type: "simple",
        field: fullField,
        columnType: COLUMNS[fullField],
      };
    }

    return {
      type: "nested",
      field,
      key,
      columnType: COLUMNS[field],
    };
  }

  private static parseKey(tokenizer: QueryTokenizer): string {
    const currentChar = tokenizer.peekChar();
    const isQuoted = QueryTokenizer.isQuoteChar(currentChar);

    if (isQuoted) {
      return this.parseQuotedKey(tokenizer, currentChar);
    }

    return this.parseUnquotedKey(tokenizer);
  }

  private static parseUnquotedKey(tokenizer: QueryTokenizer): string {
    return tokenizer.consumeWhile((char) => QueryTokenizer.isFieldChar(char));
  }

  private static parseQuotedKey(
    tokenizer: QueryTokenizer,
    quoteChar: string
  ): string {
    const startPos = tokenizer.getPosition();

    // Skip opening quote
    tokenizer.advance();

    let key = "";
    let foundClosingQuote = false;

    while (!tokenizer.isAtEnd()) {
      const char = tokenizer.peekChar();

      if (char === quoteChar) {
        // Check for escaped quote (doubled quote)
        if (tokenizer.peekCharAt(1) === quoteChar) {
          key += quoteChar;
          tokenizer.advance(2); // Skip both quotes
          continue;
        }

        // Found closing quote
        foundClosingQuote = true;
        tokenizer.advance(); // Skip closing quote
        break;
      }

      key += char;
      tokenizer.advance();
    }

    const context = tokenizer.getFullInput().slice(startPos);
    validateClosingQuote(foundClosingQuote, startPos, context);

    return key;
  }
}
