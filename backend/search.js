import fs from 'fs';
const content = fs.readFileSync('backend/src/app.ts', 'utf8');
const lines = content.split('\n');
lines.forEach((l, i) => {
  if (l.includes("'/v1/groups'") || l.includes('"/v1/groups"')) {
    console.log((i+1) + ': ' + l.trim());
  }
});
