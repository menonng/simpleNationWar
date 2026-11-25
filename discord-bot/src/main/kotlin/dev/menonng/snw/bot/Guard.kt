package dev.menonng.snw.bot

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

/**
 * 디스코드 상호작용 안전망: 처리 중 오류가 나도 "애플리케이션이 응답하지 않았어요" 대신 오류를 알리고 콘솔에 남긴다.
 * 받은 상호작용도 한 줄씩 콘솔에 찍어(명령 이름만, 사람 정보 없음) 봇이 이벤트를 받는지 바로 알 수 있게 한다.
 */
internal fun guard(event: IReplyCallback, what: String, body: () -> Unit) {
    println("[snw-bot] 받음: $what")
    try {
        body()
    } catch (t: Throwable) {
        System.err.println("[snw-bot] 처리 오류($what): $t")
        t.printStackTrace()
        val msg = "⚠ 처리 중 오류가 났습니다(${t.javaClass.simpleName}). 봇 창의 로그를 확인해 주세요."
        runCatching {
            if (event.isAcknowledged) event.hook.sendMessage(msg).setEphemeral(true).queue()
            else event.reply(msg).setEphemeral(true).queue()
        }
    }
}
