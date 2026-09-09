import assert from "node:assert/strict";
import { test } from "node:test";
import { assertPublicHttpUrl, isPrivateIp } from "./ssrf.ts";
import { cacheKey } from "./export-cache.ts";

test("private hosts and IPs are rejected before fetch", () => {
  assert.throws(() => assertPublicHttpUrl("http://127.0.0.1/"));
  assert.throws(() => assertPublicHttpUrl("http://10.0.0.3/"));
  assert.throws(() => assertPublicHttpUrl("http://169.254.169.254/latest"));
  assert.throws(() => assertPublicHttpUrl("http://192.168.1.1/"));
  assert.ok(isPrivateIp("127.0.0.1"));
  assert.ok(isPrivateIp("::1"));
  assert.equal(isPrivateIp("1.1.1.1"), false);
  assertPublicHttpUrl("https://example.org/a");
});

test("cache key uses full text and title so 120-char prefix collisions cannot alias books", () => {
  const prefix = "a".repeat(120);
  const a = cacheKey("txt", { text: prefix + "ONE", title: "t" });
  const b = cacheKey("txt", { text: prefix + "TWO", title: "t" });
  assert.notEqual(a, b);
  const c = cacheKey("txt", { html: "<p>same</p>", title: "A" });
  const d = cacheKey("txt", { html: "<p>same</p>", title: "B" });
  assert.notEqual(c, d);
});
