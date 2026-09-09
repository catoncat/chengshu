import dns from "node:dns/promises";
import net from "node:net";

const PRIVATE_HOST =
  /^(localhost|127\.|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|::1|\[::1\]|\[fc|\[fd|\[fe80)/i;

export function isPrivateIp(ip: string): boolean {
  if (net.isIPv4(ip)) {
    const p = ip.split(".").map((n) => Number(n));
    if (p[0] === 10 || p[0] === 127 || p[0] === 0) return true;
    if (p[0] === 192 && p[1] === 168) return true;
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return true;
    if (p[0] === 169 && p[1] === 254) return true;
    return false;
  }
  if (net.isIPv6(ip)) {
    const lower = ip.toLowerCase();
    if (lower === "::1" || lower === "0:0:0:0:0:0:0:1") return true;
    if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80")) return true;
    if (lower.startsWith("::ffff:")) return isPrivateIp(lower.slice(7));
    return false;
  }
  return true;
}

export function assertPublicHttpUrl(raw: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new Error("链接无效");
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("只支持 http / https 链接");
  }
  const host = parsed.hostname;
  if (!host || PRIVATE_HOST.test(host) || host.endsWith(".local")) {
    throw new Error("不能抓取内网地址");
  }
  if (net.isIP(host) && isPrivateIp(host)) {
    throw new Error("不能抓取内网地址");
  }
  return parsed;
}

export async function assertPublicTarget(raw: string): Promise<URL> {
  const url = assertPublicHttpUrl(raw);
  if (net.isIP(url.hostname)) return url;
  const addrs = await dns.lookup(url.hostname, { all: true });
  for (const addr of addrs) {
    if (isPrivateIp(addr.address)) throw new Error("不能抓取内网地址");
  }
  return url;
}

export function isPublicHttpUrl(raw: string): boolean {
  try {
    assertPublicHttpUrl(raw);
    return true;
  } catch {
    return false;
  }
}

export async function fetchPublic(
  raw: string,
  init: RequestInit & { maxBytes?: number } = {},
): Promise<Response> {
  const maxBytes = init.maxBytes ?? 2_500_000;
  let current = raw;
  for (let hop = 0; hop < 5; hop++) {
    await assertPublicTarget(current);
    const res = await fetch(current, {
      ...init,
      redirect: "manual",
      cache: "no-store",
    });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get("location");
      if (!loc) throw new Error("抓取失败（重定向无效）");
      current = new URL(loc, current).toString();
      continue;
    }
    const length = Number(res.headers.get("content-length") || "0");
    if (length > maxBytes) throw new Error("页面太大，换一篇短一点的");
    return res;
  }
  throw new Error("抓取失败（重定向过多）");
}
