# 모드 ↔ 봇 통신 규약 (v1)

## 1. 연결

| 항목 | 값 |
|---|---|
| 전송 | WebSocket, 텍스트 프레임(JSON) |
| 주소 | `ws://127.0.0.1:<port>/snw` (같은 머신. 외부에 열지 않는다) |
| 역할 | 봇 = 서버, 모드 = 클라이언트 |
| 인증 | 핸드셰이크 헤더 `Authorization: Bearer <SNW_WS_SECRET>` |
| 버전 | 헤더 `X-SNW-Protocol: 1`. 주 버전이 다르면 봇이 1008 로 닫는다 |
| 생존 확인 | 30초마다 WebSocket ping |
| 재연결 | 1, 2, 4, … 최대 60초 |

## 2. 봉투

```json
{ "v": 1, "id": "01JH8…", "type": "player.death", "ts": 1736420300123, "data": { } }
```

- `id`: 보낸 쪽이 만든 고유값(ULID). 받는 쪽은 최근 id 를 기억해 중복을 버린다.
- 확인이 필요한 메시지(표에서 ✔)는 받는 쪽이 `{"type":"ack","ref":"<id>"}` 를 돌려준다. 확인이 안 오면 재연결 후 다시 보낸다.
- 알 수 없는 `type` 은 무시하고 로그만 남긴다(한쪽만 먼저 업데이트돼도 깨지지 않게).

## 3. 모드 → 봇

| type | ✔ | data |
|---|---|---|
| `hello` | | `serverVersion`, `mods{snw,zomapo,…}`, `online[uuid]` |
| `season.start` | ✔ | `startedAt`, `nations{id: {spawn{x,z}}}` — 봇이 #공지에 개장 알림(좌표는 보내지 않는다) |
| `player.join` / `player.leave` | | `uuid`, `ts` |
| `link.code` | ✔ | `uuid`, `name`, `code`, `expiresAt` |
| `player.death` | ✔ | `victim`, `killer?`, `assists[]`, `cause`, `pvp`, `victimNation`, `killerNation?` |
| `revenant.created` | | `owner`, `nation`, `pos{dim,x,y,z}` (봇은 좌표를 국가 채널에만) |
| `nation.claim` | | `nation`, `added`, `removed`, `total`, `cap` |
| `nation.relation` | ✔ | `a`, `b`, `from`, `to`, `effectiveAt` |
| `landmark.captured` | ✔ | `landmark`, `from?`, `to` |
| `vaccine.shard` | ✔ | `person`, `nation`, `total` — 백신 조각 획득 |
| `zomapo.bloodmoon` | | `phase`(start/end), `result?{areas[], participants[]}` |
| `zomapo.nest` | | `event`(found/destroyed), `nest`, `participants[]` |
| `zomapo.daily` | | `globalRamp`, `nations{id: level}`, `serverInfection` |
| `starforce.success` | | `uuid`, `item`, `star` (10성 달성만) |
| `zomapo.bloodmoon.notice` | | `startsAt`, `target`(수도 id 또는 camp), `online` — 블러드문 10분 전 예고 |
| `stats.snapshot` | | `players[{uuid, k, d, a, dPvp, zombieKills, zombieScore, playtime}]` |
| `rating.submit` | ✔ | `round`, `rater`, `target`, `scores{category: 1~5 \| null}` |

## 4. 봇 → 모드

| type | ✔ | data |
|---|---|---|
| `welcome` | | `links[{uuid, discordId, nickname}]`, `nations[{uuid, nation, role}]` — 전체 동기화 |
| `link.confirmed` | ✔ | `uuid`, `discordId`, `nickname` |
| `link.revoked` | ✔ | `uuid` |
| `nickname.updated` | ✔ | `uuid`, `nickname` |
| `nation.assign` | ✔ | `assignments[{uuid, nation, role}]`, `nationNames{id: name}` |
| `rating.request` | | `round`, `deadline`, `categories[{id, label}]`, `pairs[{rater, targets[]}]` |
| `announce` | | `text`, `title?` |
| `stats.request` | | `uuids[]?` (비면 전원) |

## 5. 예시

```json
{ "v":1, "id":"01JH8Z3K", "type":"player.death", "ts":1736420300123,
  "data":{ "victim":"5b1…", "killer":"9c2…", "assists":["a77…"], "cause":"player",
           "pvp":true, "victimNation":"n2", "killerNation":"n1" } }
```

```json
{ "v":1, "id":"01JH8Z9Q", "type":"link.confirmed", "ts":1736420311000,
  "data":{ "uuid":"5b1…", "discordId":"123456789012345678", "nickname":"영희" } }
```

## 6. 메시지 모델 공유

메시지 데이터 클래스(kotlinx.serialization)는 `snw-protocol` 이라는 작은 공용 소스 모듈로 두고 모드와 봇이 같이 쓴다. 브랜치가 갈라져 있으므로, 기본 브랜치의 `protocol/` 디렉터리를 원본으로 두고 두 브랜치가 같은 커밋을 가져오는 방식(서브트리 또는 복사 + 버전 표기)으로 맞춘다.
