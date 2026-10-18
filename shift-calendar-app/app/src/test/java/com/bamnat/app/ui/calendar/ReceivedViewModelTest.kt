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
        var reads = 0
        override suspend fun create(payload: JSONObject) = error("not used")
        override suspend fun update(state: ShareLinkState, payload: JSONObject) = error("not used")
        override suspend fun delete(state: ShareLinkState) = Unit
        override suspend fun read(id: String): ReceivedShare {
            reads++
            failure?.let { throw it }
            return shares[id] ?: throw ShareLinkException("not_found")
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

    @Test fun expiredOnesGoWhenTheAppOpens() {
        c.received.put(id, share("민지", expires = Instant.now().minusSeconds(60)))
        ReceivedViewModel(c)
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
