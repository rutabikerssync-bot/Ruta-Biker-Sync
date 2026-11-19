import { describe, expect, it } from 'vitest';
import { mapRideRosterRows, normalizeRoleInConvoy } from '../src/lib/rideRoster.js';

describe('Roster enriquecido de rodada', () => {
  it('normaliza roles Operativos sin inventar datos', () => {
    expect(normalizeRoleInConvoy('capitan_rodada')).toBe('Punta');
    expect(normalizeRoleInConvoy('scout')).toBe('Scout');
    expect(normalizeRoleInConvoy('sweep')).toBe('Sweep');
    expect(normalizeRoleInConvoy('participante')).toBe('Rider');
    expect(normalizeRoleInConvoy('road_marshal')).toBe('Rider');
    expect(normalizeRoleInConvoy(null)).toBe('Rider');
  });

  it('proyecta nombre, avatar y rol reales y usa fallbacks honestos', () => {
    const rows = [
      {
        user_id: 'u-1',
        group_id: 'g-1',
        status_kind: 'full',
        ride_role: 'capitan_rodada',
        display_name: 'Juan Martínez',
        photo_url: 'https://example.test/juan.webp',
        map_color: '#FFB300',
        bike_model: 'Yamaha XT1200'
      },
      {
        user_id: 'u-2',
        group_id: 'g-1',
        status_kind: 'full',
        ride_role: 'scout',
        display_name: null,
        photo_url: null,
        map_color: null,
        bike_model: null
      }
    ];

    const roster = mapRideRosterRows(rows);

    expect(roster).toHaveLength(2);
    expect(roster[0]).toMatchObject({
      userId: 'u-1',
      rideRole: 'capitan_rodada',
      roleInConvoy: 'Punta',
      displayName: 'Juan Martínez',
      avatarUrl: 'https://example.test/juan.webp',
      bikeModel: 'Yamaha XT1200'
    });
    expect(roster[1]).toMatchObject({
      userId: 'u-2',
      rideRole: 'scout',
      roleInConvoy: 'Scout',
      displayName: 'Desconocido',
      avatarUrl: null,
      bikeModel: null
    });
  });
});
