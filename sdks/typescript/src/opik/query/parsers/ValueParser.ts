/**
 * Parser for value tokens (strings in quotes or numbers)
 */

import { QueryTokenizer } from "../tokenizer";
import { validateClosingQuote } from "../validators";
import type { ValueToken } from "../types";

/**
 * Parses a value (quoted string or number)
 */
export class ValueParser {
  static parse(tokenizer: QueryTokenizer): ValueToken {
    tokenizer.skipWhitespace();

    const startPos = tokenizer.getPosition();
    const currentChar = tokenizer.peekChar();

    if (currentChar === '"') {
      return this.parseQuotedString(tokenizer, startPos);
    }

    if (QueryTokenizer.isDigitChar(currentChar) || currentChar === "-") {
      return this.parseNumber(tokenizer);
    }

    const remaining = tokenizer.getRemainingInput();
    throw new Error(
      `Invalid value ${remaining.slice(0, 20)}, expected a string in double quotes("value") or a number`
    );
  }

  private static parseQuotedString(
    tokenizer: QueryTokenizer,
    startPos: number
  ): ValueToken {
    // Skip opening quote
    tokenizer.advance();

    const valueStart = tokenizer.getPosition();
    let foundClosingQuote = false;

    // Parse until closing quote or end of string
    while (!tokenizer.isAtEnd()) {
      if (tokenizer.peekChar() === '"') {
        foundClosingQuote = true;
        break;
      }
      tokenizer.advance();
    }

    validateClosingQuote(
      foundClosingQuote,
      startPos,
      `value starting at position ${startPos}`
    );

    const value = tokenizer.sliceFrom(valueStart);

    // Skip closing quote
    tokenizer.advance();

    return { value };
  }

  private static parseNumber(tokenizer: QueryTokenizer): ValueToken {
    let value = "";

    // Handle negative sign
    if (tokenizer.peekChar() === "-") {
      value += tokenizer.consumeChar();
    }

    // Parse integer part
    value += this.parseDigits(tokenizer);

    // Parse decimal part if present
    if (tokenizer.peekChar() === ".") {
      value += tokenizer.consumeChar();
      value += this.parseDigits(tokenizer);
    }

    return { value };
  }

  static parseArrayValue(tokenizer: QueryTokenizer): ValueToken {
    tokenizer.skipWhitespace();

    if (tokenizer.peekChar() !== "(") {
      const remaining = tokenizer.getRemainingInput();
      throw new Error(
        `Expected array value starting with '(' for in/not_in operator, got: ${remaining.slice(0, 20)}`
      );
    }
    tokenizer.advance(); // skip '('

    const items: string[] = [];

    while (true) {
      tokenizer.skipWhitespace();

      if (tokenizer.isAtEnd()) {
        throw new Error("Unterminated array value, missing ')'");
      }

      if (tokenizer.peekChar() === ")") {
        if (items.length === 0) {
          throw new Error(
            "Expected at least one item inside (...) for in/not_in operator"
          );
        }
        tokenizer.advance();
        break;
      }

      if (items.length > 0) {
        if (tokenizer.peekChar() !== ",") {
          const remaining = tokenizer.getRemainingInput();
          throw new Error(
            `Expected ',' between array elements, got: ${remaining.slice(0, 20)}`
          );
        }
        tokenizer.advance(); // skip ','
        tokenizer.skipWhitespace();
      }

      if (tokenizer.isAtEnd() || tokenizer.peekChar() !== '"') {
        const remaining = tokenizer.getRemainingInput();
        throw new Error(
          `Array elements must be quoted strings, got: ${remaining.slice(0, 20)}`
        );
      }

      const item = this.parseQuotedString(tokenizer, tokenizer.getPosition());
      const itemValue = item.value as string;
      if (itemValue.includes(",")) {
        throw new Error(
          `Array element values cannot contain commas (got: "${itemValue}"). The backend uses comma as the array delimiter.`
        );
      }
      items.push(itemValue);
    }

    return { value: items.join(",") };
  }

  private static parseDigits(tokenizer: QueryTokenizer): string {
    return tokenizer.consumeWhile((char) => QueryTokenizer.isDigitChar(char));
  }
}
