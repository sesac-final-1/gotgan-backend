// 시연 데이터 시나리오 — 서비스별 시드(R__demo_seed.sql)가 같은 번호 · 금액 · 날짜를 가리키게 하는 단일 원본. 담당 구지훈
// 원본: 프론트 목업(gotgan-frontend src/mocks/fixtures) — 화면 모습은 살리고 DDL · 서비스 간 정합성에 맞춘다
// 날짜는 시드 실행일 기준 상대값(daysAgo) — 클러스터가 매일 새 DB 라 "지난주"가 항상 실제 지난주

// 상위 카테고리 — product 시드 · settlement 수수료 시드와 같은 번호 (product.md 확정표)
export const CATEGORY = { food: 1, living: 2, fashion: 3, 'shoes-bags': 4, beauty: 5, digital: 6 };

export const GRADE = { 새싹: 'SPROUT', 파워: 'POWER', 빅파워: 'BIG_POWER' };

// 스토어 번호 규칙: 목업 sN → store_id 10 + N. 단 시연 판매자 로그인 스토어 1 · 2 는 Keycloak 시드 · settlement 시드와 번호가 같아야 해서
// (user 2 · 3, 카테고리 식품 · 리빙 그대로) 같은 카테고리의 목업 스토어 s6 · s4 를 1 · 2 로 쓴다 (10/21 답변 J2 — 이름 · slug 만 바뀜)
// 대표자(판매자) 회원: 1 · 2 는 user 2 · 3(Keycloak 로그인 가능), 나머지는 user 200 + N (조회용, 로그인 불가 — 답변 K1)
export const STORES = [
  { storeId: 1, userId: 2, mock: 's6', name: '산골농부 박씨네', slug: 'park-farm', cat: 'food', owner: '박영철', bizNo: '000-00-00006', phone: '064-0000-0006',
    email: 'wlsgksvheh+seller1@gmail.com', intro: '제주 서귀포에서 3대째 귤 농사를 짓습니다. 따는 날 바로 보내드려요.', since: 980, grade: '빅파워', color: '#d0701f',
    courier: '우체국택배', shipFee: 3000, freeOver: 30000, dispatch: '수확일 기준 당일 발송', status: 'ACTIVE' },
  { storeId: 2, userId: 3, mock: 's4', name: '토담공방', slug: 'todam', cat: 'living', owner: '이수아', bizNo: '000-00-00004', phone: '063-0000-0004',
    email: 'wlsgksvheh+seller2@gmail.com', intro: '전주 한옥마을 옆 작은 도자기 공방. 손으로 빚어 하나하나 모양이 다릅니다.', since: 455, grade: '파워', color: '#8c6d4f',
    courier: 'CJ대한통운', shipFee: 3500, freeOver: 50000, dispatch: '주문 제작 3~5영업일', status: 'ACTIVE' },
  { storeId: 11, userId: 201, mock: 's1', name: '오롯이 리넨', slug: 'orot-linen', cat: 'fashion', owner: '한지우', bizNo: '000-00-00001', phone: '02-0000-0001',
    email: 'hello@orotlinen.example.com', intro: '여름이 아니어도 입고 싶은 리넨. 성수동 작업실에서 직접 패턴을 뜨고 봉제합니다.', since: 612, grade: '파워', color: '#5b6b4a',
    courier: 'CJ대한통운', shipFee: 3000, freeOver: 50000, dispatch: '평일 14시 이전 결제 시 당일 출고', status: 'ACTIVE' },
  { storeId: 12, userId: 202, mock: 's2', name: '무드앤로우', slug: 'mood-and-row', cat: 'fashion', owner: '오세훈', bizNo: '000-00-00002', phone: '070-0000-0002',
    email: 'cs@moodrow.example.com', intro: '매일 입는 옷을 더 오래. 두꺼운 원단, 단정한 핏의 베이직 웨어.', since: 890, grade: '빅파워', color: '#2b2b2b',
    courier: '롯데택배', shipFee: 0, freeOver: null, dispatch: '결제 후 1~2영업일 출고', status: 'ACTIVE' },
  { storeId: 13, userId: 203, mock: 's3', name: '걸음상회', slug: 'georum', cat: 'shoes-bags', owner: '최민재', bizNo: '000-00-00003', phone: '051-0000-0003',
    email: 'georum@georum.example.com', intro: '부산 신발 공장 3대째. 오래 걸어도 편한 가죽 신발과 가방.', since: 1320, grade: '빅파워', color: '#7a4b2a',
    courier: '우체국택배', shipFee: 3000, freeOver: 70000, dispatch: '평일 13시 이전 결제 시 당일 출고', status: 'ACTIVE' },
  { storeId: 15, userId: 205, mock: 's5', name: '볕드는집', slug: 'byeot-house', cat: 'living', owner: '정다은', bizNo: '000-00-00005', phone: '031-0000-0005',
    email: 'house@byeot.example.com', intro: '햇볕 잘 드는 방을 위한 조명과 패브릭.', since: 301, grade: '새싹', color: '#c08a2e',
    courier: '한진택배', shipFee: 3000, freeOver: 40000, dispatch: '평일 13시 이전 결제 시 당일 출고',
    status: 'SUSPENDED', statusReason: '반품 미처리 3건 — 처리 완료 후 해제', suspendedDaysAgo: 31 },
  { storeId: 17, userId: 207, mock: 's7', name: '로스터리 하루', slug: 'haru-roastery', cat: 'food', owner: '윤하루', bizNo: '000-00-00007', phone: '02-0000-0007',
    email: 'roast@haru.example.com', intro: '월·목 로스팅, 화·금 발송. 볶은 지 3일 이내 원두만 보냅니다.', since: 540, grade: '파워', color: '#3d2b22',
    courier: 'CJ대한통운', shipFee: 3000, freeOver: 30000, dispatch: '화 · 금 발송', status: 'ACTIVE' },
  { storeId: 18, userId: 208, mock: 's8', name: '피부정원', slug: 'skin-garden', cat: 'beauty', owner: '강예린', bizNo: '000-00-00008', phone: '02-0000-0008',
    email: 'garden@skingarden.example.com', intro: '성분은 줄이고 효과는 그대로. 민감 피부를 위한 저자극 스킨케어.', since: 720, grade: '파워', color: '#6f8f7a',
    courier: '롯데택배', shipFee: 3000, freeOver: 30000, dispatch: '평일 15시 이전 결제 시 당일 출고', status: 'ACTIVE' },
  { storeId: 19, userId: 209, mock: 's9', name: '데스크테리어랩', slug: 'deskterior-lab', cat: 'digital', owner: '서준호', bizNo: '000-00-00009', phone: '070-0000-0009',
    email: 'lab@deskterior.example.com', intro: '하루 8시간 앉는 책상을 위한 도구들.', since: 210, grade: '새싹', color: '#34495e',
    courier: 'CJ대한통운', shipFee: 3000, freeOver: 50000, dispatch: '평일 14시 이전 결제 시 당일 출고', status: 'ACTIVE' },
];

// 입점 신청서의 대표 상품(2~10개, {name, subCategory, price}) — 심사 화면 표시용. 카테고리별 하위 이름은 product 시드와 같게
export const REPRESENTATIVE = {
  food: [{ name: '제철 과일 선물 세트', subCategory: '과일', price: 32000 }, { name: '수제 과일청 2병', subCategory: '꿀·잼', price: 18000 }],
  living: [{ name: '손빚음 머그', subCategory: '식기', price: 28000 }, { name: '리넨 패브릭 조명', subCategory: '조명', price: 54000 }],
  fashion: [{ name: '리넨 셔츠', subCategory: '셔츠', price: 69000 }, { name: '와이드 팬츠', subCategory: '팬츠', price: 59000 }],
  'shoes-bags': [{ name: '가죽 로퍼', subCategory: '로퍼', price: 129000 }, { name: '데일리 토트백', subCategory: '토트백', price: 89000 }],
  beauty: [{ name: '진정 토너 200ml', subCategory: '스킨케어', price: 24000 }, { name: '수분크림 50ml', subCategory: '스킨케어', price: 32000 }],
  digital: [{ name: '무접점 키보드', subCategory: '키보드', price: 219000 }, { name: '원목 모니터 받침대', subCategory: '데스크', price: 89000 }],
};

// 회원 — 1 관리자 · 2 · 3 판매자 · 4~6 구매자는 Keycloak 시드 계정(로그인 가능, 이메일 고정). 4~6 은 목업 u1~u3 프로필로
//   목업 u4~u14 → user 100 + N (조회용, 로그인 불가 — 답변 K1). 휴면 없음(K2), u11 은 이용정지(관리자 회원 화면 시연)
//   로그인 계정의 포인트 · 누적 구매확정 금액은 아래 주문에서 계산, 조회용 회원은 목업 값(포인트는 이력 1건으로 잔액과 맞춤)
export const LOGIN_BUYERS = [
  { userId: 4, mock: 'u1', email: 'wlsgksvheh+buyer1@gmail.com', name: '김서연', nick: '서연이네', phone: '010-0000-0010', grade: 'GOLD', joined: 820, login: 0 },
  { userId: 5, mock: 'u2', email: 'wlsgksvheh+buyer2@gmail.com', name: '이도현', nick: '도현', phone: '010-0000-0011', grade: 'GOLD', joined: 1400, login: 0 },
  { userId: 6, mock: 'u3', email: 'wlsgksvheh+buyer3@gmail.com', name: '박지민', nick: '지민찡', phone: '010-0000-0012', grade: 'SILVER', joined: 400, login: 2 },
];
export const VIEW_BUYERS = [
  { userId: 104, name: '최유나', nick: '유나', email: 'yuna.choi@example.com', phone: '010-0000-0013', grade: 'NORMAL', joined: 60, spent: 98000, point: 1200, login: 5 },
  { userId: 105, name: '정하준', nick: '하준아빠', email: 'hajun.dad@example.com', phone: '010-0000-0014', grade: 'GOLD', joined: 980, spent: 1210000, point: 8800, login: 1 },
  { userId: 106, name: '윤채원', nick: '채원', email: 'chaewon.y@example.com', phone: '010-0000-0015', grade: 'SILVER', joined: 300, spent: 402000, point: 2050, login: 3 },
  { userId: 107, name: '강민호', nick: '민호', email: 'minho.kang@example.com', phone: '010-0000-0016', grade: 'NORMAL', joined: 30, spent: 32000, point: 500, login: 12 },
  { userId: 108, name: '조은비', nick: '은비', email: 'eunbi.cho@example.com', phone: '010-0000-0017', grade: 'GOLD', joined: 1650, spent: 7710000, point: 61000, login: 0 },
  { userId: 109, name: '임재현', nick: 'jh_lim', email: 'jaehyun.lim@example.com', phone: '010-0000-0018', grade: 'NORMAL', joined: 720, spent: 140000, point: 0, login: 390 },
  { userId: 110, name: '한소희', nick: '소소', email: 'sohee.han@example.com', phone: '010-0000-0019', grade: 'SILVER', joined: 250, spent: 388000, point: 4100, login: 1 },
  { userId: 111, name: '오준서', nick: '블랙컨슈머아님', email: 'junseo.oh@example.com', phone: '010-0000-0020', grade: 'NORMAL', joined: 90, spent: 210000, point: 0, login: 4,
    suspended: { reason: '허위 리뷰 반복 작성', daysAgo: 4, days: 14 } },
  { userId: 112, name: '서다인', nick: '다인', email: 'dain.seo@example.com', phone: '010-0000-0021', grade: 'GOLD', joined: 1100, spent: 1530000, point: 9900, login: 0 },
  { userId: 113, name: '문태양', nick: '태양', email: 'taeyang.m@example.com', phone: '010-0000-0022', grade: 'NORMAL', joined: 8, spent: 0, point: 3000, login: 0 },
  { userId: 114, name: '배수빈', nick: '수빈', email: 'subin.bae@example.com', phone: '010-0000-0023', grade: 'SILVER', joined: 510, spent: 610000, point: 5200, login: 6 },
];

// 주문에 쓰는 상품 — product_id = 스토어 id × 100 + 순번, 옵션 = 상품 × 100 + 1 (상품 시드는 product 엔티티 이후 같은 번호로).
//   category = 하위 카테고리(product 시드), 판매수수료는 상위(스토어 카테고리) 기준
export const PRODUCTS = {
  101: { storeId: 1, name: '제주 노지 감귤 5kg', price: 29000, category: 101 },
  102: { storeId: 1, name: '한라봉 3kg', price: 39000, category: 101 },
  201: { storeId: 2, name: '손빚음 머그', price: 28000, category: 201 },
  202: { storeId: 2, name: '백자 달항아리 화병', price: 45000, category: 202 },
  1101: { storeId: 11, name: '리넨 오버핏 셔츠', price: 69000, category: 301 },
  1501: { storeId: 15, name: '리넨 패브릭 스탠드 조명', price: 54000, category: 203 },
  1701: { storeId: 17, name: '에티오피아 예가체프 원두 200g', price: 18000, category: 104 },
  1801: { storeId: 18, name: '시카 9 진정 토너 200ml', price: 24000, category: 501 },
};

// 주문 시나리오 — 주문 1건 = 스토어 1곳 · 상품 1개(정합성 확인을 단순하게). 포인트 사용 없음
//   week: 구매확정된 주(1 = 지난주 → 정산 지급예정, 2 = 지지난주 → 지급 완료), day: 그 주 월요일 + day 에 구매확정
//   recent: 진행 중 상태 — 판매자 주문 화면 시연(발주확인 · 송장 · 배송완료 버튼)
export const ORDERS = [
  { buyer: 4, product: 101, qty: 2, week: 2, day: 1 },
  { buyer: 5, product: 201, qty: 2, week: 2, day: 2 },
  { buyer: 6, product: 1101, qty: 1, week: 2, day: 3 },
  { buyer: 5, product: 102, qty: 1, week: 1, day: 0 },
  { buyer: 6, product: 101, qty: 1, week: 1, day: 2 },
  { buyer: 4, product: 202, qty: 1, week: 1, day: 3 },
  { buyer: 4, product: 1701, qty: 2, week: 1, day: 4 },
  { buyer: 6, product: 1501, qty: 1, week: 1, day: 1 },   // 정지 스토어 — 정산서 자동 보류
  { buyer: 4, product: 101, qty: 1, recent: 'DELIVERED' },
  { buyer: 5, product: 201, qty: 1, recent: 'SHIPPING' },
  { buyer: 6, product: 102, qty: 1, recent: 'PREPARING' },
  { buyer: 4, product: 202, qty: 1, recent: 'PAYMENT_DONE' },
  { buyer: 5, product: 101, qty: 3, recent: 'PAYMENT_DONE' },
  { buyer: 6, product: 1801, qty: 2, recent: 'PAYMENT_DONE' },
];

export const COMMISSION = { 1: 7, 2: 9, 3: 11, 4: 11, 5: 10, 6: 6 }; // settlement category_commission 시드와 같은 율(%)
export const PAYMENT_FEE_RATE = 2.2;
export const EARN_RATE = 5;
