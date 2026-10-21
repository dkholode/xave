const form = document.getElementById("form");
const statusEl = document.getElementById("status");
const button = document.getElementById("go");

function show(kind, message) {
  statusEl.hidden = false;
  statusEl.className = "status " + kind;
  statusEl.textContent = message;
}

function filenameFrom(resp, fallback) {
  const cd = resp.headers.get("Content-Disposition") || "";
  const encoded = cd.match(/filename\*=UTF-8''([^;]+)/i);
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1]);
    } catch (_) {
      // Fall through to the ASCII filename.
    }
  }
  const m = cd.match(/filename="?([^"]+)"?/);
  return (m && m[1]) || fallback;
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const url = document.getElementById("url").value.trim();
  const full = document.getElementById("full").checked;
  if (!url) return;

  button.disabled = true;
  show("info", "Fetching and building your PDF…");

  try {
    const body = new URLSearchParams({ url, full: full ? "true" : "false" });
    const resp = await fetch("/api/convert", { method: "POST", body });

    if (!resp.ok) {
      const data = await resp.json().catch(() => ({}));
      show("error", "⚠️ " + (data.error || `Request failed (${resp.status}).`));
      return;
    }

    const blob = await resp.blob();
    const name = filenameFrom(resp, "xave.pdf");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();

    const warnings = resp.headers.get("X-Xave-Warnings");
    let msg = `✅ Downloaded ${name}.`;
    if (warnings) msg += ` Note: ${warnings}`;
    show(warnings ? "info" : "ok", msg);
  } catch (err) {
    show("error", "⚠️ " + err.message);
  } finally {
    button.disabled = false;
  }
});
