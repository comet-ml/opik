import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { splitWithBracketPreservation } from "@/lib/pythonArgumentsParser";
import { getCodeArgumentNames } from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";

const SPANS_ARGUMENT = "spans";
const SECTIONS = ["input", "output", "metadata"];
const SECTION_PATH_REGEX = /^(input|output|metadata)(?=$|\.|\[)(.*)$/;
const PATH_TOKEN_REGEX = /\.([^.[\]]+)|\[(\d+)\]|\[["']([^"']+)["']\]/g;
const SCORE_DEF_REGEX = /def\s+score\s*\(/;
const HEADER_END_REGEX = /^\s*(?:->[^:\n]*)?:[ \t]*(#.*)?\n/;

export const getLegacyCodeArguments = (
  scope: EVALUATORS_RULE_SCOPE,
  codeArguments: Record<string, string> = {},
) => {
  const allowedNames = getCodeArgumentNames(scope);
  return Object.entries(codeArguments).filter(
    ([name, path]) =>
      Boolean(path) &&
      (!allowedNames.includes(name) ||
        (name !== SPANS_ARGUMENT && path !== name)),
  );
};

const toPythonAccessor = (path: string) =>
  Array.from(path.matchAll(PATH_TOKEN_REGEX))
    .map(([, key, index, quotedKey]) =>
      index !== undefined
        ? `[${index}]`
        : `[${JSON.stringify(key ?? quotedKey)}]`,
    )
    .join("");

const toAssignment = (name: string, path: string) => {
  const match = path.match(SECTION_PATH_REGEX);
  if (!match) return `${name} = ${JSON.stringify(path)}`;
  const [, section, rest] = match;
  return rest
    ? `${name} = json.loads(${section})${toPythonAccessor(rest)}`
    : `${name} = ${section}`;
};

const getParameterName = (parameter: string) =>
  parameter.split(/[:=]/)[0].trim();

const findClosingParenthesis = (code: string, openIndex: number) => {
  let depth = 0;
  for (let i = openIndex; i < code.length; i++) {
    if (code[i] === "(") depth++;
    if (code[i] === ")" && --depth === 0) return i;
  }
  return -1;
};

export const convertToStandardSignature = (
  code: string,
  scope: EVALUATORS_RULE_SCOPE,
  codeArguments: Record<string, string>,
): string | null => {
  const defMatch = SCORE_DEF_REGEX.exec(code);
  if (!defMatch) return null;
  const openIndex = defMatch.index + defMatch[0].length - 1;
  const closeIndex = findClosingParenthesis(code, openIndex);
  if (closeIndex === -1) return null;
  const headerEnd = HEADER_END_REGEX.exec(code.slice(closeIndex + 1));
  if (!headerEnd) return null;
  const bodyStart = closeIndex + 1 + headerEnd[0].length;
  const indent = code.slice(bodyStart).match(/^(?:[ \t]*\n)*([ \t]+)\S/)?.[1];
  if (!indent) return null;

  const allowedNames = getCodeArgumentNames(scope);
  const parameters = splitWithBracketPreservation(
    code.slice(openIndex + 1, closeIndex),
  );
  const mapped = parameters
    .map(getParameterName)
    .filter((name) => name in codeArguments && name !== SPANS_ARGUMENT);
  const usedSections = new Set<string>();
  const kept: string[] = [];

  parameters.forEach((parameter) => {
    const name = getParameterName(parameter);
    if (name === "self" || mapped.includes(name)) return;
    if (allowedNames.includes(name)) {
      usedSections.add(name);
      return;
    }
    kept.push(parameter);
  });
  mapped.forEach((name) => {
    const section = codeArguments[name].match(SECTION_PATH_REGEX)?.[1];
    if (section) usedSections.add(section);
  });

  const signature = [
    "self",
    ...SECTIONS.filter((section) => usedSections.has(section)).map((section) =>
      section === "metadata" ? "metadata=None" : section,
    ),
    ...(usedSections.has(SPANS_ARGUMENT) ? ["spans=None"] : []),
    ...kept,
  ].join(", ");

  const assignments = [
    ...mapped.filter((name) => !SECTIONS.includes(name)),
    ...mapped.filter((name) => SECTIONS.includes(name)),
  ]
    .map((name) => toAssignment(name, codeArguments[name]))
    .filter((line) => !/^(\w+) = \1$/.test(line));

  const converted =
    code.slice(0, openIndex + 1) +
    signature +
    code.slice(closeIndex, bodyStart) +
    assignments.map((line) => `${indent}${line}\n`).join("") +
    code.slice(bodyStart);

  const needsJson =
    assignments.some((line) => line.includes("json.loads(")) &&
    !/^\s*import json\b/m.test(code);
  return needsJson ? `import json\n${converted}` : converted;
};
