package com.rutabikersync.app.core.debug

/**
 * Redacción defensiva aplicada a cada línea de logcat antes de escribir el ZIP.
 *
 * La exportación es sólo para builds de desarrollo, pero aun así no se permite que
 * salgan del dispositivo correos, tokens, coordenadas exactas, UUIDs de piloto ni
 * blobs base64. Las funciones son puras para poder verificarlas en JVM.
 */
object AppLogRedactor {

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    private val BEARER = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{8,}""")
    private val ASSIGNMENT = Regex(
        """(?i)\b(token|secret|password|passwd|api[_-]?key|authorization|fcm[_-]?token|refresh[_-]?token)\s*[=:]\s*([^\s,;]+)"""
    )
    private val COORDINATE = Regex("""-?\d{1,3}\.\d{5,}""")
    private val UUID = Regex(
        """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
    )
    private val BASE64_BLOB = Regex("""data:[A-Za-z0-9.+/-]+;base64,[A-Za-z0-9+/=]{16,}""")

    /** Líneas que identifican a una persona o contienen datos personales: se descartan enteras. */
    private val SENSITIVE_LINE = Regex(
        """(?i)(displayname|fullname|aliasbiker|medical|direccion|dirección|phone|email|correo)"""
    )

    fun shouldDropLine(line: String): Boolean = SENSITIVE_LINE.containsMatchIn(line)

    fun redact(line: String): String {
        var out = BASE64_BLOB.replace(line, "[base64-redacted]")
        out = JWT.replace(out, "[jwt-redacted]")
        out = BEARER.replace(out) { match -> "${match.groupValues[1]}[redacted]" }
        out = ASSIGNMENT.replace(out) { match -> "${match.groupValues[1]}=[redacted]" }
        out = EMAIL.replace(out, "[email-redacted]")
        out = COORDINATE.replace(out, "[coord]")
        out = UUID.replace(out, "[uuid-redacted]")
        return out
    }
}
