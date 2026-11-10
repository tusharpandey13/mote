package dev.mote.remote.diag

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HelperDiagTest {
    private val sent = ArrayList<String>()
    @Before fun setUp() { HelperDiag.clear(); sent.clear(); HelperDiag.sender = { sent += it; true } }
    @After fun tearDown() { HelperDiag.clear(); HelperDiag.sender = { false } }

    @Test fun openAsksForSnapshotAndSubscribes() {
        HelperDiag.open()
        assertEquals(listOf("diag", "diag.subscribe"), sent.map { Wire.typeOf(it) })
        assertEquals(HelperDiag.Status.LOADING, HelperDiag.state.value.status)
        HelperDiag.deliver("""{"t":"diag","helper":{"version":"0.7.0","protocol":"1.5","arch":"arm64"}}""")
        assertEquals(HelperDiag.Status.OK, HelperDiag.state.value.status)
        HelperDiag.deliver("""{"t":"diag.events","subscribed":true,"events":[{"seq":1,"area":"auth","event":"ok"}]}""")
        assertTrue(HelperDiag.state.value.subscribed)
        assertEquals(1, HelperDiag.state.value.events.size)
    }

    @Test fun olderHelperSaysUpdate() {
        HelperDiag.open()
        HelperDiag.onErr("unknown", "diag")
        val s = HelperDiag.state.value
        assertEquals(HelperDiag.Status.UNSUPPORTED, s.status)
        assertEquals(DiagRows.UPDATE_HELPER, DiagRows.helper(s.status, s.snapshot).single().value)
        sent.clear(); HelperDiag.open()
        assertTrue("no more asking an old helper", sent.isEmpty())
    }

    @Test fun noLinkOverBluetooth() {
        HelperDiag.sender = { false }
        HelperDiag.open()
        assertEquals(HelperDiag.Status.NO_LINK, HelperDiag.state.value.status)
    }

    @Test fun rowsExplainBlockedFeaturesAndUnknownPermissions() {
        val snap = JSONObject("""{"t":"diag","helper":{"version":"0.7.0","protocol":"1.5","arch":"arm64"},
          "features":{"input":{"state":"blocked","blockedBy":"accessibility-not-granted"},"labs.ctx":{"state":"off","detail":"Turn on Labs"}},
          "permissions":{"bluetooth":{"state":"unknown"}}}""")
        val rows = DiagRows.helper(HelperDiag.Status.OK, snap)
        assertTrue(rows.any { it.title == "input" && it.bad && it.value.contains("Accessibility") })
        assertTrue(rows.any { it.title == "labs.ctx" && !it.bad })
        assertTrue(rows.any { it.value.startsWith("can't check") && !it.bad })
    }
}
