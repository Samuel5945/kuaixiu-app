package local.kuaixiu.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * App 级时间入口：默认东八区，KxStore 加载配置后更新。
 * 避免 CalendarWriter 与 KxStore 互相引用形成循环依赖。
 */
object Time {

    @Volatile
    var defaultZone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun zone(): ZoneId = defaultZone

    fun now(): ZonedDateTime = ZonedDateTime.now(zone())

    fun today(): LocalDate = now().toLocalDate()

    fun zoned(date: LocalDate, time: java.time.LocalTime): ZonedDateTime =
        ZonedDateTime.of(date, time, zone())

    fun zoned(dateTime: LocalDateTime): ZonedDateTime = dateTime.atZone(zone())
}
