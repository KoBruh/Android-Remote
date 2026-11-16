package com.androidremote.app.tv

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** Reads the TV's name and MAC address out of the subject of its certificate. */
object CertSubject {

    /** Name and MAC address the TV advertises in its certificate. Either may be empty. */
    fun nameAndMac(cert: X509Certificate): Pair<String, String> =
        parse(cert.subjectX500Principal.getName(X500Principal.RFC2253, mapOf(OID_DN_QUALIFIER to "DNQ")))

    /**
     * Extracts the name and MAC address from the subject of an Android TV certificate.
     * Ported from androidtvremote2 (_parse_name_and_mac).
     *
     * NVIDIA SHIELD example: `CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX`
     * Nexus Player example: `dnQualifier=fugu/fugu/Nexus Player/CN=atvremote/XX:XX:XX:XX:XX:XX`
     *
     * @param subject the subject in RFC 2253 form, with the dnQualifier attribute named `DNQ`.
     */
    fun parse(subject: String): Pair<String, String> {
        val attributes = splitRfc2253(subject)
        val commonName = attributes["CN"].orEmpty()
        val dnQualifier = attributes["DNQ"].orEmpty()
        val commonNameParts = commonName.split("/")
        val name = when {
            dnQualifier.isNotEmpty() -> dnQualifier.split("/").last()
            commonNameParts.size > 1 -> commonNameParts[commonNameParts.size - 2]
            else -> commonName
        }
        return name to commonNameParts.last()
    }

    private const val OID_DN_QUALIFIER = "2.5.4.46"

    /** Splits an RFC 2253 distinguished name into its attributes, honouring backslash escapes. */
    private fun splitRfc2253(subject: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val current = StringBuilder()
        var key: String? = null
        fun flush() {
            key?.let { result.putIfAbsent(it.trim().uppercase(), current.toString()) }
            key = null
            current.clear()
        }
        var i = 0
        while (i < subject.length) {
            val c = subject[i]
            when {
                c == '\\' && i + 1 < subject.length -> {
                    current.append(subject[i + 1])
                    i++
                }
                c == '=' && key == null -> {
                    key = current.toString()
                    current.clear()
                }
                c == ',' || c == '+' -> flush()
                else -> current.append(c)
            }
            i++
        }
        flush()
        return result
    }
}
