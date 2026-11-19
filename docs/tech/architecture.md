# 기술 구조

## 1. 스택

zomApo 와 같다: Kotlin 2.4.x, Java 25, Fabric Loader 0.19.3+, Fabric API `0.161.0+26.3`, FLK `1.14.2+kotlin.2.4.21`, Gradle 플러그인 `net.fabricmc.fabric-loom`(26.x: Yarn 없음, Mojang 이름, 리매핑 없음), `"environment": "server"`. 버전 정책은 zomApo [version-policy.md](https://github.com/menonng/zomApo/blob/HEAD/docs/server/version-policy.md).

선택 의존(`compileOnly`): BlueMap API, squaremap API.

## 2. 모듈

```
simpleNationWar/            (기본 브랜치)
├─ snw-api/                 다른 모드가 컴파일 시 참조하는 인터페이스만. 구현 없음
│  └─ dev/menonng/snw/api/
│     ├─ NationApi.kt       국가·소속·영토·관계·닉네임 조회, 이벤트 구독
│     ├─ StatProvider.kt    /info·국민 목록에 줄을 추가하는 확장점
│     ├─ ProjectCondition.kt 국가 프로젝트(연구) 조건 확장점
│     ├─ MapLayers.kt       지도 레이어를 그리는 공용 창구
│     └─ ServerEventBus.kt  모드 간 게임 이벤트 전달(디스코드 모드가 구독)
├─ snw/                     본체 모드 (snw-api 를 jar 에 포함)
│  └─ dev/menonng/snw/
│     ├─ Snw.kt
│     ├─ nation/            국가, 직위, 소속, 이적
│     ├─ territory/         점령, 상한, 전초기지, 보호, 자동 복구, 주민 상한
│     ├─ relation/          관계, 휴전, 고립 보정
│     ├─ landmark/          거점, 거점전
│     ├─ treasury/          국고(가상 인벤토리), 기록
│     ├─ project/           국가 프로젝트 틀
│     ├─ stats/             K/D/A, 피해 기여 추적
│     ├─ display/           닉네임(탭 목록·채팅 표시 이름), 바닐라 팀, 로케이터 바 필터
│     ├─ map/               MapAdapter, BlueMap·squaremap 어댑터, 다각형 병합
│     ├─ gui/               상자 메뉴 DSL, /n 화면들, 다이얼로그
│     ├─ command/           /n, /info, /snw (Brigadier Kotlin DSL)
│     └─ data/              SavedData 코덱
├─ logic/                   마인크래프트를 모르는 순수 Kotlin: K/D/A 판정(어시스트 창·최소 기여·반복 킬), 영토 상한, 점령 조건, 다각형 병합, 관계 전이·휴전 규칙. JUnit 테스트
├─ protocol/                모드 ↔ 봇 메시지 모델 원본 (discord-mod, discord-bot 브랜치가 가져감)
└─ docs/
```

`discord-mod` 브랜치: `snw-discord/` 모드. `discord-bot` 브랜치: `bot/` 애플리케이션. 두 브랜치 모두 `protocol/` 을 공유한다([protocol.md](../discord/protocol.md) 6절).

## 3. `snw-api`

다른 모드(zomApo, snw-discord)는 `snw-api` 만 `compileOnly` 로 참조하고, 실행 시 `FabricLoader.isModLoaded("snw")` 를 확인한 뒤 `NationApi.get()` 으로 구현을 얻는다.

```kotlin
interface NationApi {
    fun nations(): List<NationView>
    fun nationOf(player: UUID): NationView?
    fun sameNation(a: UUID, b: UUID): Boolean
    fun allied(a: UUID, b: UUID): Boolean
    fun ownerOf(level: ResourceKey<Level>, chunk: ChunkPos): NationView?
    fun territory(nation: NationId): Set<ChunkPos>
    fun nickname(player: UUID): String?
    fun setNickname(player: UUID, nickname: String?)          // snw-discord 용
    fun assign(player: UUID, nation: NationId?, role: Role)    // snw-discord 용
    fun events(): NationEvents                                 // onClaimChanged, onRelationChanged, onPlayerKill, onLandmarkCaptured, onProjectStage
    fun eventBus(): ServerEventBus
    fun mapLayers(): MapLayers?                                // 지도 모드가 없으면 null

    companion object { fun get(): NationApi = … }
}

interface StatProvider {                                       // 엔트리포인트 "snw:stat_provider"
    fun lines(player: UUID, viewer: UUID?): List<StatLine>      // /info, 국민 목록에 표시할 줄
}

interface ProjectConditionProvider {                           // 엔트리포인트 "snw:project_condition"
    fun conditions(): List<ProjectConditionType>                // "변종 n종 처치" 같은 조건 타입 등록
}
```

- 확장점은 Fabric **커스텀 엔트리포인트**로 받는다. zomApo 의 `fabric.mod.json` 에 `"snw:stat_provider": ["dev.menonng.zomapo.integration.snw.ZombieStats"]` 처럼 적는다. 의존 방향이 한쪽(zomApo → snw-api)으로 유지된다.
- `NationView` 는 읽기 전용 스냅샷(id, 이름, 색, 원수, 국민, 수도)이다.

## 4. 믹스인 지점 (26.3 jar 에서 `javap` 로 확인할 목록)

| 목적 | 대상 (후보) |
|---|---|
| 블록 설치·파괴·상호작용 보호 | 서버 플레이어 상호작용 처리, Fabric 이벤트(`PlayerBlockBreakEvents`, `UseBlockCallback` 등) 우선 |
| 폭발 피해 기록·복구 | 폭발 처리에서 영향 블록 목록을 받는 지점 |
| 불 번짐, 엔더맨 블록 이동 | 해당 블록·목표 클래스 |
| 주민 번식 차단 | 주민 번식 가능 판정 |
| 채팅·사망 메시지 표시 이름 | 플레이어 표시 이름 |
| 기본 채팅 → 국가 채팅 | **구현됨.** `TeamMsgCommand.sendMessage` 호출용 Invoker(`mixin/TeamMsgCommandInvoker`) + `ALLOW_CHAT_MESSAGE` 의 앞선 단계(`snw:chat_routing`) |
| 탭 목록 이름 | 플레이어 정보 갱신 패킷의 표시 이름 |
| 로케이터 바 필터 | 웨이포인트 추적·전송 관리 |
| 로그인 거부(snw-discord) | 로그인 단계 이벤트 (Fabric 네트워킹 이벤트 우선) |

Fabric API 이벤트로 되는 것은 믹스인보다 이벤트를 먼저 쓴다.

## 5. 저장

| 데이터 | `SavedData` | 키 |
|---|---|---|
| 국가 | `snw_nations` | 국가 id |
| 플레이어 (소속, 직위, K/D/A, 닉네임 캐시, 마지막 접속, 누적 시간) | `snw_players` | UUID |
| 거점 | `snw_landmarks` | 거점 id |
| 복구 대기 블록 | `snw_restore` | 예정 시각 |

모두 코덱으로 직렬화한다. 영토는 국가별 `LongSet`(청크 좌표 압축)으로 저장하고, 청크 → 국가 역색인은 불러올 때 메모리에 만든다.

## 6. UI 구현

monun 저장소에서 가져오는 구조다. 전체 분석은 zomApo 의 [reference/monun.md](https://github.com/menonng/zomApo/blob/HEAD/docs/reference/monun.md).

- **상자 메뉴 GUI**: 서버 사이드 `ChestMenu` 위에 invfx 와 같은 모양의 작은 Kotlin DSL. `frame(줄, 제목) { slot(x, y) {…}; pane(영역) { onClick { x, y -> … } }; list(영역, { 데이터 }) { transform {…}; onClickItem {…} } }`. 국민 탭 = `list` + 페이지, 영토 탭 = `pane` 좌표 클릭 → 청크 오프셋, 탭 버튼 = `slot`. 클릭은 서버에서 처리하고 아이템 이동은 막는다. 잠긴 탭·칸은 방벽 아이템 + 해금 조건 설명(inv-captive 의 잠긴 칸 표시).
- **입력 폼**: 바닐라 다이얼로그(26.x)를 speculation 의 `GameDialog<R>` 처럼 감싼다. `suspend fun <R> ask(player, dialog: SnwDialog<R>, timeout, default): R`. 관계 선언 확인, 직위 변경, 국고 대량 인출 확인, 인게임 상호평가가 같은 틀을 쓴다. 시간 초과·창 닫기는 `default`.
- **명령**: Brigadier 위 kommand 와 같은 모양의 작은 DSL(`then` / `executes` / `requires`). `/info` 의 대상 인수는 kommand 의 `dynamic` 인수처럼 직접 해석하고 자동완성에 **마크 이름과 커스텀 닉네임을 모두** 띄운다. 운영자 명령은 `requires` 로 숨긴다.
- **문구**: 모든 안내 문구는 설정 파일 템플릿(`$닉네임`, `${국가}` 치환, tap `TemplateSupport` 와 같은 생각).

## 7. 테스트

- `logic` 단위 테스트: K/D/A 판정 경계(14.9초/15.1초, 최소 기여, 10분 안 3번째 킬), 영토 상한 식, 다각형 병합(구멍·떨어진 조각), 관계 전이(악화 즉시·개선 동의·휴전 72시간).
- 서버 게임테스트: 점령 조건, 상한 계산, 보호 행렬, 자동 복구, K/D/A 판정(어시스트 15초·최소 기여·같은 상대 반복), 다각형 병합(구멍 포함).
- 헤드리스 서버 기동 CI: simpleNationWar 단독, + zomApo, + snw-discord(봇 없이) 세 조합.
- 봇: 프로토콜 왕복 테스트(가짜 모드 클라이언트), 편성 알고리즘 단위 테스트.

## 8. 라이선스 메모

참고한 Towns and Nations 와 monun 저장소는 GPL-3.0 이다. **코드를 옮기지 않고 설계만 참고**한다. 이 저장소의 라이선스는 코드 단계에서 정한다.
