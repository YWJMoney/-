package com.bamnat.app.alarm

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.time.LocalDate
import java.time.LocalTime

/** [OPS-10] The alarm rings as the alarm-app foreground service type, with no media playback permission. */
@RunWith(AndroidJUnit4::class)
class AlarmFgsTypeTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun theManifestAsksForNoMediaPlayback() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse(requested.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED))
        assertTrue(requested.contains(Manifest.permission.USE_EXACT_ALARM))
        val info = context.packageManager.getServiceInfo(ComponentName(context, AlarmService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, info.foregroundServiceType)
    }

    @Test fun aRingingAlarmRunsAsSystemExempted() {
        val ring = RingRequest("주간", LocalDate.now(), LocalTime.of(7, 0), 60, null, System.currentTimeMillis())
        val service = Robolectric.buildService(AlarmService::class.java,
            ring.putInto(Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_START))).create().startCommand(0, 1).get()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, service.foregroundServiceType)
        service.onDestroy()
    }

    @Test fun androidThirteenAndOlderNeedNoType() {
        val sdk = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.TIRAMISU)
            assertEquals(0, AlarmService.FOREGROUND_TYPE)
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, AlarmService.FOREGROUND_TYPE)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }
}
