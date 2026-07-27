package saien.someday.app.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLocationAdapterSmokeTest {
    @Test
    fun androidGrantedLocationAdapterFeedsSharedEditorWithoutMapSdk() {
        val log = AndroidShellEntrypoint.grantedLocationSmokeLog()

        println(log)
        assertTrue(log.contains("platform=android"))
        assertTrue(log.contains("location=granted"))
        assertTrue(log.contains("lat=37.4219999"))
        assertTrue(log.contains("lon=-122.0840575"))
        assertTrue(log.contains("saved=true"))
        assertTrue(log.contains("no-map-sdk"))
        assertFalse(log.contains("maps.googleapis"))
    }
}
