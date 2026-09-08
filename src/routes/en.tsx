import { createFileRoute } from "@tanstack/react-router";
import { SiteHeader } from "@/components/site-header";

export const Route = createFileRoute("/en")({
  component: English,
  head: () => ({
    meta: [
      { title: "成书" },
      {
        name: "description",
        content: "Share a page from the phone browser. 成书 turns it into a book and opens your reader.",
      },
    ],
  }),
});

function English() {
  return (
    <>
      <SiteHeader lang="en" />
      <main className="mx-auto max-w-[744px] px-5 pt-10 pb-24 text-muted-foreground sm:px-8 sm:pt-14">
        <article className="max-w-[600px] leading-[1.75] [&_p+p]:mt-[1.5em] [&_ul]:mt-[1.5em]" lang="en">
          <p className="text-foreground">
            When you hit a long piece in the phone browser that you actually want to sit with, use the system share sheet and pick 成书. It pulls the article, turns it into EPUB, Markdown, or plain text, and opens WeChat Reading, KOReader, or whatever you use — or hands Markdown to an AI.
          </p>
          <p>Once the format and destination are set, it does not need to sit in the background. You only see it in the share sheet.</p>
          <p>
            Reading long articles in a phone browser is miserable: ads, floating popups, and constant prompts to open an app.
          </p>
          <p>
            That kind of writing belongs in a reader. WeChat Reading has typesetting and translation. KOReader is what you want on e-ink — fonts and page-turns feel right.
          </p>
          <p>
            On Android the two sides barely meet. Readers want a local file. The browser only shares a URL. Getting an article into a reader used to mean: copy the link, find a converter, wait, download, then dig through the file manager to open it. By then the reading mood is gone.
          </p>
          <p>
            I don’t want Pocket. I don’t want this on someone else’s server, and I don’t need a social layer. Both ends are already on the phone. What’s missing is a clean pipe.
          </p>
          <p>This tool does three things:</p>
          <ul className="list-disc space-y-2 pl-5">
            <li>Sit in the system share sheet.</li>
            <li>Take a URL, strip it to the article, lay it out as EPUB, Markdown, or plain text.</li>
            <li>Hand the file to any app that can open it — a reader, or an AI.</li>
          </ul>
          <p>
            It remembers links it has already seen, so it won’t fetch them twice. Set a format and an app, then ignore it.
          </p>
          <p>
            This page is a test case. If 成书 is installed, share it from the browser and see how it looks in your reader.
          </p>
        </article>
      </main>
    </>
  );
}
