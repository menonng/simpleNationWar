# simpleNationWar

좀비 아포칼립스 야생 서버의 **국가 모드**. Fabric, Kotlin, 서버 사이드 전용.

[Towns and Nations](https://modrinth.com/plugin/towns-nations) 플러그인을 참고해 Fabric 모드로 다시 설계했다. 다만 이 서버에서 국가는 전쟁보다 **집단 형성**이 목적이라, 3개 국가 고정·명령어 최소화·오프라인 약탈 불가로 크게 단순화했다.

> **현재 단계: 개발 시작.** 빌드 골격, 공개 API(`snw-api`), 순수 로직(닉네임 개인정보 검사, K/D/A 판정, 영토 규칙, 영토 다각형 병합, 한국어 조사), 생존일지 생성기가 들어왔다. 시즌 1 은 2027-01-16 ~ 02-07(23일). 문서의 일·시간은 모두 현실 시간이다.

같은 서버의 좀비 모드는 [zomApo](https://github.com/menonng/zomApo), 서버 전체 기획(결정 사항, 모드 목록, 시뮬레이션, 일정)은 zomApo 의 [docs/server](https://github.com/menonng/zomApo/blob/HEAD/docs/server/README.md#개요) 에 있다.

## 빌드

JDK 25 가 필요하다. Gradle 과 마인크래프트·Fabric 파일은 래퍼가 받는다.

```
./gradlew buildAll          # 순수 모듈 테스트 + 모드 jar (mod/build/libs/)
./gradlew :diary:test       # 생존일지 테스트, 샘플은 diary/build/diary-samples.md
./gradlew :mod:runServer    # 26.3 전용 서버를 띄워 모드가 켜지는지 확인(스모크 테스트)
```

| 모듈 | 내용 |
|---|---|
| `snw-api` | 다른 모드가 참조하는 인터페이스. 마인크래프트 타입을 쓰지 않는다 |
| `logic` | 순수 Kotlin 규칙: 닉네임 개인정보 검사, K/D/A 판정, 영토 상한·점령·해제, 영토 다각형 병합, 한국어 조사 |
| `diary` | 생존일지 생성기(규칙 기반, 외부 AI 없음). 디스코드 봇이 쓴다 |
| `mod` | Fabric 서버 모드. `snw-api`·`logic` 을 jar 안에 포함한다 |

## 구성 요소와 브랜치

| 구성 요소 | 브랜치 | 설명 |
|---|---|---|
| **simpleNationWar** (`snw`) | 기본 브랜치 | 국가·직위·영토·보호·관계·거점전·국고, K/D/A, 커스텀 닉네임 표시, `/n`·`/info`·`/g`, 채팅 경로(기본 국가 채팅), BlueMap·squaremap 연동, 다른 모드용 공개 API(`snw-api`) |
| **snw-discord** | `discord-mod` (코드 단계에서 생성) | 디스코드 봇과 WebSocket 으로 통신하는 서버 모드. 접속 확인(연동 안 된 계정 차단), 닉네임 동기화, 이벤트 송신, 상호평가 인게임 입력 |
| **디스코드 봇** | `discord-bot` (코드 단계에서 생성) | 독립 실행 Kotlin 프로세스. 가입·닉네임 등록, 마크 계정 연동, 상호평가(관리자 명령), 국가 추첨식, 국가별 채널 생성, 알림 |
| **서버 관리 창** (`panel`) | `server-panel` | Fabric 서버 켜기·끄기, 색 있는 콘솔·자동완성, 접속자·TPS·MSPT, 정기 재시작·크래시 복구, 백업, 시즌 버튼. Swing 데스크톱 앱 ([docs/panel.md](docs/panel.md)) |

문서는 모두 기본 브랜치의 `docs/` 에 둔다. 코드가 생기면 각 브랜치에 해당 구현이 들어간다.

## 일반 플레이어 명령어는 세 개

| 명령 | 별칭 | 하는 일 |
|---|---|---|
| `/n` | `/국가` | 국가 GUI: 국민 목록, 영토 지도(클릭으로 점령), 국고, 관계, 거점, 연구 |
| `/info [플레이어]` | `/정보` | 닉네임·국가·직위·K·KDA·좀비 처치·좀비 스코어 카드 |
| `/g <메시지>` | `/전체` | 전체 채팅 |

**그냥 치는 채팅은 국가 채팅**이다(국가 = 바닐라 팀, 바닐라 `/teammsg` 와 같은 경로). 전체 채팅은 `/g` 로만 나간다. couriersRoost 가 있으면 둘 다 전서구로 배달된다. 전체 목록은 [docs/design/commands.md](docs/design/nation.md#명령어).

## 문서

### 통합 문서 (먼저 읽을 것)

| 문서 | 목차 |
|---|---|
| [국가 모드](docs/design/nation.md) | 국가(3국·직위·영토·보호·관계·거점전·국고·스폰) · 명령어(채팅 포함) · 화면 · 지도 연동 · KDA |
| [디스코드 연동](docs/discord/README.md) | 구조 · 디스코드 봇(가입·명령·알림·DB) · 연동 모드 |

### 상세 문서

| 문서 | 내용 |
|---|---|
| [nickname.md](docs/design/nickname.md) | 커스텀 닉네임 규칙과 개인정보 검사 |
| [peer-rating.md](docs/discord/peer-rating.md) | 상호평가(⭐ 5점)와 국가 추첨식 |
| [diary.md](docs/discord/diary.md) | 생존일지: 입력(개인정보 제외), 생성 과정, 목소리, AI 말투 규칙 |
| [protocol.md](docs/discord/protocol.md) | 모드 ↔ 봇 통신 규약 |
| [architecture.md](docs/tech/architecture.md) | 기술 구조, `snw-api`, 저장 |
| [towns-and-nations.md](docs/reference/towns-and-nations.md) | Towns and Nations 분석과 채택·변경·제외 대응표 |
