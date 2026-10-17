#!/usr/bin/env node
// CI gate: fail on any high/critical advisory in client production deps that is not explicitly
// accepted. Every accepted ID must have a row (with its risk assessment) in client/SECURITY-AUDIT.md.
// Usage (from client/): npm audit --omit=dev --json | node ../tools/npm-audit-gate.cjs
const ACCEPTED = new Map([
  ['GHSA-vfj7-8cjw-p6xm', 'braces via @expo/cli/Metro - build-time only, no fixed release'],
  ['GHSA-86w9-cpqp-85rv', 'node-forge via @expo/code-signing-certificates - code signing not used'],
]);

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  const report = JSON.parse(input || '{}');
  const advisories = Object.values(report.vulnerabilities ?? {})
    .flatMap((pkg) => pkg.via)
    .filter((via) => typeof via === 'object' && ['high', 'critical'].includes(via.severity));
  const unaccepted = new Map();
  for (const a of advisories) {
    const id = String(a.url ?? '').split('/').pop();
    if (!ACCEPTED.has(id)) unaccepted.set(id, `${a.severity} ${a.name}: ${a.title} (${a.url})`);
  }
  if (unaccepted.size) {
    console.error('Unaccepted high/critical advisories in production dependencies:');
    for (const line of unaccepted.values()) console.error('  - ' + line);
    console.error('Fix them, or document and accept them in client/SECURITY-AUDIT.md and tools/npm-audit-gate.cjs.');
    process.exit(1);
  }
  console.log(`npm audit gate: OK (${advisories.length} high/critical findings, all accepted)`);
});
