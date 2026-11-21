import { afterEach, describe, expect, it, vi } from "vitest";
import { installAuthHost } from "../auth";
import { withPptDownloadCredentials } from "../ppt-links";
import { renderAgentMarkdown } from "../agent-markdown";
import { renderMarkdown } from "../markdown";

const credential = {token: null as string | null, apiKey: null as string | null};
const host = {getToken: () => credential.token, getApiKey: () => credential.apiKey, verifySessionOnce: async () => "valid" as const, handleAuthExpired() {}, getUserInfo: () => null, responseStatusError: () => null};
installAuthHost(host);
const url = "/api/ppt/files/aee745cf-52d3-4c33-a6ef-a5ea2b5e1111";
afterEach(() => {credential.token = null; credential.apiKey = null; vi.unstubAllGlobals();});

describe("PPT download links", () => {
  it("adds the current token in both report and agent Markdown renderers", () => {
    credential.token = "current-token"; credential.apiKey = "ignored-key";
    for (const render of [renderMarkdown, renderAgentMarkdown]) {
      const result = render(`[Download PPT](${url})`);
      expect(result).toContain(`href="${url}?tk=current-token"`);
      expect(result).not.toContain("ignored-key");
    }
  });

  it("supports API key and anonymous authentication without changing unrelated links", () => {
    credential.apiKey = "key&value";
    expect(withPptDownloadCredentials(`<a href="${url}">PPT</a>`)).toContain("?apiKey=key%26value");
    credential.apiKey = null;
    expect(withPptDownloadCredentials(`<a href="${url}">PPT</a>`)).toContain(`href="${url}"`);
    expect(withPptDownloadCredentials('<a href="https://example.com">External</a>')).toBe('<a href="https://example.com">External</a>');
  });

  it("resolves persisted links under the deployment API prefix", async () => {
    vi.stubGlobal("window", {__APP_CONFIG__: {API_BASE: "/demo/api/"}});
    vi.resetModules();
    (await import("../auth")).installAuthHost(host);
    credential.token = "new-token";
    const prefixed = (await import("../ppt-links")).withPptDownloadCredentials;
    expect(prefixed(`<a href="${url}">PPT</a>`)).toContain('href="/demo/api/ppt/files/aee745cf-52d3-4c33-a6ef-a5ea2b5e1111?tk=new-token"');
  });
});
