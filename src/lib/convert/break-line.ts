/** Line-start punctuation that must not begin a line (CJK kinsoku). */
const NO_START = new Set(
  "!),.:;?]}%，、。．！？；：》」』】）…—－°′″‰℃、”’".split(""),
);
/** Line-end punctuation that must not end a line. */
const NO_END = new Set("([{‘“〈《「『【（".split(""));

function isLatin(ch: string) {
  return /[A-Za-z0-9]/.test(ch);
}

export function canBreakBetween(left: string, right: string): boolean {
  if (!left || !right) return false;
  if (NO_END.has(left)) return false;
  if (NO_START.has(right)) return false;
  if (isLatin(left) && isLatin(right)) return false;
  if (/\s/.test(right)) return true;
  return true;
}

export function wrapText(
  text: string,
  maxWidth: number,
  measure: (s: string) => number,
): string[] {
  const out: string[] = [];
  for (const para of text.split("\n")) {
    if (!para) {
      out.push("");
      continue;
    }
    const chars = Array.from(para);
    let line = "";
    for (const ch of chars) {
      const next = line + ch;
      if (!line || measure(next) <= maxWidth) {
        line = next;
        continue;
      }
      const overflow = next;
      let breakAt = -1;
      for (let i = overflow.length - 1; i >= 1; i--) {
        const left = overflow[i - 1]!;
        const right = overflow[i]!;
        if (!canBreakBetween(left, right)) continue;
        const head = overflow.slice(0, i).replace(/\s+$/g, "");
        if (measure(head) <= maxWidth) {
          breakAt = i;
          break;
        }
      }
      if (breakAt < 0) {
        out.push(line.replace(/\s+$/g, ""));
        line = ch.replace(/^\s+/g, "");
      } else {
        out.push(overflow.slice(0, breakAt).replace(/\s+$/g, ""));
        line = overflow.slice(breakAt).replace(/^\s+/g, "");
      }
    }
    if (line) out.push(line.replace(/\s+$/g, ""));
  }
  return out;
}
