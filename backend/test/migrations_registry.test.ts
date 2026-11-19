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
});
