package dev.menonng.snw.diary

/**
 * '오늘의 서버 기록' 문장 틀. 클로드 문체를 175% 로 끌어올린 목소리([Voice.CLAUDE]).
 *
 * 문체 요소(일부러 진하게 씀)
 *  - 결론부터 말하고 정리한다: "결론부터 말하면", "정리해 보면"
 *  - 조심스럽게 판단을 밝힌다: "제가 보기엔", "~인 것 같아요", "기록상으로는"
 *  - 짚고 넘어가기·덧붙이기: "한 가지 짚고 넘어가자면", "덧붙이자면", "다만", "물론"
 *  - 공정하고 다정하다: 누구 탓도 하지 않고, 수고를 알아준다
 *  - 줄표(—)와 괄호 속 혼잣말로 생각의 결을 보여 준다
 *
 * 틀 문법은 [Phrase] 와 같다. 모든 문장은 해요체로 끝난다.
 */
object ClaudeLexicon {
    private val t = mutableMapOf<String, List<String>>()
    private fun c(id: String, vararg templates: String) { t[id] = templates.toList() }

    fun forId(id: String): List<String>? = t[id]

    /** 문서 전체에 이만큼은 나와야 '175%' 로 본다. */
    val MARKERS = listOf("결론부터", "정리해 보면", "솔직히", "제가 보기엔", "것 같아요", "기록상", "흥미로운", "덧붙이자면",
        "짚고 넘어가", "다만", "물론", "—", "수고")
    const val MIN_MARKERS = 4

    init {
        // 한 줄 요약
        c("summary.bloodmoon.won", "결론부터 말하면, 오늘은 블러드문을 막아낸 날이에요.", "결론부터 말하면 — 블러드문이 왔고, 우리가 버텼어요.")
        c("summary.bloodmoon.lost", "결론부터 말하면, 오늘은 블러드문에 밀린 날이에요. 다만 그게 전부는 아니었어요.", "결론부터 말하면 — 블러드문을 막지 못했어요. 솔직히 아쉬운 밤이었어요.")
        c("summary.alpha", "결론부터 말하면, 오늘의 주인공은 쓰러진 알파였어요.", "결론부터 말하면 — 알파가 쓰러졌어요. 이건 꽤 큰일이에요.")
        c("summary.pvp", "결론부터 말하면, 오늘은 사람끼리의 싸움이 기억에 남는 날이에요.", "결론부터 말하면 — 칼이 좀비가 아닌 사람을 향한 날이 있었어요.")
        c("summary.deaths", "결론부터 말하면, 오늘은 좀비가 꽤 많은 걸 가져간 날이에요.", "결론부터 말하면 — 쉽지 않은 하루였어요.")
        c("summary.default", "결론부터 말하면, 오늘도 서버는 바쁘게 돌아갔어요.", "결론부터 말하면 — 크고 작은 일이 고르게 있었던 하루였어요.")
        c("summary.quiet", "결론부터 말하면 — 조용했어요. 그리고 조용한 날도 기록할 가치가 있다고 생각해요.", "결론부터 말하면, 오늘은 큰 사건이 없었어요. 솔직히 이런 날도 필요한 것 같아요.")

        c("opener.rise", "기록상으로는 감염도가 {a}에서 {b:으로/로} 올랐어요.", "정리해 보면, 감염도는 {d}만큼 짙어져 {b:이/가} 됐어요.")
        c("opener.fall", "기록상으로는 감염도가 {a}에서 {b:으로/로} 내려갔어요. 흔한 일은 아니에요.", "흥미로운 건 감염도가 {a}에서 {b:으로/로} 오히려 내려갔다는 점이에요.")
        c("opener.flat", "기록상으로는 감염도가 {b}에서 거의 움직이지 않았어요.", "감염도는 {b:으로/로} 어제와 같았어요. 숨 고르는 날이었던 것 같아요.")
        c("quiet", "기록할 만한 사건은 없었어요. 다들 각자의 자리에서 할 일을 한 것 같아요.", "솔직히 말하면 쓸 거리가 거의 없었어요 — 그리고 그건 좋은 소식이에요.")
        c("fullmoon", "보름달 밤이 {n}번 지나갔어요.", "덧붙이자면, 보름달 밤도 {n}번 있었어요.")

        c("zombie.single",
            "{part}에 {victim:이/가} (?zone:{zone}에서 ){variant}에게 쓰러졌어요. 운이 따르지 않은 밤이었던 것 같아요.",
            "{victim:은/는} {part}에 {variant:을/를} 만났고 — 이번에는 버티지 못했어요.")
        c("zombie.runner",
            "{victim:이/가} (?zone:{zone}에서 )러너에게 따라잡혔어요. 솔직히 러너는 누구라도 따라잡혀요.",
            "러너는 빨랐어요. {victim:이/가} 그걸 몸소 확인했어요.")
        c("zombie.bomber",
            "{victim:이/가} 부패 크리퍼의 폭발에 휘말렸어요. 그 쉭 소리는 들어도 늘 늦게 들리는 것 같아요.",
            "(?zone:{zone}에서 )폭발이 있었고, {victim:이/가} 그 한가운데 있었어요.")
        c("zombie.lurker",
            "{victim:이/가} 잠복자에게 기습당했어요. 보이지 않는 상대를 피하는 건 — 솔직히 거의 불가능해요.",
            "{part}에 {victim:이/가} 보이지 않는 무언가에게 당했어요. 기록상으로는 잠복자였어요.")
        c("zombie.group",
            "{part}에는 {victims:이/가} 좀비에게 쓰러졌어요. 숫자로 보면 꽤 아픈 시간이었어요.",
            "정리해 보면, {part}에 {victims:이/가} 차례로 쓰러졌어요.")

        c("accident", "{victim:이/가} {accidentVerbPolite}. 좀비 탓은 아니었어요.", "한 가지 짚고 넘어가자면, {victim:이/가} {accidentVerbPolite}.")
        c("accident.today", "{victim:은/는} 오늘만 {countWord} {accidentVerbPolite}. 같은 실수를 두 번 하는 건 — 사람이라서 그래요.", "{victim:이/가} 또 {accidentVerbPolite}. 하루에 {countWord}이에요.")
        c("accident.repeat", "{victim:은/는} 또 {accidentVerbPolite}. 기록상 {count}번째예요.", "솔직히 말하면, {victim:이/가} {accidentVerbPolite} 일은 이제 {count}번째라 기록하는 쪽도 익숙해졌어요.")

        c("pvp",
            "{killer:이/가} (?zone:{zone}에서 ){victim:을/를} 쓰러뜨렸어요.(?assists: {assists:이/가} 거들었고요.)",
            "(?zone:{zone}에서 ){victim:과/와} {killer:이/가} 맞붙었고, 남은 쪽은 {killer:이었어요/였어요}.")
        c("pvp.revenge",
            "{killer:이/가} {victim:을/를} 쓰러뜨렸어요. 제가 보기엔 며칠 전 일을 잊지 않고 있었던 것 같아요.",
            "흥미로운 건, 이번엔 {killer:이/가} {victim:을/를} 쓰러뜨렸다는 점이에요. 빚은 이렇게 갚아지기도 해요.")
        c("pvp.rivalry",
            "{killer:과/와} {victim} 사이는 오늘도 조용하지 않았어요. 이번엔 {killer:이/가} 이겼어요.",
            "{killer:과/와} {victim:은/는} 또 맞붙었어요 — 이쯤 되면 오래된 사이라고 불러도 될 것 같아요.")
        c("pvp.first", "기록상 이 서버의 첫 PvP예요. {killer:이/가} {victim:을/를} 쓰러뜨렸어요.", "짚고 넘어가야 할 일이 하나 있어요 — 처음으로 사람끼리 칼을 맞댔어요. {killer:이/가} {victim:을/를} 쓰러뜨렸어요.")

        c("revenant.self", "{owner:은/는} 자기 망자를 직접 찾아가 짐을 되찾았어요. 잃은 건 없어요.", "{owner:이/가} 자기 망자와 다시 만났고 — 이번엔 {owner:이/가} 이겼어요.")
        c("revenant.mate", "{owner:은/는} {by:과/와} 함께 망자를 찾아가 짐을 되찾았어요. 혼자보다 둘이 나은 일이었던 것 같아요.", "{by:이/가} 곁을 지키는 동안 {owner:이/가} 자기 망자를 정리했어요. 좋은 이웃이에요.")

        c("alpha", "{killers:이/가} (?zone:{zone}에서 )알파를 쓰러뜨렸어요. 쉬운 상대는 아니었을 거예요.", "알파가 쓰러졌어요. 해낸 사람은 {killers:이에요/예요}.")
        c("alpha.first", "기록상 첫 알파 처치예요. {killers:이/가} 해냈어요. 솔직히 박수를 보내고 싶어요.", "짚고 넘어가자면 — 이 서버에서 처음으로 알파가 쓰러졌어요. {killers:이/가} 함께였어요.")
        c("nest", "{participants:이/가} (?zone:{zone}의 )감염 둥지를 부쉈어요. 그 근처는 당분간 조금 숨쉬기 편할 것 같아요.", "감염 둥지 하나가 사라졌어요. 수고한 사람은 {participants:이에요/예요}.")

        c("bloodmoon.won", "블러드문이 {target:을/를} 덮쳤지만 막아냈어요.(?participants: 함께한 사람은 {participants:이에요/예요}.)", "{target}의 블러드문은 막아냈어요. 다들 정말 수고 많으셨어요.")
        c("bloodmoon.lost", "블러드문이 {target:을/를} 덮쳤고, 이번에는 버티지 못했어요.(?participants: 그래도 {participants:이/가} 끝까지 남아 있었어요.)", "{target}의 블러드문은 막지 못했어요. 다만 다음엔 다를 거라고 생각해요.")
        c("bloodmoon.won.after", "결과적으로 {target:은/는} 지켜냈어요.(?participants: 함께한 사람은 {participants:이에요/예요}.)")
        c("bloodmoon.lost.after", "결과적으로 {target:은/는} 지키지 못했어요.(?participants: 그래도 {participants:이/가} 끝까지 버텼어요.)")
        c("bloodmoon.first", "이 서버의 첫 블러드문이 떴어요. 솔직히 오래 기다린 사람도 있었을 것 같아요.")
        c("bloodmoon.wait", "꽤 오랜만의 블러드문이었어요.")

        c("landmark", "{nation:이/가} {landmark:을/를} 가져갔어요.(?from: {from}에게서 빼앗은 거예요.)", "{landmark}의 주인이 {nation:으로/로} 바뀌었어요.")
        c("landmark.first", "기록상 첫 거점 점령이에요. {nation:이/가} {landmark:을/를} 차지했어요.")
        c("advancement", "{person:이/가} 생존 도감 {title:을/를} 채웠어요.", "덧붙이자면, {person:은/는} 오늘 {title:을/를} 달성했어요.")
        c("advancement.rare", "{person:이/가} {title:을/를} 달성했어요. 기록상 흔하지 않은 일이에요.", "흥미로운 기록이 하나 있어요 — {person:이/가} {title:을/를} 달성했어요.")
        c("advancement.group", "생존 도감도 채워졌어요: {list}.", "정리해 보면 생존 도감에는 {list:이/가} 새로 올랐어요.")
        c("starforce", "{person:이/가} {item:을/를} {star}성까지 끌어올렸어요.")
        c("starforce.first", "{person:이/가} 처음으로 {item:을/를} {star}성에 올렸어요.")
        c("shard", "{person:이/가} 백신 조각을 주웠어요. 작은 조각이지만 의미는 작지 않은 것 같아요.", "백신 조각이 하나 늘었어요. 주운 사람은 {person:이에요/예요}.")

        c("nation.top", "좀비 스코어는 {nation:이/가} 가장 높았어요. 다른 국가도 크게 뒤처진 건 아니에요.", "제가 보기엔 오늘의 좀비 사냥은 {nation} 쪽이 조금 앞섰어요.")
        c("closer.next", "내일 감염도는 {b} 근처에서 시작해요. 무리하지 않으셨으면 해요.", "다음 기록은 감염도 {b}에서 시작할 것 같아요.")
        c("closer.nobloodmoon", "블러드문은 아직이에요. 슬슬 올 때가 된 것 같아요.", "덧붙이자면 블러드문이 한동안 없었어요. 저라면 화살을 조금 더 챙겨 두겠어요.")
        c("closer.shards", "서버 전체의 백신 조각은 이제 {n}개예요.", "정리해 보면 백신 조각은 서버 전체 {n}개가 됐어요.")
        c("signoff", "오늘도 기록할 수 있어서 다행이에요.", "물론 기록에 다 담기지 않은 하루가 더 많았을 거예요. 오늘도 수고 많으셨어요.")
    }
}
