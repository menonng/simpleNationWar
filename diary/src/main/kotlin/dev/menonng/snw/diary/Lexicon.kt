package dev.menonng.snw.diary

/**
 * 문장 틀. 목소리마다 따로 쓴다(어미를 기계적으로 바꾸면 어색해지므로).
 *
 * 틀 문법
 *  - `{이름}`            값 그대로
 *  - `{이름:이/가}`      값 + 받침에 맞는 조사 (은/는, 이/가, 을/를, 과/와, 으로/로, 이었다/였다, 이에요/예요 …)
 *  - `[가|나|다]`        하나를 고른다
 *  - `(?이름:글)`        값이 있을 때만 '글'을 넣는다. 글 안에도 자리표시를 쓸 수 있다
 */
class Phrase(val id: String, val survivor: List<String>, val observer: List<String>) {
    fun forVoice(v: Voice) = when (v) {
        Voice.SURVIVOR -> survivor
        Voice.OBSERVER -> observer
        Voice.CLAUDE -> ClaudeLexicon.forId(id) ?: observer
    }
}

object Lexicon {
    private val all = mutableMapOf<String, Phrase>()
    private fun p(id: String, survivor: List<String>, observer: List<String>) = Phrase(id, survivor, observer).also { all[id] = it }

    operator fun get(id: String): Phrase = all[id] ?: ClaudeLexicon.forId(id)?.let { Phrase(id, it, it) } ?: error("문장 틀 없음: $id")

    val opener_rise = p("opener.rise",
        listOf("감염도가 {a}에서 {b:으로/로} 올랐다.", "감염도는 {b}. 하루 사이 {d}만큼 짙어졌다.", "감염도가 또 올랐다. {a}에서 {b:으로/로}."),
        listOf("감염도가 {a}에서 {b:으로/로} 올랐어요.", "감염도는 {b:이에요/예요}. 어제보다 {d}만큼 짙어졌어요."))
    val opener_fall = p("opener.fall",
        listOf("감염도가 {a}에서 {b:으로/로} 내려갔다. [드문 일이다|모처럼이다].", "감염도가 조금 걷혔다. {a}에서 {b:으로/로}."),
        listOf("감염도가 {a}에서 {b:으로/로} 조금 내려갔어요.", "모처럼 감염도가 내려갔어요. {a}에서 {b:으로/로}요."))
    val opener_flat = p("opener.flat",
        listOf("감염도는 {b} 그대로였다.", "감염도는 어제와 같은 {b:이었다/였다}."),
        listOf("감염도는 {b:으로/로} 어제와 같았어요.", "감염도는 그대로 {b:이에요/예요}."))
    val quiet = p("quiet",
        listOf("특별한 일은 없었다. 다들 각자 할 일을 했다.", "기록할 사건이 없었다. 망치 소리만 들렸다.", "조용히 지나갔다. 아무도 쓰러지지 않았다."),
        listOf("큰 사건 없이 지나간 하루였어요.", "조용한 하루였어요. 다들 각자 할 일을 했어요."))
    val fullmoon = p("fullmoon",
        listOf("보름달이 {n}번 떴다.", "보름달 밤이 {n}번 지나갔다."),
        listOf("보름달 밤이 {n}번 있었어요.", "보름달이 {n}번 떴어요."))

    val zombie_single = p("zombie.single",
        listOf(
            "{part}에 {victim:이/가} (?zone:{zone}에서 ){variant}에게 [당했다|쓰러졌다|붙잡혔다].",
            "(?zone:{zone}. ){victim:은/는} {variant:을/를} 떨쳐내지 못했다.",
            "{victim:이/가} {part}에 쓰러졌다. 상대는 {variant:이었다/였다}.",
        ),
        listOf(
            "{part}에 {victim:이/가} (?zone:{zone}에서 ){variant}에게 쓰러졌어요.",
            "{victim:은/는} {part}에 {variant:을/를} 만났고, 이번엔 운이 따르지 않았어요.",
        ))
    val zombie_runner = p("zombie.runner",
        listOf("{victim:이/가} (?zone:{zone}에서 )러너에게 따라잡혔다.", "{part}의 러너는 빨랐다. {victim:이/가} 따라잡혔다."),
        listOf("{victim:이/가} (?zone:{zone}에서 )러너에게 따라잡혔어요. 러너는 정말 빨라요."))
    val zombie_bomber = p("zombie.bomber",
        listOf("{victim:이/가} 자폭 좀비의 폭발에 휘말렸다.", "(?zone:{zone}에서 )쉭 하는 소리가 났고, {victim:이/가} 쓰러졌다."),
        listOf("{victim:이/가} 자폭 좀비의 폭발에 휘말렸어요."))
    val zombie_lurker = p("zombie.lurker",
        listOf("{victim:이/가} 잠복자에게 기습당했다. 소리도 없었다.", "{part}에 {victim:이/가} 보이지 않는 무언가에게 당했다. 잠복자였다."),
        listOf("{victim:이/가} 잠복자에게 기습당했어요. 안 보이는 상대는 피하기 어려워요."))
    val zombie_group = p("zombie.group",
        listOf("{part}에 {victims:이/가} 좀비에게 쓰러졌다.", "{part}에 쓰러진 사람이 [여럿이었다|적지 않았다]. {victims}."),
        listOf("{part}에는 {victims:이/가} 좀비에게 쓰러졌어요."))

    val accident = p("accident",
        listOf("{victim:이/가} {accidentVerb}.", "(?zone:{zone}에서 ){victim:이/가} {accidentVerb}."),
        listOf("{victim:이/가} {accidentVerbPolite}."))
    val accident_today = p("accident.today",
        listOf("{victim:은/는} 오늘만 {countWord} {accidentVerb}.", "{victim:이/가} 또 {accidentVerb}. 하루에 {countWord}이다."),
        listOf("{victim:은/는} 오늘만 {countWord} {accidentVerbPolite}.", "{victim:이/가} 또 {accidentVerbPolite}. 하루에 {countWord}이에요."))
    val accident_repeat = p("accident.repeat",
        listOf("{victim:이/가} 또 {accidentVerb}. [이번이 {count}번째다|세는 걸 그만둘까 한다].", "{victim:은/는} 오늘도 {accidentVerb}. {count}번째."),
        listOf("{victim:은/는} 또 {accidentVerbPolite}. 오늘까지 {count}번째예요.", "{victim:이/가} 다시 {accidentVerbPolite}. 같은 일이 {count}번째라 이제 익숙할 것 같아요."))

    val pvp = p("pvp",
        listOf(
            "{killer:이/가} (?zone:{zone}에서 ){victim:을/를} 쓰러뜨렸다.(?assists: {assists:이/가} 거들었다.)",
            "{victim:은/는} {killer:을/를} 넘지 못했다.(?assists: 옆에는 {assists:이/가} 있었다.)",
            "(?zone:{zone}에서 )칼이 오갔다. 남은 쪽은 {killer:이었다/였다}.",
        ),
        listOf(
            "{killer:이/가} (?zone:{zone}에서 ){victim:을/를} 쓰러뜨렸어요.(?assists: {assists:이/가} 도왔고요.)",
            "(?zone:{zone}에서 ){victim:과/와} {killer:이/가} 맞붙었고, {killer:이/가} 이겼어요.",
        ))
    val pvp_revenge = p("pvp.revenge",
        listOf("{killer:이/가} {victim:을/를} 쓰러뜨렸다. [며칠 전 일을 되갚은 셈이다|빚은 이렇게 갚았다].", "{killer:은/는} 잊지 않았다. 이번엔 {victim:이/가} 쓰러졌다."),
        listOf("{killer:이/가} {victim:을/를} 쓰러뜨렸어요. 며칠 전 일을 기억하고 있었던 것 같아요."))
    val pvp_rivalry = p("pvp.rivalry",
        listOf("{killer:과/와} {victim} 사이는 오늘도 조용하지 않았다. 이번엔 {killer:이/가} 이겼다."),
        listOf("{killer:과/와} {victim:은/는} 또 맞붙었어요. 이번엔 {killer:이/가} 이겼어요. 이쯤 되면 오래된 사이예요."))
    val pvp_first = p("pvp.first",
        listOf("처음으로 사람끼리 칼을 맞댔다. {killer:이/가} {victim:을/를} 쓰러뜨렸다."),
        listOf("이 서버의 첫 PvP였어요. {killer:이/가} {victim:을/를} 쓰러뜨렸어요."))

    val revenant_self = p("revenant.self",
        listOf("{owner:은/는} 자기 망자를 직접 찾아가 짐을 되찾았다.", "망자는 {owner:이/가} 직접 찾아가 정리했다. 잃은 건 없다.", "짐은 무사했다. {owner:이/가} 자기 망자를 직접 되찾았다."),
        listOf("{owner:은/는} 자기 망자를 직접 찾아가 짐을 되찾았어요.", "망자는 {owner:이/가} 직접 찾아가 되찾았어요. 잃은 건 없어요."))
    val revenant_mate = p("revenant.mate",
        listOf("{owner:은/는} {by:과/와} 함께 망자를 찾아가 짐을 되찾았다.", "{by:이/가} 주변 좀비를 막는 사이 {owner:이/가} 자기 망자를 정리했다."),
        listOf("{owner:은/는} {by:과/와} 함께 망자를 찾아가 짐을 되찾았어요.", "{by:이/가} 곁을 지키는 동안 {owner:이/가} 자기 망자를 정리했어요. 좋은 이웃이에요."))

    val alpha = p("alpha",
        listOf("알파가 쓰러졌다. {killers}의 몫이다.", "(?zone:{zone}의 )알파를 {killers:이/가} 잡았다."),
        listOf("{killers:이/가} (?zone:{zone}에서 )알파를 쓰러뜨렸어요."))
    val alpha_first = p("alpha.first",
        listOf("처음으로 알파가 쓰러졌다. {killers:이/가} 해냈다."),
        listOf("서버에서 처음으로 알파가 쓰러졌어요. {killers:이/가} 해냈어요."))
    val nest = p("nest",
        listOf("(?zone:{zone}의 )감염 둥지 하나가 무너졌다. {participants:이/가} 부쉈다.", "{participants:이/가} 감염 둥지를 부쉈다. 그쪽 공기가 조금 맑아졌다."),
        listOf("{participants:이/가} 감염 둥지를 하나 부쉈어요. 그 근처는 당분간 조용할 거예요."))

    val bloodmoon_won = p("bloodmoon.won",
        listOf("블러드문이 떴다. {target:은/는} 버텼다. {participants:이/가} 끝까지 자리를 지켰다.", "붉은 밤이었다. {participants:이/가} {target:을/를} 지켜 냈다."),
        listOf("블러드문이 떴어요. {participants:이/가} {target:을/를} 끝까지 지켜 냈어요."))
    val bloodmoon_lost = p("bloodmoon.lost",
        listOf("블러드문이 떴고, {target:은/는} 버티지 못했다.", "붉은 밤이었다. {target:이/가} 뚫렸다. {participants:은/는} 마지막까지 있었다."),
        listOf("블러드문에 {target:이/가} 뚫렸어요. 다음엔 사람이 조금 더 모이면 좋겠어요."))
    val bloodmoon_won_after = p("bloodmoon.won.after",
        listOf("{target:은/는} 버텼다. {participants:이/가} 끝까지 자리를 지켰다.", "{participants:이/가} {target:을/를} 지켜 냈다."),
        listOf("{participants:이/가} {target:을/를} 끝까지 지켜 냈어요."))
    val bloodmoon_lost_after = p("bloodmoon.lost.after",
        listOf("{target:은/는} 버티지 못했다.", "{target:이/가} 뚫렸다. {participants:은/는} 마지막까지 있었다."),
        listOf("{target:이/가} 뚫렸어요. 다음엔 사람이 조금 더 모이면 좋겠어요."))
    val bloodmoon_first = p("bloodmoon.first",
        listOf("첫 블러드문이었다."),
        listOf("첫 블러드문이었어요."))
    val bloodmoon_wait = p("bloodmoon.wait",
        listOf("한동안 없던 블러드문이었다."),
        listOf("오랜만의 블러드문이었어요."))

    val landmark = p("landmark",
        listOf("{landmark:은/는} 이제 {nation}의 것이다.(?from: {from}에게서 빼앗았다.)"),
        listOf("{nation:이/가} {landmark:을/를} 가져갔어요.(?from: 원래는 {from}의 거점이었어요.)"))
    val landmark_first = p("landmark.first",
        listOf("처음으로 거점에 주인이 생겼다. {landmark}, {nation}."),
        listOf("처음으로 거점에 주인이 생겼어요. {landmark:은/는} {nation}의 것이 됐어요."))

    val advancement = p("advancement",
        listOf("{person:은/는} 생존 도감의 {title:을/를} 채웠다.", "{person:이/가} 도감에 {title:을/를} 적었다."),
        listOf("{person:이/가} 생존 도감에서 {title:을/를} 채웠어요."))
    val advancement_rare = p("advancement.rare",
        listOf("{person:이/가} 도감의 {title:을/를} 채웠다. 아직 아무도 못 채운 칸이었다."),
        listOf("{person:이/가} 생존 도감의 {title:을/를} 채웠어요. 아직 아무도 채우지 못한 칸이었어요."))
    val advancement_group = p("advancement.group",
        listOf("도감 칸이 몇 개 채워졌다. {list}.", "도감에도 진척이 있었다. {list}."),
        listOf("생존 도감에도 진척이 있었어요. {list}.", "생존 도감 칸도 몇 개 채워졌어요. {list}."))

    val starforce = p("starforce",
        listOf("{person:이/가} {item:을/를} {star}성까지 올렸다.", "{person}의 {item:이/가} {star}성이 됐다."),
        listOf("{person:이/가} {item:을/를} {star}성까지 강화했어요."))
    val starforce_first = p("starforce.first",
        listOf("{person:이/가} {item:을/를} {star}성까지 올렸다. 서버에서 처음이다."),
        listOf("{person:이/가} {item:을/를} {star}성까지 강화했어요. 서버에서 처음이에요."))
    val shard = p("shard",
        listOf("{person:이/가} 백신 조각을 하나 주웠다."),
        listOf("{person:이/가} 백신 조각을 하나 찾았어요."))

    val nation_top = p("nation.top",
        listOf("어제 좀비를 가장 많이 잡은 건 {nation:이었다/였다}.", "좀비를 제일 많이 잡은 나라는 {nation:이었다/였다}."),
        listOf("어제 좀비를 가장 많이 잡은 나라는 {nation:이에요/예요}."))
    val closer_next = p("closer.next",
        listOf("내일은 감염도 {b} 근처에서 시작한다.", "내일도 {b} 언저리에서 시작한다."),
        listOf("내일은 감염도 {b} 근처에서 시작할 거예요."))
    val closer_nobloodmoon = p("closer.nobloodmoon",
        listOf("블러드문은 [아직이다|아직 오지 않았다].", "붉은 밤은 아직 오지 않았다."),
        listOf("블러드문은 아직 오지 않았어요."))
    val closer_shards = p("closer.shards",
        listOf("백신 조각은 모두 {n}개다.", "모인 백신 조각은 {n}개."),
        listOf("백신 조각은 지금까지 {n}개 모였어요."))

    /** 사고 동사. [평서, 해요체]. */
    val accidentVerbs: Map<Accident, Pair<List<String>, String>> = mapOf(
        Accident.FALL to (listOf("발을 헛디뎠다", "높은 데서 떨어졌다") to "높은 곳에서 떨어졌어요"),
        Accident.LAVA to (listOf("용암에 빠졌다") to "용암에 빠졌어요"),
        Accident.DROWN to (listOf("물속에서 숨이 다했다", "물에서 나오지 못했다") to "물속에서 숨이 다했어요"),
        Accident.FIRE to (listOf("불길을 피하지 못했다") to "불길을 피하지 못했어요"),
        Accident.STARVE to (listOf("굶주림을 버티지 못했다") to "배고픔을 버티지 못했어요"),
        Accident.EXPLOSION to (listOf("폭발에 휘말렸다") to "폭발에 휘말렸어요"),
        Accident.OTHER to (listOf("사고로 쓰러졌다") to "사고로 쓰러졌어요"),
    )
}
