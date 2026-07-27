package saien.someday.app.ios

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosLocationAdapterSmokeTest {
    @Test
    fun iosGrantedLocationAdapterFeedsSharedEditorWithoutMapSdk() {
        val log = IosShellEntrypoint.grantedLocationSmokeLog()

        println(log)
        assertTrue(log.contains("platform=ios"))
        assertTrue(log.contains("location=granted"))
        assertTrue(log.contains("lat=37.3349"))
        assertTrue(log.contains("lon=-122.00902"))
        assertTrue(log.contains("saved=true"))
        assertTrue(log.contains("no-map-sdk"))
        assertFalse(log.contains("mapkit"))
    }
}
