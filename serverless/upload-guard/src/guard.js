// S3 업로드 검사 — images · seller-docs 에 새 객체가 생기면(EventBridge "Object Created") 앞 16바이트로 실제 형식을 확인한다.
// 담당 jihoon-devstu · 스펙 SYS-24 (10/27 회의 추가 기능)
//
// presigned PUT 은 서버를 거치지 않고 브라우저가 S3 에 바로 올린다 — 확장자 · Content-Type 은 서명에 들어가지만 내용은 아무도 보지 않는다.
// 그래서 확장자만 .jpg 로 바꾼 실행 파일 · HTML 같은 위장 파일을 여기서 지운다:
//  ① 버킷 · 경로(prefix)마다 허용하는 확장자인지 (images: jpg · png · webp / seller-docs: pdf · jpg · png)
//  ② 파일 앞부분(매직 바이트)이 그 확장자의 실제 형식인지
//  둘 중 하나라도 아니면 객체를 지우고 알림 토픽(sesac-gojigi-alerts → 메일)에 알린다. 정상이면 아무것도 하지 않는다
// 지운 키를 서비스가 저장해 두었으면 화면에는 깨진 이미지로 보인다(서비스는 S3 존재를 따로 확인하지 않음 — product.md 13절)
import { S3Client, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { SNSClient, PublishCommand } from '@aws-sdk/client-sns';

/** 형식 → 파일 앞부분 검사 */
const SIGNATURES = {
  jpg: (b) => b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff,
  png: (b) => [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((v, i) => b[i] === v),
  webp: (b) => ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 12) === 'WEBP',
  pdf: (b) => ascii(b, 0, 5) === '%PDF-',
};
const ascii = (b, from, to) => String.fromCharCode(...b.slice(from, to));

/** 버킷 용도 → 경로별 허용 형식 (presign 발급 규칙과 같게 — product.md 17절 · store.md 2-1절) */
export const RULES = {
  images: { prefixes: ['products/', 'reviews/', 'stores/'], types: ['jpg', 'png', 'webp'] },
  'seller-docs': { prefixes: ['applications/'], types: ['pdf', 'jpg', 'png'] },
};

/** 확장자 → 형식 (jpeg 는 jpg) */
export function extensionOf(key) {
  const m = /\.([a-z0-9]+)$/i.exec(key);
  if (!m) return null;
  const ext = m[1].toLowerCase();
  return ext === 'jpeg' ? 'jpg' : ext;
}

/**
 * 검사 결과 — { ok: true } 또는 { ok: false, reason }. bytes = 파일 앞부분(Uint8Array)
 * rule 이 없으면(검사 대상이 아닌 버킷) 통과
 */
export function inspect(rule, key, bytes) {
  if (!rule) return { ok: true };
  if (!rule.prefixes.some((p) => key.startsWith(p))) return { ok: false, reason: '허용하지 않는 경로' };
  const type = extensionOf(key);
  if (!rule.types.includes(type)) return { ok: false, reason: `허용하지 않는 확장자(${type ?? '없음'})` };
  if (!bytes?.length) return { ok: false, reason: '빈 파일' };
  if (!SIGNATURES[type](bytes)) return { ok: false, reason: `내용이 ${type} 형식이 아님` };
  return { ok: true };
}

/** 버킷 이름 → 용도 (env.IMAGES_BUCKET · env.SELLER_DOCS_BUCKET) */
const ruleFor = (env, bucket) => (bucket === env.IMAGES_BUCKET ? RULES.images : bucket === env.SELLER_DOCS_BUCKET ? RULES['seller-docs'] : null);

export function createHandler({ s3, sns, env, log = console }) {
  return async (event) => {
    const bucket = event.detail?.bucket?.name;
    const key = event.detail?.object?.key;
    const rule = ruleFor(env, bucket);
    if (!rule || !key) {
      log.warn(JSON.stringify({ msg: '검사 대상이 아닌 이벤트 — 무시', bucket, key }));
      return { checked: false };
    }

    let bytes;
    try {
      const res = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: key, Range: 'bytes=0-15' }));
      bytes = await res.Body.transformToByteArray();
    } catch (e) {
      if (e?.name === 'NoSuchKey') {  // 이미 지워진 객체(같은 이벤트 재전달 등)
        log.info(JSON.stringify({ msg: '이미 없는 객체 — 건너뜀', bucket, key }));
        return { checked: false };
      }
      throw e;                         // 그 밖의 실패는 Lambda 비동기 재시도(2회)
    }

    const result = inspect(rule, key, bytes);
    if (result.ok) return { checked: true, deleted: false };

    await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
    log.warn(JSON.stringify({ msg: '업로드 파일 삭제 — 형식 불일치', bucket, key, reason: result.reason }));
    await sns.send(new PublishCommand({
      TopicArn: env.ALERT_TOPIC_ARN,
      Subject: '[곳간] 업로드 파일 삭제 — 형식 불일치',
      Message: `버킷: ${bucket}\n키: ${key}\n이유: ${result.reason}\n\n업로드한 쪽(화면 · presign 발급 기록)에서 같은 키를 쓰는 데이터가 있으면 깨진 파일로 보입니다.`,
    }));
    return { checked: true, deleted: true, reason: result.reason };
  };
}

// 실제 Lambda 진입점 — 클라이언트는 콜드 스타트 때 한 번만 만든다
let realHandler;
export const handler = async (event) => {
  realHandler ??= createHandler({
    s3: new S3Client({}),
    sns: new SNSClient({}),
    env: { IMAGES_BUCKET: process.env.IMAGES_BUCKET, SELLER_DOCS_BUCKET: process.env.SELLER_DOCS_BUCKET, ALERT_TOPIC_ARN: process.env.ALERT_TOPIC_ARN },
  });
  return realHandler(event);
};
