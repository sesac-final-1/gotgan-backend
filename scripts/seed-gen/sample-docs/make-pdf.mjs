// 입점 신청 시드 서류 샘플(시연용 가짜 서류) — 한 쪽짜리 PDF. 한글 글꼴을 넣지 않으려고 문구는 영문
//   node sample-docs/make-pdf.mjs → sample-docs/{business-reg,ecommerce-reg,bank-copy}.pdf
//   올리기: aws s3 cp sample-docs/ s3://sesac-gojigi-seller-docs-4380/applications/seed/ --recursive --exclude "*" --include "*.pdf"
import fs from 'fs';

const DOCS = {
  'business-reg': 'Business Registration Certificate',
  'ecommerce-reg': 'E-commerce Business Report Certificate',
  'bank-copy': 'Bank Account Copy',
};

function pdf(title) {
  const text = [
    'BT /F1 28 Tf 72 720 Td (SAMPLE - GOTGAN DEMO) Tj ET',
    `BT /F1 18 Tf 72 680 Td (${title}) Tj ET`,
    'BT /F1 12 Tf 72 640 Td (This is a fake document for the team project demo.) Tj ET',
    'BT /F1 12 Tf 72 620 Td (No real business or account information.) Tj ET',
    '4 w 60 560 m 552 560 l S',
  ].join('\n');
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${Buffer.byteLength(text)} >>\nstream\n${text}\nendstream`,
  ];
  let out = '%PDF-1.4\n';
  const offsets = objs.map((o, i) => { const at = Buffer.byteLength(out); out += `${i + 1} 0 obj\n${o}\nendobj\n`; return at; });
  const xref = Buffer.byteLength(out);
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return out;
}

for (const [name, title] of Object.entries(DOCS)) fs.writeFileSync(new URL(`./${name}.pdf`, import.meta.url), pdf(title));
console.log('ok', Object.keys(DOCS).length);
