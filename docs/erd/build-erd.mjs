// 서비스별 Flyway 마이그레이션(db/migration/V*.sql)을 모아 ERD Cloud 가져오기용 SQL 1개를 만든다.
// 사용: node docs/erd/build-erd.mjs  → docs/erd/gotgan-erdcloud.sql
// ERD Cloud 가져오기는 MySQL 문법 일부만 읽으므로 ERD Cloud 내보내기와 같은 모양으로 단순화한다:
//   컬럼(타입 · NULL · 단순 DEFAULT · COMMENT)만 남기고, PK · FK 는 ALTER TABLE 로 뒤에 붙인다.
//   AUTO_INCREMENT · ON UPDATE · UNIQUE · INDEX · ENGINE 옵션은 뺀다 (원본은 각 서비스 Flyway 파일).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.join(ROOT, 'docs/erd/gotgan-erdcloud.sql');

// 서비스 → DB · 담당 (컨벤션 3장 서비스별 스키마)
const SERVICES = [
  ['user', 'userdb', '이경석'],
  ['store', 'storedb', '박지연'],
  ['product', 'productdb', '박지연'],
  ['order', 'orderdb', '이경석'],
  ['payment', 'paymentdb', '구지훈'],
  ['review', 'reviewdb', '이경석'],
  ['settlement', 'settlementdb', '구지훈'],
];
const SKIP_TABLES = new Set(['shedlock']); // 스케줄러 잠금 — 도메인 관계가 없어 ERD 에서 제외

const version = (f) => Number(/^V(\d+)__/.exec(f)?.[1] ?? 0);
const stripComment = (line) => line.replace(/\s--\s.*$/, '').trim();

function parseTable(name, body, tail) {
  const table = { name, columns: [], pk: null, fks: [], comment: /COMMENT\s*=\s*'([^']*)'/.exec(tail)?.[1] ?? '' };
  for (const raw of body.split('\n')) {
    const line = stripComment(raw).replace(/,$/, '');
    if (!line) continue;
    let m;
    if ((m = /^CONSTRAINT\s+\S+\s+PRIMARY KEY\s*\(([^)]+)\)/i.exec(line))) {
      table.pk = m[1].split(',').map((c) => c.trim());
    } else if ((m = /^CONSTRAINT\s+(\S+)\s+FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES\s+(\w+)\s*\(([^)]+)\)/i.exec(line))) {
      table.fks.push({ name: m[1], cols: m[2].trim(), refTable: m[3], refCols: m[4].trim() });
    } else if (/^(CONSTRAINT|INDEX|KEY|UNIQUE|PRIMARY)\b/i.test(line)) {
      continue; // UNIQUE · INDEX — ERD 표시 대상 아님
    } else if ((m = /^(\w+)\s+((?:INT|BIGINT|TINYINT|SMALLINT)\s+UNSIGNED|[A-Z]+(?:\(\d+(?:,\d+)?\))?)(.*)$/i.exec(line))) {
      const [, col, type, rest] = m;
      const notNull = /\bNOT NULL\b/i.test(rest);
      let def = /\bDEFAULT\s+('(?:[^']*)'|[\w.()]+)/i.exec(rest)?.[1] ?? null;
      const comment = /\bCOMMENT\s+'([^']*)'/i.exec(rest)?.[1] ?? null;
      table.columns.push({ col, type: type.toUpperCase(), notNull, def, comment });
    }
  }
  return table;
}

// 1) 서비스별 마이그레이션을 버전 순으로 읽어 CREATE TABLE 수집
const schemas = [];
for (const [svc, db, owner] of SERVICES) {
  const dir = path.join(ROOT, `services/${svc}-service/src/main/resources/db/migration`);
  if (!fs.existsSync(dir)) continue;
  const files = fs.readdirSync(dir).filter((f) => /^V\d+__.*\.sql$/.test(f)).sort((a, b) => version(a) - version(b));
  const tables = [];
  for (const f of files) {
    const sql = fs.readFileSync(path.join(dir, f), 'utf8').replace(/\r\n/g, '\n');
    for (const m of sql.matchAll(/CREATE TABLE\s+(\w+)\s*\(\n([\s\S]*?)\n\)([^;]*);/g)) {
      if (!SKIP_TABLES.has(m[1])) tables.push(parseTable(m[1], m[2], m[3]));
    }
  }
  schemas.push({ svc, db, owner, files, tables });
}

// 2) 여러 DB 에 같은 이름이 있으면(processed_event 등) ERD 한 캔버스에서 겹치지 않게 {db}_ 접두어
const count = {};
for (const s of schemas) for (const t of s.tables) count[t.name] = (count[t.name] ?? 0) + 1;
const rename = (s, n) => (count[n] > 1 ? `${s.db}_${n}` : n);

// 3) ERD Cloud 내보내기와 같은 모양으로 출력
const q = (s) => s.replace(/'/g, "''");
const out = [];
out.push('-- 곳간(GOTGAN) 전체 ERD — ERD Cloud 가져오기용 (자동 생성: node docs/erd/build-erd.mjs, 직접 수정 금지)');
out.push(`-- 생성: ${new Date().toISOString().slice(0, 10)} · 원본: services/*-service/src/main/resources/db/migration (Flyway)`);
out.push('-- 서비스마다 DB 가 따로라 서비스 사이 참조(user_id · order_item_id 등 "논리 참조")는 FK 로 그리지 않는다 — 컬럼 COMMENT 참고');
out.push(`-- 제외: ${[...SKIP_TABLES].join(' · ')} (스케줄러 잠금). 여러 DB 에 있는 같은 이름 테이블은 {db}_ 접두어`);
out.push('');
const alters = [];
for (const s of schemas) {
  if (!s.tables.length) continue;
  out.push(`-- ============================================================ ${s.db} (${s.svc}-service · 담당 ${s.owner})`);
  out.push(`-- 원본: ${s.files.join(' · ')}`);
  out.push('');
  for (const t of s.tables) {
    const name = rename(s, t.name);
    out.push(`-- ${t.comment}`);
    out.push(`CREATE TABLE \`${name}\` (`);
    out.push(t.columns.map((c) => {
      const parts = [`\t\`${c.col}\``, c.type, c.notNull ? 'NOT NULL' : 'NULL'];
      if (c.def) parts.push(`DEFAULT ${c.def}`);
      if (c.comment) parts.push(`COMMENT '${q(c.comment)}'`);
      return parts.join('\t');
    }).join(',\n'));
    out.push(');');
    out.push('');
    if (t.pk) alters.push(`ALTER TABLE \`${name}\` ADD CONSTRAINT \`PK_${name.toUpperCase()}\` PRIMARY KEY (${t.pk.map((c) => `\`${c}\``).join(', ')});`);
    for (const fk of t.fks) {
      alters.push(`ALTER TABLE \`${name}\` ADD CONSTRAINT \`${fk.name}\` FOREIGN KEY (\`${fk.cols}\`) REFERENCES \`${rename(s, fk.refTable)}\` (\`${fk.refCols}\`);`);
    }
  }
}
const pks = alters.filter((a) => a.includes('PRIMARY KEY'));
const fks = alters.filter((a) => a.includes('FOREIGN KEY'));
out.push('-- ============================================================ PK', ...pks, '');
out.push('-- ============================================================ FK (같은 DB 안의 관계만)', ...fks, '');
fs.writeFileSync(OUT, out.join('\n'));
console.log(`${path.relative(ROOT, OUT)} — ${schemas.reduce((n, s) => n + s.tables.length, 0)} tables · ${fks.length} FK`);
