package dev.menonng.snw.bot

import dev.menonng.snw.logic.discord.TeamDraw
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.radiogroup.RadioGroup
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.modals.Modal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 상호평가·국가 추첨식의 디스코드 화면(peer-rating.md). 규칙은 RatingService·TeamService(그 아래 logic 의 PeerRating·TeamDraw).
 * - /평가 시작·현황·종료·결과, DM 의 [평가하기] → 대상 한 명당 모달(항목 = 라디오 그룹, ⭐1~5·잘 모름)
 * - /팀 팀장·추첨·확정·최적화(관리자), /교환(모두), #등록 의 [단짝 지목](유저 선택 메뉴, 지목 내용은 본인과 봇만)
 * - 닉네임 변경 승인 버튼(운영진 채널)
 */
class SeasonFront(private val cfg: BotConfig, private val front: DiscordFront, private val db: Db, private val hub: ModHub,
                  private val ratings: RatingService, private val teams: TeamService,
                  private val kit: TestKit? = null) : ListenerAdapter() {
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val admin = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)

    fun commands(): List<CommandData> = listOf(
        Commands.slash("평가", "상호평가 라운드(관리자)").setDefaultPermissions(admin).addSubcommands(
            SubcommandData("시작", "모든 생존자가 서로를 평가합니다").addOption(OptionType.INTEGER, "마감시간", "몇 시간 뒤 마감(기본 48)", false)
                .addOption(OptionType.STRING, "항목", "쉼표로 최대 5개(기본: 전투,건축,생존,협동,열정)", false),
            SubcommandData("현황", "누가 몇 명 평가했는지(점수는 보이지 않음)"),
            SubcommandData("종료", "마감하고 집계"),
            SubcommandData("결과", "본인에게 DM 으로 결과").addOption(OptionType.BOOLEAN, "공개", "#공지에 항목별 상위 3명", false),
        ),
        Commands.slash("팀", "국가 추첨식(관리자)").setDefaultPermissions(admin).addSubcommands(
            SubcommandData("팀장", "팀장(원수) 3명 확정(관리자)").addOption(OptionType.USER, "첫째", "팀장", true).addOption(OptionType.USER, "둘째", "팀장", true).addOption(OptionType.USER, "셋째", "팀장", true),
            SubcommandData("추첨", "시드 해시 공개 → 한 명씩 공개 → 시드 공개(관리자)"),
            SubcommandData("확정", "국가 역할·채널을 만들고 게임에 배정(관리자)"),
            SubcommandData("최적화", "예비: 최적화 편성 상위 3안(관리자)"),
        ),
        // 디스코드는 권한을 명령 단위로만 숨길 수 있어서, 모두가 쓰는 맞교환은 /팀 에서 떼어 따로 둔다
        Commands.slash("교환", "추첨 뒤 24시간: 다른 국가 한 명과 맞교환 신청").addOption(OptionType.USER, "상대", "바꿀 사람", true),
    ) + (if (kit != null) listOf(Commands.slash("테스트", "시험 모드: 가짜 멤버로 혼자 시험(관리자)").setDefaultPermissions(admin).addSubcommands(
        SubcommandData("나", "나를 참가자로(게임 연동 없이)"),
        SubcommandData("더미", "가짜 멤버를 n 명까지 채웁니다").addOption(OptionType.INTEGER, "수", "기본 11, 최대 ${TestKit.MAX}", false),
        SubcommandData("평가채우기", "가짜 멤버들이 모두를(나 포함) 평가합니다"),
        SubcommandData("마감당기기", "마감을 n 분 뒤로(독촉 DM → 자동 마감 시험)").addOption(OptionType.INTEGER, "분", "기본 2", false),
        SubcommandData("단짝", "가짜 멤버끼리 단짝 두 쌍"),
        SubcommandData("팀장자동", "예상 시간 상위 절반에서 팀장 3명").addOption(OptionType.BOOLEAN, "나포함", "나를 팀장에 넣기", false),
        SubcommandData("교환", "더미 n 번과 나의 맞교환(양쪽 신청)").addOption(OptionType.INTEGER, "번호", "더미 번호", true),
        SubcommandData("목록", "가짜 멤버와 국가 배정"),
        SubcommandData("정리", "가짜 멤버·시험 기록 삭제").addOption(OptionType.BOOLEAN, "국가채널", "봇이 만든 국가 역할·채널도 삭제", false),
    )) else emptyList())

    private fun isAdmin(e: SlashCommandInteractionEvent) = e.member?.hasPermission(Permission.MANAGE_SERVER) == true

    private fun dm(user: User, text: String, vararg buttons: Button) {
        user.openPrivateChannel().flatMap { ch -> if (buttons.isEmpty()) ch.sendMessage(text) else ch.sendMessage(text).setComponents(ActionRow.of(buttons.toList())) }.queue({}, {})
    }
    private fun dmId(id: String, text: String, vararg buttons: Button) {
        if (TestKit.isDummy(id)) return                     // 가짜 멤버는 디스코드 계정이 없다
        front.jda.retrieveUserById(id).queue({ dm(it, text, *buttons) }, {})
    }

    // ------------------------------------------------------------------ 명령
    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name !in setOf("평가", "팀", "교환", "테스트")) return
        guard(event, "/${event.fullCommandName}") {
            when (event.name) {
                "평가" -> rating(event)
                "팀" -> team(event)
                "교환" -> swap(event)
                "테스트" -> test(event)
            }
        }
    }

    private fun rating(e: SlashCommandInteractionEvent) {
        if (!isAdmin(e)) { e.reply("관리자만 쓸 수 있습니다.").setEphemeral(true).queue(); return }
        when (e.subcommandName) {
            "시작" -> {
                val hours = e.getOption("마감시간")?.asInt ?: 48
                val r = ratings.start(e.getOption("항목")?.asString?.split(','), hours)
                if (r == null) { e.reply("이미 열린 라운드가 있습니다.").setEphemeral(true).queue(); return }
                for (u in ratings.participants()) dmId(u.discordId, "상호평가가 시작되었습니다. 평가할 사람 ${ratings.targetsFor(u.discordId).size}명 · 마감 ${hours}시간 뒤.\n잘 모르는 사람은 '잘 모름'을 고르세요. 누가 몇 점을 줬는지는 공개되지 않습니다.", Button.primary("rate:next", "평가하기"))
                front.post(cfg.channels.notice, "⭐ 상호평가가 시작되었습니다. DM 의 [평가하기]로 참여해 주세요(마감 ${hours}시간 뒤). 평가는 디스코드에서만 하고, 게임 안 /평가 는 내 진행과 결과를 보는 창입니다.")
                e.reply("라운드 ${r.id} 를 시작했습니다(항목 ${r.categories.joinToString { ratings.label(it) }}).").setEphemeral(true).queue()
            }
            "현황" -> {
                val st = ratings.status()
                e.reply(if (st.isEmpty()) "열린 라운드가 없습니다." else st.joinToString("\n") { (n, d, t) -> "$n ${d}/${t}" }).setEphemeral(true).queue()
            }
            "종료" -> {
                val r = ratings.close() ?: run { e.reply("열린 라운드가 없습니다.").setEphemeral(true).queue(); return }
                front.post(cfg.channels.admin, "상호평가 라운드 ${r.id} 마감. 이상치 점검:\n" + ratings.outlierReport(r))
                e.reply("마감했습니다. /평가 결과 로 본인들에게 보냅니다.").setEphemeral(true).queue()
            }
            "결과" -> {
                val r = db.lastClosedRound() ?: run { e.reply("마감된 라운드가 없습니다.").setEphemeral(true).queue(); return }
                for (u in ratings.participants()) ratings.resultFor(r, u.discordId)?.let { dmId(u.discordId, it) }
                ratings.release(r)          // 게임 안 /평가 창에도 내 점수가 보이게
                if (e.getOption("공개")?.asBoolean == true) front.post(cfg.channels.notice, ratings.publicSummary(r))
                e.reply("결과를 보냈습니다.").setEphemeral(true).queue()
            }
        }
    }

    private fun team(e: SlashCommandInteractionEvent) {
        if (!isAdmin(e)) { e.reply("관리자만 쓸 수 있습니다.").setEphemeral(true).queue(); return }
        when (e.subcommandName) {
            "팀장" -> {
                val ids = listOf("첫째", "둘째", "셋째").mapNotNull { e.getOption(it)?.asUser?.id }
                val err = teams.setLeaders(ids)
                if (err != null) { e.reply(err).setEphemeral(true).queue(); return }
                front.post(cfg.channels.notice, "👑 팀장(원수)이 정해졌습니다: " + ids.mapNotNull { db.user(it)?.nickname }.joinToString(", "))
                e.reply("팀장을 확정했습니다.").setEphemeral(true).queue()
            }
            "추첨" -> {
                val (hash, res) = teams.draw() ?: run { e.reply("팀장 3명을 먼저 정해 주세요(또는 이미 확정됨).").setEphemeral(true).queue(); return }
                e.reply("추첨을 시작합니다.").setEphemeral(true).queue()
                reveal(hash, res)
            }
            "확정" -> {
                val assign = teams.confirm() ?: run { e.reply("확정할 추첨 결과가 없습니다(또는 이미 확정됨).").setEphemeral(true).queue(); return }
                e.reply("확정했습니다. 국가 역할·채널을 만듭니다.").setEphemeral(true).queue()
                sched.execute { createNations(assign) }
            }
            "최적화" -> {
                e.deferReply(true).queue()
                sched.execute { e.hook.sendMessage(teams.optimizeSummary().take(1900)).queue() }
            }
        }
    }

    private fun test(e: SlashCommandInteractionEvent) {
        val kit = kit ?: run { e.reply("시험 모드가 꺼져 있습니다(config 의 testMode).").setEphemeral(true).queue(); return }
        if (!isAdmin(e)) { e.reply("관리자만 쓸 수 있습니다.").setEphemeral(true).queue(); return }
        val me = e.user.id
        fun say(t: String) = e.reply("🧪 $t".take(1990)).setEphemeral(true).queue()
        when (e.subcommandName) {
            "나" -> say(if (kit.linkSelf(me)) "참가자로 넣었습니다(닉네임 ${db.user(me)?.nickname}, 예상 시간 14h~). 게임 연동 없이 디스코드 흐름만 시험합니다." else "이미 연동된 참가자입니다.")
            "더미" -> { val n = e.getOption("수")?.asInt ?: 11; val made = kit.addDummies(n); say("가짜 멤버를 ${made}명 만들었습니다(지금 ${kit.dummies().size}명). 참가자 ${ratings.participants().size}명.") }
            "평가채우기" -> say(kit.fillRatings()?.let { "가짜 멤버들이 ${it}건 평가했습니다. 더미01 은 한 사람에게 1점을 몰아줘서 /평가 종료 때 이상치 경고가 나와야 합니다." } ?: "열린 라운드가 없습니다. /평가 시작 먼저.")
            "마감당기기" -> { val m = e.getOption("분")?.asInt ?: 2; say(kit.pullDeadline(m)?.let { "마감을 ${m}분 뒤로 당겼습니다. 30초 안에 미완료자 독촉 DM, ${m}분 뒤 자동 마감과 운영진 채널 알림이 와야 합니다." } ?: "열린 라운드가 없습니다.") }
            "단짝" -> { val p = kit.pairBuddies(); say(if (p.isEmpty()) "가짜 멤버가 2명 이상 있어야 합니다." else "단짝: " + p.joinToString(", ") { (a, b) -> "${db.user(a)?.nickname}↔${db.user(b)?.nickname}" }) }
            "팀장자동" -> {
                val err = kit.autoLeaders(if (e.getOption("나포함")?.asBoolean == true) me else null)
                if (err != null) say(err) else {
                    front.post(cfg.channels.notice, "👑 팀장(원수)이 정해졌습니다: " + teams.state.leaders.mapNotNull { db.user(it)?.nickname }.joinToString(", "))
                    say("팀장: " + teams.state.leaders.mapNotNull { db.user(it)?.nickname }.joinToString(", ") + ". 이제 /팀 추첨.")
                }
            }
            "교환" -> {
                val msg = kit.swapWith(e.getOption("번호")!!.asInt, me)
                if (msg == "교환했습니다.") front.post(cfg.channels.notice, "🔁 ${db.user(me)?.nickname} ↔ ${db.user(TestKit.idOf(e.getOption("번호")!!.asInt))?.nickname} 국가 맞교환")
                say(msg)
            }
            "목록" -> {
                val ds = kit.dummies()
                val nat = { id: String -> teams.nationOf(id).takeIf { it >= 0 }?.let { " · " + teams.NATIONS[it].second } ?: "" }
                say(if (ds.isEmpty()) "가짜 멤버가 없습니다." else ds.joinToString("\n") { "${it.nickname} (${it.weeklyBand})" + nat(it.discordId) } + "\n나${nat(me)}")
            }
            "정리" -> {
                val text = kit.cleanup(me)
                val chans = e.getOption("국가채널")?.asBoolean == true
                say(text + if (chans) ", 국가 역할·채널 삭제 중" else "")
                if (chans) sched.execute { deleteNations() }
            }
        }
    }

    /** 시험 정리: 봇이 만든 국가 역할·카테고리·채널을 지운다(관리자가 고른 경우만). */
    private fun deleteNations() {
        val g = front.guild() ?: return
        for (n in db.nations()) {
            n.channels?.split(',')?.filter { it.isNotBlank() }?.forEach { id -> g.getGuildChannelById(id)?.delete()?.complete() }
            n.categoryId?.let { g.getCategoryById(it)?.delete()?.complete() }
            n.roleId?.let { g.getRoleById(it)?.delete()?.complete() }
            db.deleteNation(n.id)
        }
        front.post(cfg.channels.admin, "🧪 시험용 국가 역할·채널을 지웠습니다.")
    }

    private fun swap(e: SlashCommandInteractionEvent) {
        val other = e.getOption("상대")?.asUser ?: return
        val msg = teams.requestSwap(e.user.id, other.id)
        if (msg.startsWith("상대에게")) dmId(other.id, "${db.user(e.user.id)?.nickname} 님이 국가 맞교환을 신청했습니다. 원하면 /교환 으로 그 사람을 고르세요.")
        if (msg == "교환했습니다.") front.post(cfg.channels.notice, "🔁 ${db.user(e.user.id)?.nickname} ↔ ${db.user(other.id)?.nickname} 국가 맞교환")
        e.reply(msg).setEphemeral(true).queue()
    }

    /** 추첨식 공개: 시드 해시 → 10초마다 한 단위씩 → 시드와 공개 입력(순위 구간만). */
    private fun reveal(hash: String, res: TeamDraw.Result) {
        val names = teams.NATIONS.map { it.second }
        front.post(cfg.channels.notice, "🎲 국가 추첨식을 시작합니다. 시드의 SHA-256: `$hash`")
        var delay = 5L
        for ((t, unit) in res.order) {
            val who = unit.mapNotNull { db.user(it)?.nickname }
            val line = "🎲 ${names[t]} ← ${who.first()}" + (if (who.size > 1) " (단짝 ${who.drop(1).joinToString()}와 함께)" else "") +
                (if (unit.first() in teams.state.leaders) " 👑" else "")
            sched.schedule({ front.post(cfg.channels.notice, line) }, delay, TimeUnit.SECONDS)
            delay += 10
        }
        sched.schedule({
            front.post(cfg.channels.notice, "🎲 추첨 끝. 시드: `${teams.state.seed}` — 같은 입력과 시드로 누구나 결과를 재현할 수 있습니다.\n24시간 동안 /교환 으로 다른 국가 한 명과 서로 신청해 맞바꿀 수 있습니다.")
            front.post(cfg.channels.notice, "공개 입력(개인 점수 대신 순위 구간):\n" + teams.publicInput().take(1800))
        }, delay, TimeUnit.SECONDS)
    }

    /** 국가 역할·카테고리(#국가-채팅, #국가-알림, 🔊국가-음성)를 만들고 역할을 준다. */
    private fun createNations(assign: Map<String, Pair<String, String>>) {
        val g = front.guild() ?: return
        val everyone = g.publicRole
        for ((id, name, color) in teams.NATIONS) {
            val existing = db.nations().firstOrNull { it.id == id }
            val role = existing?.roleId?.let { g.getRoleById(it) } ?: g.createRole().setName(name).setColor(color).setMentionable(true).complete()
            val cat = existing?.categoryId?.let { g.getCategoryById(it) } ?: g.createCategory(name)
                .addPermissionOverride(everyone, emptyList(), listOf(Permission.VIEW_CHANNEL))
                .addPermissionOverride(role, listOf(Permission.VIEW_CHANNEL), emptyList()).complete()
            val chans = if (existing?.channels.isNullOrBlank()) listOf(
                g.createTextChannel("국가-채팅", cat).complete().id, g.createTextChannel("국가-알림", cat).complete().id, g.createVoiceChannel("국가-음성", cat).complete().id,
            ).joinToString(",") else existing!!.channels!!
            val leader = assign.entries.firstOrNull { it.value.first == id && it.value.second == "LEADER" }?.key
            db.putNation(Db.Nation(id, name, color, role.id, cat.id, chans, leader))
            for ((did, nr) in assign) if (nr.first == id && !TestKit.isDummy(did)) g.retrieveMemberById(did).queue({ m -> g.addRoleToMember(m, role).queue() }, {})
        }
        front.post(cfg.channels.notice, "🏳 국가가 확정되었습니다. 국가 채널이 열렸습니다. 국가 이름은 각 국가에서 정해 주세요.")
    }

    // ------------------------------------------------------------------ 버튼·모달·선택
    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.componentId
        if (id != "rate:next" && id != "snw:buddy" && !id.startsWith("nick:")) return
        guard(event, "버튼 " + (if (id.startsWith("nick:")) "닉네임 승인" else id)) { button(event) }
    }

    private fun button(event: ButtonInteractionEvent) {
        val id = event.componentId
        when {
            id == "rate:next" -> {
                val r = db.openRound() ?: run { event.reply("열린 평가가 없습니다.").queue(); return }
                val t = ratings.nextTarget(r, event.user.id) ?: run { event.reply("모두 평가했습니다. 고마워요! 마감 전까지는 다시 열어 고칠 수 없지만, 운영진에게 말하면 다시 보낼 수 있습니다.").queue(); return }
                event.replyModal(ratingModal(r, t)).queue()
            }
            id == "snw:buddy" -> event.reply("같이 하고 싶은 한 명을 고르세요. 서로 지목했을 때만 같은 국가로 묶이고, 누가 누구를 골랐는지는 공개하지 않습니다.")
                .setEphemeral(true).setComponents(ActionRow.of(EntitySelectMenu.create("snw:buddy:pick", EntitySelectMenu.SelectTarget.USER).setMaxValues(1).build())).queue()
            id.startsWith("nick:") -> {
                if (event.member?.hasPermission(Permission.MANAGE_SERVER) != true) { event.reply("운영진만 누를 수 있습니다.").setEphemeral(true).queue(); return }
                val (_, act, target) = id.split(':')
                val nick = db.get("nick.pending.$target")
                if (nick.isNullOrBlank()) { event.reply("이미 처리된 신청입니다.").setEphemeral(true).queue(); return }
                db.put("nick.pending.$target", "")
                if (act == "ok") {
                    val r = hub.changeNickname(target, nick)
                    val msg = when (r) { is ModHub.RegisterResult.Ok -> "승인: `${r.nickname}`"; is ModHub.RegisterResult.Rejected -> "규칙에 맞지 않아 반영하지 못했습니다: ${r.reasons.joinToString()}"; else -> "처리하지 못했습니다" }
                    if (r is ModHub.RegisterResult.Ok) { front.linked(target, r.nickname); dmId(target, "닉네임이 **${r.nickname}**(으)로 바뀌었습니다.") }
                    event.editMessage("닉네임 변경 신청 <@$target> → `$nick` — $msg").setComponents().queue()
                } else {
                    dmId(target, "닉네임 변경 신청이 반려되었습니다. 운영진에게 이유를 물어보세요.")
                    event.editMessage("닉네임 변경 신청 <@$target> → `$nick` — 거절").setComponents().queue()
                }
            }
        }
    }

    override fun onEntitySelectInteraction(event: EntitySelectInteractionEvent) {
        if (event.componentId != "snw:buddy:pick") return
        guard(event, "단짝 선택") { buddyPick(event) }
    }

    private fun buddyPick(event: EntitySelectInteractionEvent) {
        val pick = event.mentions.users.firstOrNull() ?: return
        if (pick.id == event.user.id) { event.reply("자기 자신은 고를 수 없습니다.").setEphemeral(true).queue(); return }
        if (db.user(pick.id)?.linkedAt == null) { event.reply("그 사람은 아직 연동하지 않았습니다.").setEphemeral(true).queue(); return }
        db.setBuddy(event.user.id, pick.id)
        event.reply("지목했습니다. 상대도 나를 고르면 같은 국가로 묶입니다.").setEphemeral(true).queue()
    }

    private fun ratingModal(r: Db.Round, t: Db.User): Modal {
        val b = Modal.create("rate:${r.id}:${t.discordId}", "${t.nickname} 평가".take(45))
        for (c in r.categories) {
            val g = RadioGroup.create("c:$c")
            for (s in 1..5) g.addOption("⭐".repeat(s), s.toString())
            g.addOption("잘 모름", "0")
            b.addComponents(Label.of(ratings.label(c), g.build()))
        }
        return b.build()
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (!event.modalId.startsWith("rate:")) return
        guard(event, "평가 모달") { rateModal(event) }
    }

    private fun rateModal(event: ModalInteractionEvent) {
        val (_, rid, target) = event.modalId.split(':')
        val r = db.openRound()
        if (r == null || r.id.toString() != rid) { event.reply("이 평가는 이미 마감되었습니다.").queue(); return }
        val scores = r.categories.associateWith { c -> event.values.firstOrNull { it.customId == "c:$c" }?.asString?.toIntOrNull()?.takeIf { it in 1..5 } }
        ratings.submit(event.user.id, target, scores)
        val (done, total) = ratings.progress(r, event.user.id)
        val more = ratings.nextTarget(r, event.user.id) != null
        val reply = event.reply("평가했습니다. 진행 $done / $total")
        if (more) reply.setComponents(ActionRow.of(Button.primary("rate:next", "다음 사람"))).queue() else reply.queue()
    }

    /** 30초마다(Main): 마감 12시간 전 미완료자 독촉 한 번, 마감 시각이 지나면 자동 마감. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val r = db.openRound() ?: return
        if (!r.reminded && now >= r.deadline - 12 * 3_600_000L) {
            db.markReminded(r.id)
            for (u in ratings.participants()) { val (d, t) = ratings.progress(r, u.discordId); if (d < t) dmId(u.discordId, "상호평가 마감 12시간 전입니다. 진행 $d / $t", Button.primary("rate:next", "이어서 평가하기")) }
        }
        if (now >= r.deadline) {
            ratings.close()
            front.post(cfg.channels.admin, "상호평가 라운드 ${r.id} 가 시간 만료로 마감되었습니다. /평가 결과 로 보냅니다.\n" + ratings.outlierReport(r))
        }
    }
}
