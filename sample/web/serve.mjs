// Zero-dependency static server for this sample. Serves the whole repo root
// so relative imports into ../../web/dist resolve, and defaults to this
// sample's index.html. Usage: `node serve.mjs [port]`.
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = normalize(join(fileURLToPath(new URL('.', import.meta.url)), '..', '..'));
const port = Number(process.argv[2] ?? 5173);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
};

createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? '/', 'http://localhost');
    let path = decodeURIComponent(url.pathname);
    if (path === '/') path = '/sample/web/index.html';
    const resolved = normalize(join(repoRoot, path));
    if (!resolved.startsWith(repoRoot)) {
      res.writeHead(403).end('Forbidden');
      return;
    }
    const info = await stat(resolved);
    const filePath = info.isDirectory() ? join(resolved, 'index.html') : resolved;
    const body = await readFile(filePath);
    res.writeHead(200, { 'Content-Type': MIME[extname(filePath)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404).end('Not found');
  }
}).listen(port, () => {
  console.log(`AegisScan web sample: http://localhost:${port}/sample/web/index.html`);
});
