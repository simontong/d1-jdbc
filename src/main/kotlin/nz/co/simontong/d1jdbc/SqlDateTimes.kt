package nz.co.simontong.d1jdbc

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
