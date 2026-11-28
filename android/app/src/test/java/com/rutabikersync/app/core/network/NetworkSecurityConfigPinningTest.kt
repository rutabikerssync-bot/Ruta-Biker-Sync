package com.rutabikersync.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

/** Auditoría integral 2026-11-27 · verifica el pinning sin depender de un dispositivo. */
class NetworkSecurityConfigPinningTest {

    companion object {
        const val PROD_HOST = "ruta-biker-sync.onrender.com"
        /** SPKI SHA-256 del chain real consultado el 2026-11-28. */
        val PROD_PINS = listOf(
            "QPI0fij4f9IFNgdTG+qw55e/WWB5PK5Ev5MO8QB3PAg=", // leaf onrender.com
            "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=", // GTS WE1
            "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c="  // GTS Root R4
        )
    }

    private fun document(relativePath: String): Document {
        val file = listOf(
            File("src/$relativePath"),
            File("app/src/$relativePath")
        ).firstOrNull { it.isFile }
            ?: error("No se encontró $relativePath desde ${System.getProperty("user.dir")}")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(file).apply { normalizeDocument() }
    }

    private fun elements(document: Document, tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun attribute(element: Element, name: String): String? =
        element.attributes?.getNamedItem(name)?.nodeValue

    @Test
    fun `standardRelease fija TLS y los tres pins del chain productivo`() {
        val config = document("standardRelease/res/xml/network_security_config.xml")

        val baseConfig = elements(config, "base-config").single()
        assertEquals("false", attribute(baseConfig, "cleartextTrafficPermitted"))

        val domain = elements(config, "domain").single()
        assertEquals(PROD_HOST, domain.textContent.trim())
        assertEquals("false", attribute(domain, "includeSubdomains"))

        val pinSet = elements(config, "pin-set").single()
        val expiration = requireNotNull(attribute(pinSet, "expiration")) { "pin-set sin expiration" }
        assertEquals("2027-02-01", expiration)
        assertTrue(
            "Los pins deben expirar antes del vencimiento del leaf (2027-02-17)",
            LocalDate.parse(expiration).isBefore(LocalDate.of(2027, 2, 17))
        )

        val pins = elements(config, "pin")
        assertEquals(3, pins.size)
        pins.forEach { assertEquals("SHA-256", attribute(it, "digest")) }
        assertEquals(PROD_PINS.toSet(), pins.map { it.textContent.trim() }.toSet())
    }

    @Test
    fun `config base mantiene TLS obligatorio sin pinning`() {
        val config = document("main/res/xml/network_security_config.xml")

        assertEquals("false", attribute(elements(config, "base-config").single(), "cleartextTrafficPermitted"))
        assertTrue("El config base no debe contener pins", elements(config, "pin").isEmpty())
    }

    @Test
    fun `debug mantiene HTTP local sin pinning`() {
        val config = document("debug/res/xml/network_security_config.xml")

        assertTrue("El config debug permite HTTP local", elements(config, "base-config").isNotEmpty())
        assertEquals("true", attribute(elements(config, "base-config").single(), "cleartextTrafficPermitted"))
        assertFalse("El config debug no debe contener pins", elements(config, "pin").isNotEmpty())
    }
}
