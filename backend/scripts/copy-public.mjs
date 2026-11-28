import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Google Play (TAREA 4): fastifyStatic sirve dist/public cuando el backend
 * arranca desde dist/index.js. tsc no copia recursos no-TS, así que este paso
 * hace que privacy.html, terms.html y account-deletion.html estén disponibles
 * en la URL pública tras `npm run build`.
 */
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(root, 'public');
const target = resolve(root, 'dist', 'public');

if (!existsSync(source)) {
  throw new Error(`No existe el directorio público: ${source}`);
}

mkdirSync(dirname(target), { recursive: true });
cpSync(source, target, { recursive: true, force: true });
console.log(`Recursos públicos copiados: ${source} -> ${target}`);
