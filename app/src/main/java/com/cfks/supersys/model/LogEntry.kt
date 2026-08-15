package com.cfks.supersys.model

data class LogEntry(
    val rawLine: String,
    val level: Char
) {
    companion object {
        const val LEVEL_VERBOSE = 'V'
        const val LEVEL_DEBUG = 'D'
        const val LEVEL_INFO = 'I'
        const val LEVEL_WARN = 'W'
        const val LEVEL_ERROR = 'E'
        const val LEVEL_FATAL = 'F'

        /**
         * Parse a logcat -v threadtime line and extract the level character.
         * Format: MM-DD HH:MM:SS.mmm PID TID LEVEL/TAG: MESSAGE
         */
        fun parse(line: String): LogEntry {
            val regex = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+([VDIWEF])""")
            val match = regex.find(line)
            val level = match?.groupValues?.get(1)?.first() ?: ' '
            return LogEntry(line, level)
        }
    }
}
