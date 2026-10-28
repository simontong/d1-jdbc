package nz.co.simontong.d1jdbc

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

// Formats follow SQLite's date functions: https://sqlite.org/lang_datefunc.html

/**
 * Formats a date as SQLite's `YYYY-MM-DD`.
 *
 * @param date the date
 * @return the date as text
 */
internal fun sqlDate(date: LocalDate): String = date.toString()

/**
 * Formats a time as SQLite's `HH:MM:SS`, with fractional seconds only when there are some.
 *
 * @param time the time
 * @return the time as text
 */
internal fun sqlTime(time: LocalTime): String {
	val seconds = "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
	if (time.nano == 0) {
		return seconds
	}
	return seconds + "." + "%09d".format(time.nano).trimEnd('0')
}

/**
 * Formats a date and time as SQLite's `YYYY-MM-DD HH:MM:SS`.
 *
 * @param dateTime the date and time
 * @return the date and time as text
 */
internal fun sqlDateTime(dateTime: LocalDateTime): String {
	return sqlDate(dateTime.toLocalDate()) + " " + sqlTime(dateTime.toLocalTime())
}

/** SQLite's date and time formats, with an optional time zone after a time. */
private val SQL_DATE_TIME = Regex(
	"""^(?:(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?))?|(\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?))""" +
		"""\s*(Z|[+-]\d{2}:\d{2})?$""",
)

/**
 * A date, time or both read from text in one of SQLite's formats.
 *
 * @property date the date, if the text has one
 * @property time the time, if the text has one
 * @property offset the time zone offset, if the text has one
 */
internal data class SqlDateTime(
	val date: LocalDate?,
	val time: LocalTime?,
	val offset: ZoneOffset?,
) {
	/**
	 * Returns the moment this describes, reading a missing time zone the way SQLite does, as UTC.
	 *
	 * @return the instant, or null when there's no date
	 */
	fun toInstant(): Instant? {
		val date = date ?: return null
		return LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT).toInstant(offset ?: ZoneOffset.UTC)
	}
}

/**
 * Reads text in one of SQLite's date and time formats.
 *
 * @param text the text
 * @return the parts found, or null when the text isn't in a known format
 */
internal fun parseSqlDateTime(text: String): SqlDateTime? {
	val match = SQL_DATE_TIME.matchEntire(text.trim()) ?: return null
	val (date, timeWithDate, timeAlone, offset) = match.destructured
	val time = timeWithDate.ifEmpty { timeAlone }
	return try {
		SqlDateTime(
			date = date.ifEmpty { null }?.let { LocalDate.parse(it) },
			time = time.ifEmpty { null }?.let { LocalTime.parse(it.take(18)) },
			offset = offset.ifEmpty { null }?.let { ZoneOffset.of(it) },
		)
	} catch (error: DateTimeException) {
		null
	}
}
