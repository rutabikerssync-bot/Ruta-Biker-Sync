import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * FASE 4 (P0-GP3): verificación JVM/Node de los recursos legales públicos.
 * No requiere PostgreSQL: solo comprueba que el sitio enlaza archivos reales y
 * que el contenido no afirma hechos todavía no verificados en Play Console.
 */
const publicDir = fileURLToPath(new URL('../public/', import.meta.url));
const checklistPath = fileURLToPath(new URL('../../CHECKLIST.md', import.meta.url));
const packagePath = fileURLToPath(new URL('../package.json', import.meta.url));
const copyScriptPath = fileURLToPath(new URL('../scripts/copy-public.mjs', import.meta.url));

function readPage(name: string): string {
  return readFileSync(`${publicDir}${name}`, 'utf8');
}

describe('recursos legales públicos de Fase 4', () => {
  it('publica privacidad, términos y borrado con enlaces entre ellos', () => {
    const privacy = readPage('privacy.html');
    const terms = readPage('terms.html');
    const deletion = readPage('account-deletion.html');

    for (const [page, name] of [[privacy, 'privacy.html'], [terms, 'terms.html'], [deletion, 'account-deletion.html']] as const) {
      expect(page).toContain('<html lang="es">');
      expect(page).toContain('soporte@rutabikersync.com');
      expect(page).not.toContain('rbs-pending.invalid');
      expect(page).not.toContain('TODO_EXTERNAL');
      expect(name.length).toBeGreaterThan(0);
    }
    expect(privacy).toContain('href="./terms.html"');
    expect(privacy).toContain('href="./account-deletion.html"');
    expect(terms).toContain('href="./privacy.html"');
    expect(deletion).toContain('href="./privacy.html"');
  });

  it('la portada sustituye los enlaces legales vacíos por recursos reales', () => {
    const index = readPage('index.html');
    expect(index).toContain('href="/privacy.html"');
    expect(index).toContain('href="/terms.html"');
    expect(index).toContain('href="/privacy.html#derechos"');
    expect(index).toContain('href="/account-deletion.html"');
    expect(index).toContain('href="mailto:soporte@rutabikersync.com"');
    expect(index).not.toContain('<a href="#">Privacidad');
    expect(index).not.toContain('<a href="#">Términos');
    expect(index).not.toContain('<a href="#">Cumplimiento');
    expect(index).not.toContain('<a href="#">Soporte');
  });

  it('describe el tratamiento real sin inventar historial GPS ni borrado automático web', () => {
    const privacy = readPage('privacy.html');
    const deletion = readPage('account-deletion.html');

    expect(privacy).toContain('no guarda un historial de GPS');
    expect(privacy).toContain('no declara <code>ACCESS_BACKGROUND_LOCATION</code>');
    expect(privacy).toContain('Ed25519');
    expect(privacy).toContain('id="derechos"');
    expect(deletion).toContain('DELETE /v1/users/me');
    expect(deletion).toContain('No existe un formulario web con borrado automático');
    expect(deletion).toContain('Exportar mis Datos');
  });

  it('el build copia los recursos legales a dist/public para que fastifyStatic los sirva', () => {
    const pkg = JSON.parse(readFileSync(packagePath, 'utf8')) as { scripts?: Record<string, string> };
    expect(pkg.scripts?.build).toContain('node scripts/copy-public.mjs');
    const copyScript = readFileSync(copyScriptPath, 'utf8');
    expect(copyScript).toContain("resolve(root, 'public')");
    expect(copyScript).toContain("resolve(root, 'dist', 'public')");
    expect(copyScript).toContain('cpSync');
  });

  it('el CHECKLIST deja pendiente lo que solo se puede completar en Play Console', () => {
    const checklist = readFileSync(checklistPath, 'utf8');
    const privacyLine = checklist.split('\n').find((line) => line.includes('Política de privacidad y términos')) ?? '';
    const dataSafetyLine = checklist.split('\n').find((line) => line.includes('Declaración de datos de Google Play')) ?? '';
    const storeListingLine = checklist.split('\n').find((line) => line.includes('Ficha de tienda, capturas y clasificación')) ?? '';

    expect(privacyLine).not.toContain('- [x]');
    expect(dataSafetyLine).not.toContain('- [x]');
    expect(storeListingLine).not.toContain('- [x]');
    expect(checklist).toContain('docs/audit/play_console_checklist_20261127.md');
  });
});
