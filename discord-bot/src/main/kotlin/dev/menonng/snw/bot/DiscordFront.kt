package dev.menonng.snw.bot

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.GatewayIntent

/**
 * 디스코드 쪽(README '디스코드 봇'): 가입(#등록 의 [등록하기]·[연동 코드 입력]), 역할(미등록·생존자), 별명, #알림·#서버-상태,
 * 슬래시 명령(/정보, /닉네임변경, /서버상태, /서버공지). 상호평가·국가 추첨은 다음 단계(peer-rating.md).
 * 마크 이름은 디스코드에 드러내지 않는다(별명 = 커스텀 닉네임).
 */
class DiscordFront(private val cfg: BotConfig, token: String) : ListenerAdapter(), Front {
    lateinit var hub: ModHub
    val jda: JDA = JDABuilder.createLight(token, GatewayIntent.GUILD_MEMBERS, GatewayIntent.DIRECT_MESSAGES).addEventListeners(this).build()
    /** 상호평가·국가 추첨 명령(SeasonFront 가 채운다). */
    var extraCommands: List<net.dv8tion.jda.api.interactions.commands.build.CommandData> = emptyList()
        set(value) { field = value; if (jda.status == JDA.Status.CONNECTED) registerCommands() }   // 이미 접속했으면 다시 등록(순서 경쟁 방지)
    /** 설정의 id 가 숫자가 아니면(예시 문구가 남아 있으면) 오류 대신 없는 것으로 본다. 준비될 때 콘솔에 알린다. */
    fun guild() = cfg.guildId.trim().toLongOrNull()?.let { jda.getGuildById(it) }
    fun channel(id: String) = id.trim().toLongOrNull()?.let { guild()?.getTextChannelById(it) }
    fun post(id: String, text: String) { channel(id)?.sendMessage(text.take(1990))?.queue() }

    // ------------------------------------------------------------------ Front
    override fun alert(text: String) { if (cfg.alerts) post(cfg.channels.alerts, text) }
    override fun serverStatus(online: Boolean) = post(cfg.channels.status, if (online) "🟢 마인크래프트 서버가 켜졌습니다" else "🔴 마인크래프트 서버가 꺼졌습니다")
    override fun linked(discordId: String, nickname: String) {
        val g = guild() ?: return
        g.retrieveMemberById(discordId).queue({ m ->
            cfg.roles.unregistered.takeIf { it.isNotBlank() }?.let { g.getRoleById(it) }?.let { g.removeRoleFromMember(m, it).queue() }
            cfg.roles.survivor.takeIf { it.isNotBlank() }?.let { g.getRoleById(it) }?.let { g.addRoleToMember(m, it).queue() }
            if (g.selfMember.canInteract(m)) g.modifyNickname(m, nickname).queue({}, {})
        }, {})
    }
    override fun review(discordId: String, nickname: String, reasons: List<String>) =
        post(cfg.channels.admin, "닉네임 확인 요청: `$nickname` — ${reasons.joinToString(", ")} (사용자 <@$discordId>)")

    fun daily(text: String) = post(cfg.channels.diary, text)
    fun status(text: String) = post(cfg.channels.status, text)

    // ------------------------------------------------------------------ 준비
    override fun onReady(event: ReadyEvent) {
        println("[snw-bot] 디스코드 접속: ${event.jda.selfUser.name} (서버 ${event.jda.guilds.size}곳)")
        val g = guild()
        if (g == null) System.err.println("[snw-bot] ⚠ guildId(${cfg.guildId}) 서버를 찾지 못했습니다. 봇이 그 서버에 초대됐는지, 서버 ID 가 맞는지 확인하세요. 들어가 있는 서버: " +
            event.jda.guilds.joinToString { "${it.name}(${it.id})" })
        else {
            println("[snw-bot] 서버: ${g.name}")
            val c = cfg.channels
            for ((name, id) in listOf("register" to c.register, "notice" to c.notice, "status" to c.status, "diary" to c.diary, "alerts" to c.alerts, "admin" to c.admin)) {
                if (id.isBlank()) continue
                println("[snw-bot]   채널 $name: " + (channel(id)?.let { "#${it.name} 확인" } ?: "⚠ 찾지 못함($id) — 채널 ID 를 다시 복사하세요"))
            }
        }
        registerCommands()
        // #등록 안내(한 번만)
        val reg = channel(cfg.channels.register) ?: return
        if (hub.let { true } && (reg.latestMessageIdLong == 0L)) reg.sendMessage("환영합니다! 아래 [등록하기]로 닉네임을 정하고, 마크 서버에 접속하면 뜨는 6자리 코드를 [연동 코드 입력]에 넣으세요.")
            .setComponents(ActionRow.of(Button.primary("snw:register", "등록하기"), Button.secondary("snw:link", "연동 코드 입력"), Button.secondary("snw:buddy", "단짝 지목"))).queue()
    }

    @Synchronized fun registerCommands() {
        val g = guild() ?: run { System.err.println("[snw-bot] guildId 를 찾을 수 없습니다"); return }
        g.updateCommands().addCommands(
            Commands.slash("정보", "생존자 정보(마지막 스냅샷)").addOption(OptionType.USER, "유저", "볼 사람", false),
            Commands.slash("닉네임변경", "새 닉네임을 신청합니다").addOption(OptionType.STRING, "닉네임", "2~10자", true),
            Commands.slash("서버상태", "접속자·봇 연결").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
            Commands.slash("서버공지", "디스코드 #공지 + 게임 안 방송").addOption(OptionType.STRING, "내용", "공지", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
            *extraCommands.toTypedArray(),
        ).queue({ println("[snw-bot] 슬래시 명령 ${it.size}개 등록") }, { System.err.println("[snw-bot] 명령 등록 실패: $it") })
    }

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        cfg.roles.unregistered.takeIf { it.isNotBlank() }?.let { event.guild.getRoleById(it) }?.let { event.guild.addRoleToMember(event.member, it).queue() }
    }

    // ------------------------------------------------------------------ 가입
    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.componentId !in setOf("snw:register", "snw:link")) return
        guard(event, "버튼 ${event.componentId}") { button(event) }
    }

    private fun button(event: ButtonInteractionEvent) {
        when (event.componentId) {
            "snw:register" -> event.replyModal(Modal.create("snw:register", "생존자 등록").addComponents(
                Label.of("마크 이름(Java 계정)", TextInput.create("mc", TextInputStyle.SHORT).setRequiredRange(3, 16).build()),
                Label.of("커스텀 닉네임", "2~10자. 실명·학교·나이·연락처·사는 곳은 넣지 마세요", TextInput.create("nick", TextInputStyle.SHORT).setRequiredRange(2, 10).build()),
                Label.of("주로 접속할 시간대(선택)", TextInput.create("when", TextInputStyle.SHORT).setRequired(false).setPlaceholder("예: 평일 밤, 주말 저녁").build()),
                Label.of("일주일 예상 플레이 시간(선택)", TextInput.create("hours", TextInputStyle.SHORT).setRequired(false).setPlaceholder("~3h / 3~7h / 7~14h / 14h~").build()),
                Label.of("확인: 위 내용에 개인정보가 없으면 '동의'", TextInput.create("agree", TextInputStyle.SHORT).setRequiredRange(2, 2).build()),
            ).build()).queue()
            "snw:link" -> event.replyModal(Modal.create("snw:link", "연동 코드 입력").addComponents(
                Label.of("마크 서버 접속 화면의 6자리 코드", TextInput.create("code", TextInputStyle.SHORT).setRequiredRange(6, 6).build()),
            ).build()).queue()
        }
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (event.modalId !in setOf("snw:register", "snw:link")) return
        guard(event, "모달 ${event.modalId}") { modal(event) }
    }

    private fun modal(event: ModalInteractionEvent) {
        fun v(id: String) = event.values.firstOrNull { it.customId == id }?.asString?.trim().orEmpty()
        val uid = event.user.id
        when (event.modalId) {
            "snw:register" -> {
                if (v("agree") != "동의") { event.reply("'동의'라고 적어 주세요. 닉네임에 개인정보가 없는지 꼭 확인해 주세요.").setEphemeral(true).queue(); return }
                event.deferReply(true).queue()
                val r = hub.register(uid, v("mc"), v("nick"), showName = true, funnyOptIn = false, availability = v("when").ifBlank { null }, weeklyBand = v("hours").ifBlank { null }, lookup = MojangLookup::uuidOf)
                val msg = when (r) {
                    is ModHub.RegisterResult.Ok -> "등록했습니다: **${r.nickname}**. 이제 마크 서버에 접속하면 6자리 코드가 뜹니다. [연동 코드 입력]에 넣어 주세요."
                    is ModHub.RegisterResult.Review -> "등록했습니다: **${r.nickname}**. 닉네임을 운영진이 한 번 확인합니다(${r.reasons.joinToString(", ")}). 그동안 연동은 진행할 수 있습니다."
                    is ModHub.RegisterResult.Rejected -> "이 닉네임은 쓸 수 없습니다: ${r.reasons.joinToString(", ")}"
                    ModHub.RegisterResult.UnknownMinecraftName -> "그 마크 이름을 찾지 못했습니다. Java 계정 이름을 확인해 주세요."
                    ModHub.RegisterResult.MinecraftTaken -> "그 마크 계정은 이미 다른 사람이 등록했습니다. 운영진에게 문의해 주세요."
                }
                event.hook.sendMessage(msg).queue()
            }
            "snw:link" -> {
                val msg = when (val r = hub.redeem(uid, v("code"))) {
                    ModHub.LinkResult.NotRegistered -> "먼저 [등록하기]로 닉네임을 정해 주세요."
                    ModHub.LinkResult.BadCode -> "코드가 맞지 않거나 10분이 지났습니다. 마크 서버에 다시 접속해 새 코드를 받아 주세요."
                    ModHub.LinkResult.Mismatch -> "등록한 마크 계정과 코드의 계정이 다릅니다."
                    is ModHub.LinkResult.Linked -> "연동되었습니다, **${r.nickname}** 님. 이제 마크 서버에 들어갈 수 있습니다."
                }
                event.reply(msg).setEphemeral(true).queue()
            }
        }
    }

    // ------------------------------------------------------------------ 명령
    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name !in setOf("정보", "닉네임변경", "서버상태", "서버공지")) return
        guard(event, "/${event.fullCommandName}") { slash(event) }
    }

    private fun slash(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "정보" -> {
                val target = event.getOption("유저")?.asUser ?: event.user
                val u = hub.userOf(target.id)
                event.reply(if (u == null) "등록되지 않은 사용자입니다." else hub.infoLine(u)).setEphemeral(true).queue()
            }
            "닉네임변경" -> {
                val nick = event.getOption("닉네임")?.asString ?: return
                channel(cfg.channels.admin)?.sendMessage("닉네임 변경 신청: <@${event.user.id}> → `$nick`")
                    ?.setComponents(ActionRow.of(Button.success("nick:ok:${event.user.id}", "승인"), Button.danger("nick:no:${event.user.id}", "거절")))?.queue()
                event.reply("운영진에게 신청했습니다. 승인되면 바뀝니다.").setEphemeral(true).queue()
                hub.queueNicknameChange(event.user.id, nick)
            }
            "서버상태" -> event.reply("마크 서버 ${if (hub.connected) "연결됨" else "꺼짐"} · 접속자 ${hub.online.size}명 · 확인 대기 ${hub.pendingCount()}건").setEphemeral(true).queue()
            "서버공지" -> {
                val text = event.getOption("내용")?.asString ?: return
                post(cfg.channels.notice, "📢 $text"); hub.announce(text)
                event.reply("공지했습니다.").setEphemeral(true).queue()
            }
        }
    }

    fun shutdown() = jda.shutdown()
}
