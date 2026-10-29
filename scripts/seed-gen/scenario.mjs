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
// 입점 심사 시연용 신청 (10/29) — 관리자가 승인 · 반려 · 보완요청을 눌러 볼 수 있게. 신청자는 Keycloak 에 있는 구매자(승인되면 user 가 SELLER 를 넣음)
//   buyer1(4)은 메인 시연 구매자라 쓰지 않는다. 서류(application_document)는 S3 객체가 없어 없음 → 화면에 "미제출"
export const APPLICATIONS = [
  { applicationId: 101, userId: 6, status: 'PENDING', cat: 'beauty', name: '지민이네 수제비누', slug: 'jimin-soap', owner: '박지민', bizNo: '000-00-00101',
    phone: '010-0000-0012', email: 'wlsgksvheh+buyer3@gmail.com', intro: '천연 오일로 저온 숙성한 수제 비누를 만듭니다.', submittedDaysAgo: 1 },
  { applicationId: 102, userId: 5, status: 'SUPPLEMENT', cat: 'living', name: '도현 목공소', slug: 'dohyun-wood', owner: '이도현', bizNo: '000-00-00102',
    phone: '010-0000-0011', email: 'wlsgksvheh+buyer2@gmail.com', intro: '원목 도마와 트레이를 하나씩 깎아 만듭니다.', submittedDaysAgo: 4,
    supplement: { daysAgo: 2, reason: '통신판매업 신고증 사본이 흐려 확인이 어렵습니다. 다시 올려 주세요.' } },
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

// 카테고리 트리 — productdb 시드 그대로 (product.md "시드 카테고리 ID" 10/20 확정). 하위 ID = 상위 × 100 + 순번, 정렬 = 순번
export const CATEGORY_TREE = [
  { id: 1, name: '식품', en: 'Food', sort: 4, subs: ['과일', '꿀·잼', '쌀·잡곡', '커피'] },
  { id: 2, name: '리빙·인테리어', en: 'Living', sort: 3, subs: ['식기', '화병', '조명', '침구', '수납', '캔들'] },
  { id: 3, name: '패션의류', en: 'Apparel', sort: 1, subs: ['셔츠', '팬츠', '원피스', '니트', '아우터', '티셔츠'] },
  { id: 4, name: '신발·가방', en: 'Shoes & Bags', sort: 2, subs: ['로퍼', '스니커즈', '토트백', '백팩', '크로스백'] },
  { id: 5, name: '뷰티', en: 'Beauty', sort: 5, subs: ['스킨케어', '바디·핸드', '립', '선케어'] },
  { id: 6, name: '디지털·가전', en: 'Digital', sort: 6, subs: ['키보드', '음향', '데스크'] },
];
const SUB_ID = Object.fromEntries(CATEGORY_TREE.flatMap((c) => c.subs.map((s, i) => [s, c.id * 100 + i + 1])));

// 상품 — 프론트 목업 products(catalog.js) 39개. product_id = 스토어 id × 100 + 스토어 안 순번(목업 순서), 옵션 = 상품 × 100 + 조합 순번(1부터),
//   이미지 = 상품 × 10 + 순번(0이 대표) · S3 키 products/seed/{mock}-{1..3}.jpg (프론트 public/images/p 와 같은 파일).
//   discount = 즉시할인율 %, stock = 상품 전체 재고(조합에 고르게 나눔 — 나머지는 앞 조합), created = 등록 며칠 전, status 생략 = ON_SALE
export const CATALOG = [
  {id: 1101, mock: "p101", name: "프렌치 리넨 오버핏 셔츠", sub: "셔츠", price: 89000, discount: 15, stock: 142, created: 160, options: [["컬러",["오트밀","화이트","세이지"]],["사이즈",["S","M","L","XL"]]], material: "리넨 100% (프랑스산 원사)", origin: "대한민국"},
  {id: 1102, mock: "p102", name: "워싱 리넨 와이드 이지팬츠", sub: "팬츠", price: 69000, discount: 10, stock: 88, created: 140, options: [["컬러",["샌드","차콜"]],["사이즈",["S","M","L","XL"]]], material: "리넨 55% 코튼 45%", origin: "대한민국"},
  {id: 1103, mock: "p103", name: "리넨 셔링 롱 원피스", sub: "원피스", price: 119000, discount: 20, stock: 0, created: 120, options: [["컬러",["아이보리","블랙"]],["사이즈",["FREE"]]], material: "리넨 100%", origin: "대한민국"},
  {id: 1104, mock: "p104", name: "코튼 케이블 니트 베스트", sub: "니트", price: 59000, discount: 0, stock: 61, created: 18, options: [["컬러",["크림","그레이"]],["사이즈",["FREE"]]], material: "코튼 100%", origin: "대한민국"},
  {id: 1105, mock: "p101", name: "리넨 반팔 셔츠 (시즌오프)", sub: "셔츠", price: 59000, discount: 40, stock: 12, status: "STOPPED", created: 300, options: [["사이즈",["S","M","L","XL"]]], material: "리넨 100%", origin: "대한민국"},
  {id: 1201, mock: "p201", name: "330g 헤비웨이트 반팔 티셔츠", sub: "티셔츠", price: 32000, discount: 10, stock: 1200, created: 400, options: [["컬러",["화이트","블랙","멜란지","네이비"]],["사이즈",["S","M","L","XL"]]], material: "코튼 100% (30수 싱글)", origin: "대한민국"},
  {id: 1202, mock: "p202", name: "울 블렌드 싱글 체스터 코트", sub: "아우터", price: 289000, discount: 25, stock: 34, created: 10, options: [["컬러",["카멜","차콜"]],["사이즈",["S","M","L","XL"]]], material: "울 70% 나일론 30%", origin: "대한민국"},
  {id: 1203, mock: "p203", name: "램스울 크루넥 니트", sub: "니트", price: 79000, discount: 0, stock: 310, created: 35, options: [["컬러",["오트","포레스트","버건디"]],["사이즈",["S","M","L","XL"]]], material: "램스울 100%", origin: "중국"},
  {id: 1204, mock: "p204", name: "셀비지 데님 스트레이트 진", sub: "팬츠", price: 98000, discount: 5, stock: 140, created: 200, options: [["허리",["28","30","32","34"]]], material: "코튼 100% 14oz", origin: "대한민국"},
  {id: 1301, mock: "p301", name: "수제 페니 로퍼", sub: "로퍼", price: 189000, discount: 12, stock: 75, created: 500, options: [["컬러",["브라운","블랙"]],["사이즈",["230","240","250","260","270","280"]]], material: "소가죽 / 고무창", origin: "대한민국 (부산)"},
  {id: 1302, mock: "p302", name: "캔버스 로우탑 스니커즈", sub: "스니커즈", price: 59000, discount: 0, stock: 420, created: 260, options: [["컬러",["내추럴","블랙"]],["사이즈",["230","240","250","260","270","280"]]], material: "코튼 캔버스 / 고무창", origin: "대한민국"},
  {id: 1303, mock: "p303", name: "베지터블 레더 데일리 토트백", sub: "토트백", price: 168000, discount: 10, stock: 22, created: 80, options: [["컬러",["탄","다크브라운"]]], material: "베지터블 태닝 소가죽", origin: "대한민국"},
  {id: 1304, mock: "p304", name: "코듀라 나일론 롤탑 백팩", sub: "백팩", price: 129000, discount: 15, stock: 64, created: 90, options: [["컬러",["블랙","올리브"]]], material: "코듀라 나일론 500D", origin: "베트남"},
  {id: 1305, mock: "p305", name: "미니 하프문 크로스백", sub: "크로스백", price: 79000, discount: 0, stock: 48, created: 14, options: [["컬러",["아이보리","블랙","버터"]]], material: "소가죽", origin: "대한민국"},
  {id: 201, mock: "p401", name: "분청 손잡이 머그 350ml", sub: "식기", price: 28000, discount: 0, stock: 95, created: 380, options: [["유약",["백토","재유","흑유"]]], material: "분청토 / 무연유약", origin: "대한민국 (전주)"},
  {id: 202, mock: "p402", name: "백자 림 접시 3종 세트", sub: "식기", price: 54000, discount: 10, stock: 40, created: 210, options: [["구성",["S+M+L","M 3장"]]], material: "백자토", origin: "대한민국 (전주)"},
  {id: 203, mock: "p403", name: "물레 성형 미니 화병", sub: "화병", price: 36000, discount: 0, stock: 17, created: 40, options: [["형태",["호리병","원통"]]], material: "조합토", origin: "대한민국 (전주)"},
  {id: 204, mock: "p404", name: "다완 찻잔 2인 세트", sub: "식기", price: 64000, discount: 8, stock: 9, created: 6, options: [], material: "분청토", origin: "대한민국 (전주)"},
  {id: 1501, mock: "p501", name: "오크 베이스 패브릭 테이블 램프", sub: "조명", price: 119000, discount: 15, stock: 55, created: 150, options: [["전구",["전구색 2700K","주백색 4000K"]]], material: "오크 원목 / 린넨 갓", origin: "대한민국"},
  {id: 1502, mock: "p502", name: "스톤워싱 리넨 침구 3종 세트", sub: "침구", price: 239000, discount: 30, stock: 70, created: 330, options: [["사이즈",["SS","Q","K"]],["컬러",["내추럴","테라코타","그레이"]]], material: "리넨 100%", origin: "리투아니아 원단 / 국내 봉제"},
  {id: 1503, mock: "p503", name: "핸드위빙 라탄 바스켓", sub: "수납", price: 42000, discount: 0, stock: 130, created: 70, options: [["크기",["S","M","L"]]], material: "라탄", origin: "인도네시아"},
  {id: 1504, mock: "p504", name: "소이 왁스 우드윅 캔들 200g", sub: "캔들", price: 26000, discount: 0, stock: 300, created: 400, options: [["향",["무화과 잎","흰 차","삼나무"]]], material: "소이왁스 / 우드윅", origin: "대한민국"},
  {id: 101, mock: "p601", name: "서귀포 노지 햇감귤 5kg", sub: "과일", price: 32900, discount: 18, stock: 900, created: 4, options: [["크기",["로얄과 (S~M)","대과 (L)","못난이 (혼합)"]]], material: "감귤 (노지 재배)", origin: "제주 서귀포시"},
  {id: 102, mock: "p602", name: "지리산 야생화 꿀 1.2kg", sub: "꿀·잼", price: 48000, discount: 0, stock: 120, created: 220, options: [], material: "벌꿀 100% (야생화)", origin: "경남 하동군"},
  {id: 103, mock: "p603", name: "유기농 현미 4kg (2026년 햅쌀)", sub: "쌀·잡곡", price: 29000, discount: 10, stock: 240, created: 12, options: [["도정",["현미","7분도","백미"]]], material: "유기농 현미 100%", origin: "전남 해남군"},
  {id: 104, mock: "p604", name: "설향 딸기잼 무첨가 300g", sub: "꿀·잼", price: 14500, discount: 0, stock: 410, created: 180, options: [["수량",["1병","2병","3병"]]], material: "딸기 70% 비정제원당 30%", origin: "충남 논산시"},
  {id: 1701, mock: "p701", name: "에티오피아 예가체프 G1 원두 200g", sub: "커피", price: 18000, discount: 0, stock: 500, created: 260, options: [["분쇄",["홀빈","핸드드립","에스프레소","모카포트"]]], material: "아라비카 100%", origin: "에티오피아 / 국내 로스팅"},
  {id: 1702, mock: "p702", name: "시그니처 블렌드 드립백 20개입", sub: "커피", price: 22000, discount: 10, stock: 380, created: 310, options: [["블렌드",["하루 (산미)","밤 (고소)"]]], material: "아라비카 100%", origin: "국내 로스팅"},
  {id: 1703, mock: "p703", name: "콜드브루 원액 500ml × 2", sub: "커피", price: 26000, discount: 0, stock: 0, created: 100, options: [], material: "커피추출액 100%", origin: "국내 제조"},
  {id: 1704, mock: "p704", name: "스테인리스 구스넥 드립 케틀 600ml", sub: "커피", price: 54000, discount: 15, stock: 60, created: 50, options: [["컬러",["실버","매트블랙"]]], material: "STS304", origin: "대만"},
  {id: 1801, mock: "p801", name: "시카 9 진정 토너 200ml", sub: "스킨케어", price: 24000, discount: 20, stock: 800, created: 420, options: [["구성",["단품","1+1"]]], material: "병풀추출물 외", origin: "대한민국"},
  {id: 1802, mock: "p802", name: "세라마이드 장벽 수분크림 50ml", sub: "스킨케어", price: 32000, discount: 10, stock: 600, created: 380, options: [], material: "세라마이드NP 외", origin: "대한민국"},
  {id: 1803, mock: "p803", name: "시어버터 핸드크림 50ml 3입", sub: "바디·핸드", price: 19000, discount: 0, stock: 900, created: 150, options: [["향",["무향","베르가못","라벤더"]]], material: "시어버터 외", origin: "대한민국"},
  {id: 1804, mock: "p804", name: "비건 컬러 립밤", sub: "립", price: 12000, discount: 0, stock: 450, created: 60, options: [["컬러",["클리어","로즈","코랄"]]], material: "호호바오일 외", origin: "대한민국"},
  {id: 1805, mock: "p805", name: "무기자차 데일리 선크림 SPF50+", sub: "선케어", price: 26000, discount: 15, stock: 520, created: 200, options: [], material: "징크옥사이드 외", origin: "대한민국"},
  {id: 1901, mock: "p901", name: "저소음 무접점 텐키리스 키보드", sub: "키보드", price: 219000, discount: 8, stock: 38, created: 90, options: [["키압",["35g","45g"]],["배열",["한글","영문"]]], material: "PBT 키캡 / 정전용량 무접점", origin: "중국 (국내 기획)"},
  {id: 1902, mock: "p902", name: "노이즈캔슬링 무선 헤드폰", sub: "음향", price: 249000, discount: 20, stock: 52, created: 130, options: [["컬러",["샌드","블랙"]]], material: "-", origin: "중국"},
  {id: 1903, mock: "p903", name: "월넛 원목 모니터 받침대", sub: "데스크", price: 89000, discount: 0, stock: 26, created: 30, options: [["폭",["600mm","800mm"]]], material: "북미산 월넛 원목", origin: "대한민국"},
  {id: 1904, mock: "p904", name: "울 펠트 데스크 매트 900×400", sub: "데스크", price: 34000, discount: 10, stock: 210, created: 240, options: [["컬러",["차콜","그레이","오트밀"]]], material: "울 펠트 / 논슬립 백", origin: "중국"},
];

// 판매가 = price × (100 − 할인율) / 100 을 10원 단위 반올림 (product 서버 규칙 Product.calculateSalePrice 와 같게)
export const salePrice = (p) => Math.round((p.price * (100 - p.discount)) / 1000) * 10;
// 옵션 조합 (그룹 0~2개의 곱) — 옵션 없는 상품은 값이 null 인 조합 1개
export const combos = (p) => (p.options.length === 0 ? [[]] : p.options[0][1].flatMap((a) => (p.options[1] ? p.options[1][1].map((b) => [a, b]) : [[a]])));

// 주문에 쓰는 상품 — 주문 상품주문은 첫 조합(상품 × 100 + 1)을 판매가로 산다. category = 하위, 판매수수료는 상위(스토어 카테고리) 기준
export const PRODUCTS = Object.fromEntries(CATALOG.map((p) => [p.id, {
  storeId: Math.floor(p.id / 100), name: p.name, price: salePrice(p), category: SUB_ID[p.sub],
  optionName: p.options.length ? combos(p)[0].join(' / ') : null,
}]));
export const subCategoryId = (p) => SUB_ID[p.sub];

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
