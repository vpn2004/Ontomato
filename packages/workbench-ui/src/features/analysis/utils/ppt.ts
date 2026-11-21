import type { PptGenerationOptions } from "@ontomato/contracts/ppt";
export type { PptGenerationOptions } from "@ontomato/contracts/ppt";
import { nodeApiBlobPost } from "../../../utils/api";
import { renderMarkdown } from "../../../utils/markdown";
import type { ReportSection } from "../../../utils/analysis-report";
import { workbenchI18n } from "../../../i18n";

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function safeFileName(value: string): string {
  return (
    (value || "analysis-report")
      .replace(/[\\/:*?"<>|]/g, "_")
      .replace(/./g, (character) => (character.charCodeAt(0) < 32 ? "_" : character))
      .trim()
      .slice(0, 80) || "analysis-report"
  );
}

function splitSlideContent(content: string): string[] {
  const slides = content
    .split(/(?=^#{1,2}\s+)/m)
    .map((part) => part.trim())
    .filter(Boolean);
  return slides.length ? slides : [content.trim()];
}

export async function downloadPptx(title: string, sections: readonly ReportSection[], options: PptGenerationOptions = {}): Promise<void> {
  const blob = await nodeApiBlobPost("/ppt/export", {title, sections, ...options});
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${safeFileName(title)}.pptx`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function buildPptHtml(title: string, sections: readonly ReportSection[]): string {
  const slideContents = sections.flatMap((section) => splitSlideContent(section.content));
  const slides = slideContents
    .map(
      (content, index) => `
      <article class="slide ${index === 0 ? "is-active" : ""}" data-slide="${index}">
        <div class="slide-number">${String(index + 1).padStart(2, "0")}</div>
        <div class="slide-content">${renderMarkdown(content)}</div>
        <div class="slide-footer"><span>${escapeHtml(title)}</span><span>${index + 1} / ${slideContents.length}</span></div>
      </article>`
    )
    .join("\n");

  const serializedTitle = JSON.stringify(title).replace(/</g, "\\u003c");
  return `<!doctype html>
<html lang="${escapeHtml(workbenchI18n().global.locale.value)}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    :root { color-scheme: light; --ink:#172033; --muted:#667085; --line:#d9dee8; --blue:#2457d6; --paper:#fff; --canvas:#edf1f7; }
    * { box-sizing:border-box; }
    html,body { margin:0; min-height:100%; background:var(--canvas); color:var(--ink); font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif; }
    body { padding:32px; }
    .deck { display:grid; gap:24px; justify-items:center; }
    .slide { display:none; position:relative; width:min(1280px, calc(100vw - 64px)); aspect-ratio:16/9; padding:7.5% 8%; overflow:hidden; background:var(--paper); box-shadow:0 18px 50px rgba(25,39,70,.13); }
    .slide.is-active { display:block; }
    .slide::before { content:""; position:absolute; top:0; left:0; width:28%; height:6px; background:var(--blue); }
    .slide-number { position:absolute; top:7%; right:8%; color:var(--blue); font-size:12px; letter-spacing:.12em; font-weight:700; }
    .slide-content { height:calc(100% - 28px); overflow:hidden; font-size:clamp(14px, 1.35vw, 22px); line-height:1.5; }
    .slide-content h1 { max-width:82%; margin:0 0 24px; font-size:clamp(28px, 4vw, 58px); line-height:1.08; letter-spacing:0; color:var(--ink); }
    .slide-content h2 { margin:0 0 18px; font-size:clamp(24px, 3vw, 42px); line-height:1.12; letter-spacing:0; color:var(--ink); }
    .slide-content h3 { margin:20px 0 10px; font-size:clamp(18px, 2vw, 28px); }
    .slide-content p { margin:10px 0; }
    .slide-content ul,.slide-content ol { margin:12px 0; padding-left:1.3em; }
    .slide-content li { margin:8px 0; }
    .slide-content strong { color:var(--blue); }
    .slide-content blockquote { margin:18px 0; padding:12px 18px; border-left:4px solid var(--blue); color:var(--muted); background:#f5f7fb; }
    .slide-content table { width:100%; border-collapse:collapse; font-size:.72em; }
    .slide-content th,.slide-content td { padding:8px 10px; border-bottom:1px solid var(--line); text-align:left; }
    .slide-content th { color:var(--blue); background:#f5f7fb; }
    .slide-content pre { padding:12px; overflow:auto; background:#f5f7fb; font-size:.7em; }
    .chart-section,.chart-wrapper { width:100%; }
    .visualization-iframe { display:block; width:100%; height:min(42vh, 400px); border:1px solid var(--line); border-radius:8px; }
    .slide-footer { position:absolute; right:8%; bottom:4.5%; left:8%; display:flex; justify-content:space-between; color:var(--muted); font-size:11px; }
    .controls { position:fixed; right:24px; bottom:20px; z-index:10; display:flex; gap:8px; }
    .controls button { border:1px solid var(--line); border-radius:6px; padding:8px 12px; background:#fff; color:var(--ink); cursor:pointer; }
    .controls button:hover { border-color:var(--blue); color:var(--blue); }
    @media print { body { padding:0; background:#fff; } .slide { display:block; width:100vw; height:56.25vw; box-shadow:none; page-break-after:always; } .controls { display:none; } }
    @media (max-width:700px) { body { padding:12px; } .slide { width:calc(100vw - 24px); padding:9% 8%; } .slide-content { font-size:12px; } .slide-content h1 { font-size:28px; } .slide-content h2 { font-size:23px; } }
  </style>
</head>
<body>
  <main class="deck">${slides}</main>
  <nav class="controls" aria-label="slide controls">
    <button type="button" data-action="previous">Previous</button>
    <button type="button" data-action="next">Next</button>
    <button type="button" data-action="fullscreen">Fullscreen</button>
  </nav>
  <script>
    (() => {
      const slides = [...document.querySelectorAll('.slide')];
      let index = 0;
      const show = (next) => {
        index = (next + slides.length) % slides.length;
        slides.forEach((slide, i) => slide.classList.toggle('is-active', i === index));
      };
      document.addEventListener('click', (event) => {
        const action = event.target.closest('[data-action]')?.dataset.action;
        if (action === 'previous') show(index - 1);
        if (action === 'next') show(index + 1);
        if (action === 'fullscreen') document.documentElement.requestFullscreen?.();
      });
      document.addEventListener('keydown', (event) => {
        if (['ArrowRight', 'ArrowDown', ' ', 'PageDown'].includes(event.key)) { event.preventDefault(); show(index + 1); }
        if (['ArrowLeft', 'ArrowUp', 'PageUp'].includes(event.key)) { event.preventDefault(); show(index - 1); }
        if (event.key === 'f') document.documentElement.requestFullscreen?.();
      });
      document.title = ${serializedTitle};
    })();
  </script>
</body>
</html>`;
}

export function downloadPptHtml(title: string, sections: readonly ReportSection[]): void {
  const html = buildPptHtml(title, sections);
  const url = URL.createObjectURL(new Blob([html], { type: "text/html;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `${safeFileName(title)}.ppt.html`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
