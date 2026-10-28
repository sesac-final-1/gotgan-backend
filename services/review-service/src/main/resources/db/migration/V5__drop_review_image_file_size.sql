-- reviewdb V5 — 리뷰 사진 크기는 저장하지 않는다 (10/28 결정: product 와 같이 presign 키 형식만 검사, S3 HeadObject 확인 없음)
-- 크기 제한(10MB)은 presigned PUT 서명의 Content-Length 로 S3 가 막는다. review IRSA 는 s3:PutObject(reviews/*) 뿐이라 크기를 읽을 수 없음
ALTER TABLE review_image DROP COLUMN file_size;
