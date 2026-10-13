// EventBridge 매일 03:00 KST → CloudWatch Logs 를 S3 log-archive 로 내보내기 (CreateExportTask) · 90일 후 Glacier
// TODO VoC-100: CloudWatch Logs 그룹을 순회하며 CreateExportTask (한 번에 1개씩, 완료 대기) → s3://${BUCKET}/{group}/{yyyy-MM-dd}/
export const handler = async (event) => {
  console.log('log-archive triggered', JSON.stringify(event));
  return { exported: 0 };
};
