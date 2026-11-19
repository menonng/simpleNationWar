# 지도 연동 (BlueMap, squaremap)

두 지도 모드를 모두 지원한다. 서버에는 둘 중 하나만 깔아도, 둘 다 깔아도 된다.

## 1. 구조

```
MapAdapter (인터페이스)
├─ BlueMapAdapter    BlueMap API (de.bluecolored:bluemap-api) — BlueMapAPI.onEnable 에서 등록
└─ SquaremapAdapter  squaremap API (xyz.jpenilla:squaremap-api) — SquaremapProvider.get()
```

- 어댑터는 해당 모드가 있을 때만 켠다(`FabricLoader.isModLoaded`). 지도 API 는 `compileOnly`.
- simpleNationWar 는 '무엇을 그릴지'(레이어·도형·색·팝업)를 지도 모드와 무관한 모델로 만들고, 어댑터가 각 API 로 옮긴다.
- zomApo 의 감염 레이어도 같은 `MapAdapter` 를 `snw-api` 로 받아 그린다(지도 코드를 두 번 만들지 않는다).

## 2. 레이어

| 레이어 | 내용 | 기본 표시 | 갱신 |
|---|---|---|---|
| 국가 영토 | 국가 색 반투명(채움 25%) 다각형 + 테두리 | 켬 | 점령 변화 후 10초 디바운스 |
| 수도·거점 | 아이콘 마커. 거점은 소유국 색 | 켬 | 변화 시 |
| 감염 레벨 | 4×4 청크 묶음 타일. Lv1 이상만, 초록 `#55BB44` → 보라 `#660099` → 빨강 `#FF0045` | 켬 | 매일 06:00 |
| 보급 투하 | 상자 마커 | 이벤트 5분 전 ~ 종료 | 이벤트 |
| 감염 둥지 | 발견된 둥지만 | 켬 | 발견·파괴 시 |
| 생존자 캠프 | 중립 구역 원 | 켬 | 고정 |

### 영토 팝업

```
1국
원수: 철수
국민: 철수, 영희, 민수, 지민, 하늘
영토: 54 청크 · 감염 Lv2.8
```

## 3. 청크 묶음 → 다각형

영토는 청크 집합이므로 그대로 그리면 마커가 수십~수백 개가 된다. 하나의 다각형(구멍 포함)으로 합친다.

1. 영토 청크마다 네 변을 만든다.
2. 이웃 청크와 공유하는 변(양쪽에서 반대 방향으로 나오는 변)은 지운다.
3. 남은 변을 꼭짓점으로 이어 닫힌 고리를 만든다.
4. 고리의 방향(넓이 부호)으로 바깥 경계와 구멍을 나누고, 구멍은 그것을 감싸는 바깥 경계에 붙인다.
5. 일직선 위의 중간 꼭짓점은 지운다.

BlueMap 은 `ShapeMarker`(구멍 지원) 또는 높이를 주는 `ExtrudeMarker`, squaremap 은 `MultiPolygon`(구멍 지원)으로 그린다. 전초기지처럼 떨어진 조각은 다각형 여러 개가 된다.

## 4. 공개 범위 설정

서버 결정은 zomApo [decisions.md](https://github.com/menonng/zomApo/blob/HEAD/docs/server/decisions.md) 3번(지형·영토·감염 공개, 실시간 위치·지하 비공개).

| 항목 | BlueMap | squaremap |
|---|---|---|
| 실시간 플레이어 위치 끄기 | 플레이어 마커 기능 끄기 | `player-tracker` 끄기 |
| 지하 숨기기 | 동굴 제거 설정(지표 아래 일정 깊이 렌더 안 함) | 지하 렌더 끔 |
| 네더 천장 | 천장 위 렌더 안 함 | 같음 |

정확한 설정 키는 각 모드의 26.4 대응 버전 설정 파일에서 확인한다.

## 5. 시즌 전체 지도 포스터

시즌 결산 때 지도 타일(squaremap 의 평면 타일, 또는 BlueMap 평면도)을 이어 붙여 한 장의 큰 이미지를 만들고, 그 위에 시즌 마지막 날의 영토 경계·거점 소유를 얹어 디스코드 명예의 전당에 올린다. monun 이 포크해 둔 dynmap-assemble(Dynmap 타일을 한 장으로 합치는 스크립트)과 같은 방식이다. 서버 밖에서 돌리는 작은 스크립트로 충분하다.

## 6. 버전

- BlueMap: 5.24 에서 26.3 월드 지원 추가, Fabric 5.28 이 26.3 대상(2026-09-25).
- squaremap: 1.4.x 가 26.3 대응. Fabric 빌드는 버전 목록에서 확인 필요.
- 둘 다 26.4 대응을 기다려야 한다. 한쪽만 먼저 나오면 그쪽으로 개장한다([version-policy.md](https://github.com/menonng/zomApo/blob/HEAD/docs/server/version-policy.md)).
