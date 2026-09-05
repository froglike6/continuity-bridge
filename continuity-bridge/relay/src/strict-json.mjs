import { TextDecoder } from "node:util";

function scanString(text, start) {
  let index = start + 1;
  while (index < text.length) {
    if (text[index] === "\\") { index += 2; continue; }
    if (text[index] === "\"") return index + 1;
    index += 1;
  }
  throw new SyntaxError("unterminated string");
}

function scanner(text) {
  let index = 0;
  const whitespace = () => { while (/\s/.test(text[index] ?? "")) index += 1; };
  const value = () => {
    whitespace();
    if (text[index] === "{") return object();
    if (text[index] === "[") return array();
    if (text[index] === "\"") { index = scanString(text, index); return; }
    while (index < text.length && !/[\s,}\]]/.test(text[index])) index += 1;
  };
  const object = () => {
    const keys = new Set();
    index += 1;
    whitespace();
    if (text[index] === "}") { index += 1; return; }
    while (index < text.length) {
      whitespace();
      const end = scanString(text, index);
      const key = JSON.parse(text.slice(index, end));
      if (keys.has(key)) throw new SyntaxError("duplicate key");
      keys.add(key);
      index = end;
      whitespace();
      if (text[index] !== ":") throw new SyntaxError("missing colon");
      index += 1;
      value();
      whitespace();
      if (text[index] === "}") { index += 1; return; }
      if (text[index] !== ",") throw new SyntaxError("missing comma");
      index += 1;
    }
  };
  const array = () => {
    index += 1;
    whitespace();
    if (text[index] === "]") { index += 1; return; }
    while (index < text.length) {
      value();
      whitespace();
      if (text[index] === "]") { index += 1; return; }
      if (text[index] !== ",") throw new SyntaxError("missing comma");
      index += 1;
    }
  };
  value();
  whitespace();
  if (index !== text.length) throw new SyntaxError("trailing data");
}

function rejectNonFiniteNumbers(root) {
  const pending = [root];
  while (pending.length > 0) {
    const value = pending.pop();
    if (typeof value === "number" && !Number.isFinite(value)) throw new SyntaxError("non-finite JSON number");
    if (Array.isArray(value)) {
      for (const nested of value) pending.push(nested);
    } else if (value !== null && typeof value === "object") {
      for (const nested of Object.values(value)) pending.push(nested);
    }
  }
}

export function parseStrictJson(buffer) {
  const text = new TextDecoder("utf-8", { fatal: true }).decode(buffer);
  const parsed = JSON.parse(text);
  scanner(text);
  rejectNonFiniteNumbers(parsed);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) throw new SyntaxError("object required");
  return parsed;
}
