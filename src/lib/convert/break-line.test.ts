import assert from "node:assert/strict";
import { test } from "node:test";
import { canBreakBetween, wrapText } from "./break-line.ts";

const cols = (s: string) => s.length;

test("period and comma do not start a line", () => {
  assert.equal(canBreakBetween("字", "。"), false);
  assert.equal(canBreakBetween("字", "，"), false);
  assert.equal(canBreakBetween("字", "、"), false);
  const lines = wrapText("一二三。四五六", 4, cols);
  assert.ok(lines.every((l) => !l.startsWith("。")));
  assert.ok(lines.some((l) => l.includes("。")));
});

test("opening quotes do not end a line", () => {
  assert.equal(canBreakBetween("《", "书"), false);
  const lines = wrapText("看《成书》正文", 3, cols);
  assert.ok(lines.every((l) => !l.endsWith("《")));
});

test("latin words stay intact", () => {
  assert.equal(canBreakBetween("l", "l"), false);
  const lines = wrapText("hello world", 8, cols);
  assert.deepEqual(lines, ["hello", "world"]);
});

test("CJK can break between characters", () => {
  assert.equal(canBreakBetween("中", "文"), true);
  const lines = wrapText("中文排版", 2, cols);
  assert.deepEqual(lines, ["中文", "排版"]);
});
