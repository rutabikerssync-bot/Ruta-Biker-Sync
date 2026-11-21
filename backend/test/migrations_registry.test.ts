import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { readInitMigrationSql } from '../src/db/pool.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const migrationDir = path.resolve(here, '../src/db/migrations');

function migrationSql(file: string): string {
  return fs.readFileSync(path.join(migrationDir, file), 'utf8').trim();
}

describe('Registro de migraciones de arranque', () => {
  it('incluye 0015, 0016 y 0017 después de 0014', () => {
    const sql = readInitMigrationSql();
    const markers = [
      migrationSql('0014_profile_enrichment.sql'),
      migrationSql('0015_add_mf_tier.sql'),
      migrationSql('0016_club_profile.sql'),
      migrationSql('0017_club_routes_and_profile_version.sql')
    ];

    for (const marker of markers) {
      expect(sql).toContain(marker);
    }

    const positions = markers.map((marker) => sql.indexOf(marker));
    expect(positions[0]).toBeGreaterThanOrEqual(0);
    expect(positions[0]).toBeLessThan(positions[1]);
    expect(positions[1]).toBeLessThan(positions[2]);
    expect(positions[2]).toBeLessThan(positions[3]);
  });

  it('registra 0019 para permitir invitaciones abiertas compartidas por QR', () => {
    const sql = readInitMigrationSql();
    expect(sql).toContain(migrationSql('0019_open_qr_invitations.sql'));
    expect(sql).toContain('ALTER COLUMN invited_user DROP NOT NULL');
  });

  it('registra 0022 para persistir la clave pública SOS publicada por el móvil', () => {
    const sql = readInitMigrationSql();
    expect(sql).toContain(migrationSql('0022_sos_public_keys.sql'));
    expect(sql).toContain('sos_public_key BYTEA');
  });
});
