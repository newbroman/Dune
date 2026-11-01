package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checked against python-kasa 0.12 (KlapTransportV2, KlapEncryptionSession, XorEncryption) with the same inputs. */
class HomeLinkTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val local = ByteArray(16) { it.toByte() }
    private val remote = ByteArray(16) { (100 + it).toByte() }

    @Test fun accountHashes() {
        assertEquals("7b7d88aa725ce72de9dc48345207804367f68d2cf88b5419706e3c8f1217dfc2", hex(Klap.authV2("me@example.com", "p4ss!")))
        assertEquals("5164b10855d341b89b50b3e1e570699c", hex(Klap.authV1("me@example.com", "p4ss!")))
    }
    @Test fun handshakeHashes() {
        val a2 = Klap.authV2("me@example.com", "p4ss!"); val a1 = Klap.authV1("me@example.com", "p4ss!")
        assertEquals("2fbea889d0cdfe6437e52c27a7d805bf1b29f7a79414e24a26fab900c09fab13", hex(Klap.serverHashV2(local, remote, a2)))
        assertEquals("ff898ae8edbfad01f5ae033f06f321259c8002665266c00e967263e2b5b1e89f", hex(Klap.serverHashV1(local, remote, a1)))
        assertEquals("6db83cae1f69e57dc781bab2e24d2973ef5af46b5c30838ca35e2db1c48e1118", hex(Klap.clientHashV2(local, remote, a2)))
        assertEquals("672a0aeccb882a60c827d869d633d962ea0dfe01771656f43a9c5c45409207e8", hex(Klap.clientHashV1(local, remote, a1)))
    }
    @Test fun encryption() {
        val c = Klap.Crypto(local, remote, Klap.authV2("me@example.com", "p4ss!"))
        val (payload, seq) = c.encrypt("""{"method":"get_device_info"}""")
        assertEquals(103460553, seq)
        assertEquals("b129540fe5e86e433a093b8705fd10ffdcadf86ce4f39037edffee9ae8fba9ad6c2f3412f394ae2b3ccea28a364ff5f0361c411eb5791b163571bc2bc766589c", hex(payload))
        assertEquals("""{"method":"get_device_info"}""", c.decrypt(payload, seq))
    }
    @Test fun legacyXor() {
        val e = Klap.xor("""{"system":{"get_sysinfo":{}}}""".toByteArray())
        assertEquals("d0f281f88bff9af7d5ef94b6d1b4c09fec95e68fe187e8caf08bf68bf6", hex(e))
        assertEquals("""{"system":{"get_sysinfo":{}}}""", String(Klap.unxor(e)))
    }
}

/** Tuya request signatures, checked against tinytuya's method with the same inputs. */
class TuyaSignTest {
    @Test fun tokenRequest() = assertEquals("FE0035154634C25E59D44C315441C3FB3987DE2EAC9B22D33D9B4616AF4C9FFC",
        Tuya.sign("abc123", "s3cr3t", "", 1700000000000, "GET", "/v1.0/token?grant_type=1", ""))
    @Test fun commandRequest() = assertEquals("17E1D26BEE9F589B0831A244229BD8F79F642ABF0CA0B82C3FBDF25369F22444",
        Tuya.sign("abc123", "s3cr3t", "tok9", 1700000000000, "POST", "/v1.0/iot-03/devices/d1/commands", """{"commands":[{"code":"switch_1","value":true}]}"""))
}
