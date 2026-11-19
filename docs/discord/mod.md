# snw-discord (디스코드 연동 모드)

Fabric 서버 모드, Kotlin, `environment: "server"`. 브랜치 `discord-mod` 에서 개발한다(코드 단계에서 생성).

의존: Fabric API, FLK, **simpleNationWar(`snw-api`)**. 추가 라이브러리는 넣지 않는다. WebSocket 은 JDK 내장 `java.net.http.WebSocket` 을 쓴다.

## 1. 하는 일

| 기능 | 설명 |
|---|---|
| 봇 연결 | `ws://127.0.0.1:<port>/snw` 에 비밀 토큰으로 접속. 끊기면 지수 백오프로 재연결 |
| **접속 확인** | 로그인 단계에서 UUID 가 연동 목록에 없으면 접속을 거부하고 6자리 코드를 보여 준다 |
| 닉네임 반영 | `link.confirmed`, `nickname.updated` 를 받아 `snw-api` 로 닉네임 설정 |
| 소속 반영 | `nation.assign` 을 받아 `snw-api` 로 국가·원수 설정 |
| 이벤트 송신 | `snw-api` 이벤트(킬, 영토, 관계, 거점, 연구)와 이벤트 버스로 들어온 zomApo 이벤트(블러드문, 둥지, 감염 레벨, 망자, 강화)를 봇으로 전달 |
| 세션 기록 | 접속·퇴장 시각 송신 |
| 인게임 상호평가 (선택) | `rating.request` 를 받으면 접속 중인 대상자에게 바닐라 다이얼로그로 평가 폼을 띄우고 `rating.submit` 으로 돌려보낸다 |
| 공지 방송 | `announce` 를 받아 전체 채팅 + 타이틀 |

**채팅은 중계하지 않는다.** couriersRoost 의 전서구 지연을 살리기 위해서다. 필요해지면 설정으로 '전체 채팅만, 전서구 도착 시점에' 중계하는 옵션을 둔다.

## 2. 접속 확인

```
로그인 요청 (UUID, 이름)
 ├─ 연동 목록(캐시)에 있음 → 통과
 └─ 없음
     ├─ 봇 연결됨 → 코드 발급(10분), link.code 송신, 거부 화면:
     │     "디스코드 #등록 에서 [연동 코드 입력] 을 누르고 코드 482913 을 입력하세요"
     └─ 봇 연결 안 됨 → 거부 화면: "디스코드 봇 점검 중입니다. 잠시 후 다시 시도하세요"
```

- 바닐라 화이트리스트는 끄고 이 확인을 화이트리스트로 쓴다. 운영자는 `/snwd` 로 예외 UUID 를 둘 수 있다.
- 거부 화면 문구의 코드는 강조색(하늘 `#39C5BB`)으로 표시한다.
- 같은 UUID 로 10분 안에 다시 시도하면 같은 코드를 보여 준다.

## 3. 캐시

`config/snw-discord/cache.json`: 연동 목록(UUID ↔ 디스코드 ID), 닉네임, 국가 배정. 봇이 `welcome` 을 보낼 때마다 통째로 갱신한다. 봇이 꺼져 있어도 이 캐시로 기존 플레이어는 접속하고 닉네임도 보인다.

## 4. 대기열

봇과 연결이 끊긴 동안 생긴 이벤트 중 **잃으면 안 되는 것**(킬, 백신 조각, 거점 점령)만 `config/snw-discord/outbox.jsonl` 에 쌓았다가 재연결 후 보낸다. 나머지(접속 알림 등)는 버린다. 메시지마다 고유 id 가 있어 봇이 중복을 걸러 낸다.

## 5. 설정

`config/snw-discord.json`

```json
{
  "botUrl": "ws://127.0.0.1:7341/snw",
  "secretEnv": "SNW_WS_SECRET",
  "gateEnabled": true,
  "gateBypass": [],
  "relayChat": false,
  "inGameRating": true,
  "outboxTypes": ["player.death", "vaccine.shard", "landmark.captured"]
}
```

비밀 토큰은 설정 파일이 아니라 환경 변수에서 읽는다.

## 6. 명령

`/snwd status`, `/snwd reload`, `/snwd resync` (운영자). 일반 플레이어 명령 없음.
