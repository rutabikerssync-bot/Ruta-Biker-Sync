import { execSync } from 'child_process';

try {
  const output = execSync('netstat -ano', { encoding: 'utf8' });
  const lines = output.split('\n');
  const pids = new Set();
  for (const line of lines) {
    if (line.includes(':3000 ') && line.includes('LISTENING')) {
      const parts = line.trim().split(/\s+/);
      const pid = parts[parts.length - 1];
      if (pid && !isNaN(pid)) {
        pids.add(pid);
      }
    }
  }
  console.log('PIDs listening on 3000:', Array.from(pids));
  for (const pid of pids) {
    try {
      execSync(`taskkill /F /PID ${pid}`);
      console.log(`Killed PID ${pid}`);
    } catch (e) {
      console.error(`Failed to kill ${pid}:`, e.message);
    }
  }
} catch (e) {
  console.error('Error:', e.message);
}
