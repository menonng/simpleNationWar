# simpleNationWar

좀비 아포칼립스 야생 서버의 **국가 모드**. Fabric, Kotlin, 서버 사이드 전용.

[Towns and Nations](https://modrinth.com/plugin/towns-nations) 플러그인을 참고해 Fabric 모드로 다시 설계했다. 다만 이 서버에서 국가는 전쟁보다 **집단 형성**이 목적이라, 3개 국가 고정·명령어 최소화·오프라인 약탈 불가로 크게 단순화했다.

> **현재 단계: 기획 문서.** 코드는 아직 없다. 테스트 대상은 Minecraft 26.3, 26.4 정식 출시 즉시 26.4 로 올린다.

같은 서버의 좀비 모드는 [zomApo](https://github.com/menonng/zomApo), 서버 전체 기획(결정 사항, 모드 목록, 시뮬레이션, 일정)은 zomApo 의 [docs/server](https://github.com/menonng/zomApo/blob/HEAD/docs/server/README.md) 에 있다.

## 구성 요소와 브랜치

| 구성 요소 | 브랜치 | 설명 |
|---|---|---|
| **simpleNationWar** (`snw`) | 기본 브랜치 | 국가·직위·영토·보호·관계·거점전·국고, K/D/A, 커스텀 닉네임 표시, `/n`·`/info`, BlueMap·squaremap 연동, 다른 모드용 공개 API(`snw-api`) |
| **snw-discord** | `discord-mod` (코드 단계에서 생성) | 디스코드 봇과 WebSocket 으로 통신하는 서버 모드. 접속 확인(연동 안 된 계정 차단), 닉네임 동기화, 이벤트 송신, 상호평가 인게임 입력 |
| **디스코드 봇** | `discord-bot` (코드 단계에서 생성) | 독립 실행 Kotlin 프로세스. 가입·닉네임 등록, 마크 계정 연동, 상호평가(관리자 명령), 팀 편성, 국가별 채널 생성, 알림 |

문서는 모두 기본 브랜치의 `docs/` 에 둔다. 코드가 생기면 각 브랜치에 해당 구현이 들어간다.

## 일반 플레이어 명령어는 두 개

| 명령 | 별칭 | 하는 일 |
|---|---|---|
| `/n` | `/국가` | 국가 GUI: 국민 목록, 영토 지도(클릭으로 점령), 국고, 관계, 거점, 연구 |
| `/info [플레이어]` | `/정보` | 닉네임·국가·직위·K·KDA·좀비 처치·좀비 스코어 카드 |

국가 채팅은 바닐라 `/teammsg`(`/tm`)를 그대로 쓴다(국가 = 바닐라 팀). 전체 목록은 [docs/design/commands.md](docs/design/commands.md).

## 문서

| 문서 | 내용 |
|---|---|
| [docs/reference/towns-and-nations.md](docs/reference/towns-and-nations.md) | Towns and Nations 분석과 채택·변경·제외 대응표 |
| [docs/design/nation.md](docs/design/nation.md) | 국가 시스템: 3국 고정, 직위, 영토, 보호, 관계, 거점전, 국고, 연구 |
| [docs/design/commands.md](docs/design/commands.md) | 명령어 전체 (플레이어·관리자·디스코드) |
| [docs/design/stats-kda.md](docs/design/stats-kda.md) | K/D/A 정의와 저장, 표시 |
| [docs/design/nickname.md](docs/design/nickname.md) | 커스텀 닉네임 규칙과 쓰는 곳 |
| [docs/design/ui.md](docs/design/ui.md) | 국가 GUI, 국민 목록, 정보 카드, 색상 |
| [docs/design/map-integration.md](docs/design/map-integration.md) | BlueMap·squaremap 연동 |
| [docs/discord/README.md](docs/discord/README.md) | 디스코드 연동 전체 구조 |
| [docs/discord/bot.md](docs/discord/bot.md) | 디스코드 봇 |
| [docs/discord/mod.md](docs/discord/mod.md) | 디스코드 연동 모드 |
| [docs/discord/protocol.md](docs/discord/protocol.md) | 모드 ↔ 봇 통신 규약 |
| [docs/discord/peer-rating.md](docs/discord/peer-rating.md) | 상호평가(⭐ 5점)와 균형 팀 편성 |
| [docs/tech/architecture.md](docs/tech/architecture.md) | 기술 구조, `snw-api`, 저장 |
