package uk.newbroman.dune

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

class CoinbaseTest {
    // Throwaway key made with: openssl ecparam -name prime256v1 -genkey -noout
    private val pem = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEIGFFxa7qiTmN4/xDqlZNDS+RzTVTCDMSMHkSTZam2MTHoAoGCCqGSM49\nAwEHoUQDQgAEYAN7fi+TkUCNAzlccNpd1XLgat1a41nYxR31KJBcqVPDHM8+DrEJ\nN9FF1vYHmAnAOZyLzylWkEFZPiVulndiXw==\n-----END EC PRIVATE KEY-----\n"
    private val name = "organizations/test-org/apiKeys/test-key"

    @Test fun parsesJsonKeyFile() {
        val file = JSONObject().put("name", name).put("privateKey", pem).toString()
        val k = Coinbase.parseKey("", file)!!
        assertEquals(name, k.name)
        assertTrue(k.pem.startsWith("-----BEGIN EC PRIVATE KEY-----"))
    }

    @Test fun jwtVerifies() {
        val k = Coinbase.ApiKey(name, pem)
        val jwt = Coinbase.jwt(k, "GET", "/api/v3/brokerage/accounts", now = 1_800_000_000)
        val (h, p, s) = jwt.split(".")
        val dec = java.util.Base64.getUrlDecoder()
        val header = JSONObject(String(dec.decode(h)))
        val payload = JSONObject(String(dec.decode(p)))
        assertEquals("ES256", header.getString("alg")); assertEquals(name, header.getString("kid"))
        assertEquals("JWT", header.getString("typ")); assertEquals(32, header.getString("nonce").length)
        assertEquals("GET api.coinbase.com/api/v3/brokerage/accounts", payload.getString("uri"))
        assertEquals("cdp", payload.getString("iss")); assertEquals(1_800_000_120L, payload.getLong("exp"))

        // Verify the raw r||s signature with the matching public key.
        val raw = dec.decode(s); assertEquals(64, raw.size)
        fun derInt(b: ByteArray): ByteArray { val v = BigInteger(1, b).toByteArray(); return byteArrayOf(0x02, v.size.toByte()) + v }
        val body = derInt(raw.copyOfRange(0, 32)) + derInt(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, body.size.toByte()) + body
        val priv = Coinbase.privateKey(pem) as ECPrivateKey
        val params = priv.params
        // public point = d·G, via a tiny double-and-add on the curve
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(mul(priv.s, params.generator, params), params))
        val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update("$h.$p".toByteArray()) }.verify(der)
        assertTrue("signature verifies", ok)
    }

    private fun mul(k: BigInteger, g: ECPoint, ps: java.security.spec.ECParameterSpec): ECPoint {
        val pr = (ps.curve.field as java.security.spec.ECFieldFp).p; val a = ps.curve.a
        fun add(p1: ECPoint, p2: ECPoint): ECPoint {
            if (p1 == ECPoint.POINT_INFINITY) return p2; if (p2 == ECPoint.POINT_INFINITY) return p1
            val l = if (p1 == p2) p1.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(p1.affineY.shiftLeft(1).modInverse(pr)).mod(pr)
                else { if (p1.affineX == p2.affineX) return ECPoint.POINT_INFINITY
                    p2.affineY.subtract(p1.affineY).multiply(p2.affineX.subtract(p1.affineX).modInverse(pr)).mod(pr) }
            val x = l.pow(2).subtract(p1.affineX).subtract(p2.affineX).mod(pr)
            return ECPoint(x, l.multiply(p1.affineX.subtract(x)).subtract(p1.affineY).mod(pr))
        }
        var r = ECPoint.POINT_INFINITY; var q = g
        for (i in 0 until k.bitLength()) { if (k.testBit(i)) r = add(r, q); q = add(q, q) }
        return r
    }
}
