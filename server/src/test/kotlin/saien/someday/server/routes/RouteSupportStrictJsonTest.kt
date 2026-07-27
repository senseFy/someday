package saien.someday.server.routes

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteSupportStrictJsonTest {
    @Test
    fun duplicateKeysAreRejectedAtEveryObjectDepthIncludingEscapedAliases() {
        assertTrue(StrictJsonDuplicateKeyDetector.hasDuplicateObjectKey("""{"a":1,"a":2}"""))
        assertTrue(StrictJsonDuplicateKeyDetector.hasDuplicateObjectKey("""{"a":1,"\u0061":2}"""))
        assertTrue(StrictJsonDuplicateKeyDetector.hasDuplicateObjectKey("""{"outer":{"x":1,"x":2}}"""))
        assertFalse(StrictJsonDuplicateKeyDetector.hasDuplicateObjectKey("""{"a":1,"nested":{"a":2}}"""))
        assertFalse(StrictJsonDuplicateKeyDetector.hasDuplicateObjectKey("""{"array":[{"a":1},{"a":2}]}"""))
    }
}
