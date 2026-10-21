"""Generate a self-contained DOM regression test page; open it in any browser."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/assets/capture.js').read_text()
tests = (root / 'tests/capture.test.js').read_text()
out = root / 'app/build/capture-tests.html'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("<!doctype html><meta charset='utf-8'><title>Xave capture tests</title>"
               "<h1>Xave capture tests</h1><h2 id='summary'>Running…</h2><pre id='results'></pre>"
               "<script>window.captureSource=" + json.dumps(source).replace('</', '<\\/') + ";\n"
               + tests.replace('</script', '<\\/script') + '</script>')
print(out)
