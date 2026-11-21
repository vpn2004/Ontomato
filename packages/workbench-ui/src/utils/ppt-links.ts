import { authHost } from "./auth";
import { apiUrl } from "./api-base";

export function withPptDownloadCredentials(html: string): string {
  return html.replace(/href="(\/api\/ppt\/files\/[0-9a-f-]+)"/gi, (_match, fileUrl: string) => {
    const host = authHost();
    const params = new URLSearchParams();
    const token = host.getToken();
    const apiKey = host.getApiKey();
    if (token) params.set("tk", token);
    else if (apiKey) params.set("apiKey", apiKey);
    const url = apiUrl(fileUrl.slice(4)) + (params.size ? `?${params}` : "");
    return `href="${url.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;")}"`;
  });
}
