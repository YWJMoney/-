package com.bamnat.app.ui.calendar

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.share.ReceivedCalendarStore
import com.bamnat.app.share.ReceivedShare
import com.bamnat.app.share.ShareApi
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkState
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** [SHR-02] Opening a received link, keeping it, and letting it go when the sharer cuts it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReceivedViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val api = FakeApi()
    private val id = "Ab3dEfGh5JkLmNoPqRsTuv"

    private class FakeApi : ShareApi {
        override val enabled = true
        val shares = mutableMapOf<String, ReceivedShare>()
        var failure: IOException? = null
        /** Holds every read until completed, like a slow network. */
        var gate: CompletableDeferred<Unit>? = null
        var reads = 0
        /** [SHR-12] Answers "nothing changed" to a look that carries a tag. */
        var unchanged = false
        var tags = mutableListOf<String?>()
        override suspend fun create(payload: JSONObject) = error("not used")
        override suspend fun update(state: ShareLinkState, payload: JSONObject) = error("not used")
        override suspend fun delete(state: ShareLinkState) = Unit
        override suspend fun read(id: String): ReceivedShare {
            reads++
            gate?.await()
            failure?.let { throw it }
            return shares[id] ?: throw ShareLinkException("not_found")
        }
        override suspend fun readIfChanged(id: String, etag: String?): ReceivedShare? {
            tags += etag
            if (unchanged && etag != null) { reads++; return null }
            return read(id)
        }
    }

    private fun share(title: String, expires: Instant = Instant.now().plus(Duration.ofDays(30))) = ReceivedShare(
        JSONObject("""{"v":2,"title":"$title","shows":["memos"],"types":[{"id":1,"name":"주간","short":"주","color":"#1E88E5","time":"07:00-19:00"}],
            "days":[{"date":"2026-11-01","type":1,"memo":"치과"}]}"""),
        expires, Instant.now(),
    )

    private fun settle() = repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

    @Before fun setUp() {
        c.shareApi = api
        api.shares[id] = share("민지")
    }

    @Test fun aPastedMessageOpensAndStays() {
        val vm = ReceivedViewModel(c)
        vm.preview("민지의 근무표\nhttps://bamnat-share.example.workers.dev/s/$id")
        settle()
        val ready = vm.preview.value as ReceivedViewModel.Preview.Ready
        assertEquals("민지", ready.schedule.title)
        assertFalse(ready.existing)
        assertTrue(vm.add(ready))
        assertEquals(listOf(id), c.received.calendars.value.map { it.id })
        assertEquals(id, vm.viewing.value?.id)
        assertEquals(ReceivedViewModel.Preview.Idle, vm.preview.value)

        // The same link again is an update, not a second calendar.
        vm.previewId(id)
        settle()
        val again = vm.preview.value as ReceivedViewModel.Preview.Ready
        assertTrue(again.existing)
        assertTrue(vm.add(again))
        assertEquals(1, c.received.calendars.value.size)

        vm.view(null)
        settle()
        assertEquals(null, vm.viewing.value)
    }

    @Test fun saysWhatWentWrong() {
        val vm = ReceivedViewModel(c)
        vm.preview("근무표 보내 줘")
        assertTrue((vm.preview.value as ReceivedViewModel.Preview.Failed).message.startsWith("링크를 찾지 못했어요"))
        vm.preview("https://bamnat-share.example.workers.dev/s/ZZZZZZZZZZZZZZZZZZ")
        settle()
        assertTrue((vm.preview.value as ReceivedViewModel.Preview.Failed).message.contains("끊겼거나"))
        api.failure = IOException("offline")
        vm.previewId(id)
        settle()
        assertTrue((vm.preview.value as ReceivedViewModel.Preview.Failed).message.startsWith("인터넷에"))
    }

    @Test fun aCutLinkGoesFromThisPhone() {
        c.received.put(id, share("민지"))
        val vm = ReceivedViewModel(c)
        api.shares.remove(id)
        vm.refresh(id)
        settle()
        assertTrue(c.received.get(id)!!.gone)
        assertEquals(ReceivedViewModel.Refresh.Idle, vm.refresh.value)
    }

    @Test fun offlineKeepsTheLastCopy() {
        c.received.put(id, share("민지"))
        val vm = ReceivedViewModel(c)
        api.failure = IOException("offline")
        vm.refresh(id)
        settle()
        assertFalse(c.received.get(id)!!.gone)
        assertEquals(ReceivedViewModel.Refresh.Offline(id), vm.refresh.value)
        // So does an answer the app cannot read.
        api.failure = ShareLinkException("bad_response")
        vm.refresh(id)
        settle()
        assertFalse(c.received.get(id)!!.gone)
        assertEquals(ReceivedViewModel.Refresh.Offline(id), vm.refresh.value)
    }

    /** Taken out while it was loading: it stays out when the answer comes. */
    @Test fun aLateRefreshDoesNotBringItBack() {
        c.received.put(id, share("민지"))
        val vm = ReceivedViewModel(c)
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        vm.refresh(id)
        settle()
        vm.remove(id)
        gate.complete(Unit)
        settle()
        assertEquals(null, c.received.get(id))
        assertEquals(ReceivedViewModel.Refresh.Idle, vm.refresh.value)
    }

    /** Looking at a kept link again after it was cut clears the kept copy too. */
    @Test fun aCutLinkSeenAgainGoesToo() {
        c.received.put(id, share("민지"))
        api.shares.remove(id)
        val vm = ReceivedViewModel(c)
        vm.previewId(id)
        settle()
        assertTrue(c.received.get(id)!!.gone)
    }

    @Test fun switchingFetchesOnlyWhenTheCopyIsOld() {
        var now = Instant.now()
        c.received.put(id, share("민지"))
        val vm = ReceivedViewModel(c) { now }
        vm.view(id)
        settle()
        assertEquals(0, api.reads)
        now = now.plus(Duration.ofMinutes(6))
        vm.view(id)
        settle()
        assertEquals(1, api.reads)
    }

    /** [SHR-12] A look carries the copy's tag; nothing changed keeps the copy and only marks it checked. */
    @Test fun anUnchangedLinkKeepsItsCopy() {
        var now = Instant.now()
        c.received.put(id, share("민지").copy(etag = "W/\"t1\""))
        val before = c.received.get(id)!!
        api.unchanged = true
        api.shares[id] = share("민지2")
        val vm = ReceivedViewModel(c) { now }
        now = now.plus(Duration.ofMinutes(2))
        vm.view(id)
        settle()
        assertEquals(listOf<String?>("W/\"t1\""), api.tags)
        assertEquals("민지", c.received.get(id)?.title)
        assertEquals(before.raw.toString(), c.received.get(id)?.raw.toString())

        // The sharer changed it: the next look downloads it.
        api.unchanged = false
        now = now.plus(Duration.ofMinutes(2))
        vm.refreshIfStale(id)
        settle()
        assertEquals("민지2", c.received.get(id)?.title)
    }

    @Test fun expiredOnesGoWhenTheAppOpens() {
        c.received.put(id, share("민지", expires = Instant.now().minusSeconds(60)))
        ReceivedViewModel(c)
        assertTrue(c.received.get(id)!!.gone)
    }

    /** Ran out while the app stayed open: gone the next time it shows, even with no internet to ask. */
    @Test fun expiredWhileOpenGoesWhenItShows() {
        var now = Instant.now()
        c.received.put(id, share("민지", expires = now.plus(Duration.ofDays(7))))
        val vm = ReceivedViewModel(c) { now }
        assertFalse(c.received.get(id)!!.gone)
        now = now.plus(Duration.ofDays(8))
        api.failure = IOException("offline")
        vm.view(id)
        settle()
        assertTrue(c.received.get(id)!!.gone)
    }

    @Test fun theListHasRoom() {
        repeat(ReceivedCalendarStore.MAX) { i -> c.received.put("Full${i}AAAAAAAAAAAAAAA".take(20), share("동료$i")) }
        val vm = ReceivedViewModel(c)
        vm.previewId(id)
        settle()
        assertFalse(vm.add(vm.preview.value as ReceivedViewModel.Preview.Ready))
        assertTrue((vm.preview.value as ReceivedViewModel.Preview.Failed).message.contains("${ReceivedCalendarStore.MAX}개까지"))
        assertEquals(ReceivedCalendarStore.MAX, c.received.calendars.value.size)
    }
}
