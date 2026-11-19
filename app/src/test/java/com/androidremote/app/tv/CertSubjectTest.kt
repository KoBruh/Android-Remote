package com.androidremote.app.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors androidtvremote2's test_parse_name_and_mac. */
class CertSubjectTest {

    @Test
    fun `name and MAC come from the common name`() {
        assertEquals(
            "SHIELD Android TV" to "AA:BB:CC:DD:EE:FF",
            CertSubject.parse("CN=atvremote/darcy/darcy/SHIELD Android TV/AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `name comes from the dnQualifier when there is one`() {
        assertEquals(
            "Nexus Player" to "AA:BB:CC:DD:EE:FF",
            CertSubject.parse("CN=atvremote/AA:BB:CC:DD:EE:FF,DNQ=fugu/fugu/Nexus Player"),
        )
    }

    @Test
    fun `escaped characters in a name are unescaped`() {
        assertEquals(
            "Living Room, TV" to "AA:BB:CC:DD:EE:FF",
            CertSubject.parse("CN=atvremote/x/x/Living Room\\, TV/AA:BB:CC:DD:EE:FF,O=Example"),
        )
    }

    @Test
    fun `a subject without separators or common name still parses`() {
        assertEquals("plain" to "plain", CertSubject.parse("CN=plain"))
        assertEquals("Nexus Player" to "", CertSubject.parse("DNQ=fugu/fugu/Nexus Player"))
    }

    @Test
    fun `the TCL test TV's subject gives its name and MAC`() {
        // Subject of the real TV (192.168.0.55) as X500Principal renders it with the DNQ keyword map.
        assertEquals(
            "UnionTV" to "28:AD:18:4C:78:29",
            CertSubject.parse("CN=atvremote/28:AD:18:4C:78:29,DNQ=UnionTV/UnionTV/UnionTV"),
        )
    }
}
