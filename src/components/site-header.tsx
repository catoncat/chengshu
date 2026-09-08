import { useEffect, useState } from "react";

const APK = "https://0nl.onl/chengshu.apk";
const GITHUB = "https://github.com/catoncat/chengshu";

export function SiteHeader({ lang }: { lang: "zh" | "en" }) {
  const [dark, setDark] = useState(false);

  useEffect(() => {
    setDark(document.documentElement.classList.contains("dark"));
  }, []);

  function toggleTheme() {
    const next = !document.documentElement.classList.contains("dark");
    document.documentElement.classList.toggle("dark", next);
    document.documentElement.classList.toggle("light", !next);
    localStorage.setItem("chengshu-theme", next ? "dark" : "light");
    setDark(next);
  }

  return (
    <header className="sticky top-0 z-20">
      <div className="absolute inset-0 border-b border-border/40 bg-background/70 backdrop-blur-md" />
      <div className="relative mx-auto flex h-14 max-w-[744px] items-center justify-between gap-4 px-5 min-[641px]:px-8">
        <a href={lang === "en" ? "/en" : "/"} className="text-foreground no-underline hover:text-foreground">
          成书
        </a>
        <nav className="flex items-center gap-3.5 text-[13px] text-muted-foreground min-[641px]:gap-5">
          <a
            href={lang === "zh" ? "/en" : "/"}
            className="text-inherit no-underline hover:text-foreground"
            aria-label={lang === "zh" ? "English" : "中文"}
          >
            <span className={lang === "zh" ? "text-foreground" : ""}>中</span>
            <span className="mx-[0.15em] opacity-40">/</span>
            <span className={lang === "en" ? "text-foreground" : ""}>EN</span>
          </a>
          <button
            type="button"
            onClick={toggleTheme}
            className="inline-flex border-0 bg-transparent p-0 text-inherit hover:text-foreground"
            aria-label={dark ? "Light" : "Dark"}
          >
            {dark ? <SunIcon /> : <MoonIcon />}
          </button>
          <a
            href={GITHUB}
            className="inline-flex text-inherit no-underline hover:text-foreground"
            aria-label="GitHub"
          >
            <GitHubIcon />
          </a>
          <a href={APK} className="text-foreground no-underline hover:underline">
            {lang === "zh" ? "下载" : "Download"}
          </a>
        </nav>
      </div>
    </header>
  );
}

function GitHubIcon() {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor" aria-hidden="true">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82A7.7 7.7 0 0 1 8 4.84c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
      <path d="M13.5 9.3A5.6 5.6 0 0 1 6.7 2.5 5.6 5.6 0 1 0 13.5 9.3z" />
    </svg>
  );
}

function SunIcon() {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
      <circle cx="8" cy="8" r="3.1" />
      <path d="M8 1.4v1.3M8 13.3v1.3M1.4 8h1.3M13.3 8h1.3M3.1 3.1l.9.9M12 12l.9.9M3.1 12.9l.9-.9M12 4l.9-.9" strokeLinecap="round" />
    </svg>
  );
}
