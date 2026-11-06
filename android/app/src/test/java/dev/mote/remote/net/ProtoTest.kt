package dev.mote.remote.net

import dev.mote.remote.hid.Consumer
import dev.mote.remote.hid.Mod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    private fun j(s: String) = JSONObject(s)

    @Test fun keyUsesHidUsageAndModifierBits() {
        val o = j(Proto.key(0x50, Mod.CMD or Mod.SHIFT))
        assertEquals("key", o.getString("t"))
        assertEquals(80, o.getInt("usage"))
        assertEquals(10, o.getInt("mods")) // shift 2 + command 8, same bits as the protocol
        assertFalse("tap is the default and omitted", o.has("action"))
        assertEquals("down", j(Proto.key(4, 0, "down")).getString("action"))
    }

    @Test fun pointerMessages() {
        assertEquals(12, j(Proto.move(12, -3)).getInt("dx"))
        assertEquals(-3, j(Proto.move(12, -3)).getInt("dy"))
        assertEquals(40, j(Proto.scroll(0, 40)).getInt("dy"))
        val b = j(Proto.button("right", "down"))
        assertEquals("right", b.getString("b")); assertEquals("down", b.getString("action"))
        val c = j(Proto.clickAt(1.5, 0.25))
        assertEquals(1.0, c.getDouble("x"), 0.0) // clamped to the display
        assertEquals("norm", c.getString("unit"))
        assertEquals(0, c.getInt("display"))
    }

    @Test fun consumerUsagesMapToProtocolKeys() {
        assertEquals("play", Proto.consumerKey(Consumer.PLAY_PAUSE))
        assertEquals("brightup", Proto.consumerKey(Consumer.BRIGHTNESS_UP))
        assertEquals("voldown", Proto.consumerKey(Consumer.VOL_DOWN))
        assertNull(Proto.consumerKey(0x1234))
        assertEquals("right", Proto.buttonName(2))
        assertEquals("left", Proto.buttonName(1))
    }

    @Test fun textIsChunkedWithoutSplittingSurrogates() {
        assertEquals(1, Proto.text("Grüße 東京 👋").size)
        val long = "a".repeat(Proto.TEXT_MAX - 1) + "👋" + "b"
        val parts = Proto.text(long).map { j(it).getString("s") }
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.length <= Proto.TEXT_MAX })
        assertEquals(long, parts.joinToString(""))
        assertFalse(Character.isHighSurrogate(parts[0].last()))
        assertTrue(Proto.text("").isEmpty())
    }

    @Test fun parsesHelperMessages() {
        val h = Proto.parse("""{"t":"hello","v":1,"app":"mote-helper","host":"Mac","hostId":"0123456789abcdef","local":"Mac-2","nonce":"ab","pairing":true}""")
        assertEquals(Msg.Hello(1, "Mac", "0123456789abcdef", "Mac-2", "ab", true), h)
        // v1.0 helpers send no hostId / local
        assertEquals(Msg.Hello(1, "Mac", null, null, "ab", false), Proto.parse("""{"t":"hello","v":1,"host":"Mac","nonce":"ab","pairing":false}"""))
        assertEquals(Msg.Err("rate", "too many", 42, "auth"), Proto.parse("""{"t":"err","code":"rate","msg":"too many","retryAfter":42,"ref":"auth"}"""))
        assertEquals(Msg.Authed("id", "phone", false, true), Proto.parse("""{"t":"authed","id":"id","name":"phone","paused":false,"ax":true}"""))
        assertEquals(Msg.Other("future"), Proto.parse("""{"t":"future","x":1}"""))
        assertNull(Proto.parse("not json"))
    }

    @Test fun hmacMatchesRfc4231AndHelperFormat() {
        // RFC 4231 test case 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Proto.hmacHex("Jefe".toByteArray(), "what do ya want for nothing?"),
        )
        // Token as the helper sends it (64 hex chars) -> 32 key bytes; nonce is ASCII (smoke.mjs: createHmac(sha256, Buffer.from(token,'hex')).update(nonce))
        val token = Proto.hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")!!
        assertEquals(32, token.size)
        assertEquals( // reference value from node:crypto, as in helper/tests/smoke.mjs
            "36ec4579078060dfb6c95e4a7a9d963671b37c5dd53a9e4b03955c21f8cbb93a",
            Proto.hmacHex(token, "00112233445566778899aabbccddeeff"),
        )
        assertNull(Proto.hexToBytes("abc"))
        assertNull(Proto.hexToBytes("zz"))
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
    }

    @Test fun pairAndAuthMessages() {
        val p = j(Proto.pair("123456", "x".repeat(100)))
        assertEquals("123456", p.getString("code")); assertEquals(64, p.getString("name").length)
        val a = j(Proto.auth("dev", "ff"))
        assertEquals("auth", a.getString("t")); assertEquals("dev", a.getString("id")); assertEquals("ff", a.getString("mac"))
    }
}
