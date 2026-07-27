package saien.someday.sync

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrictJsonV2Test {
    @Test
    fun rejectsDuplicateKeysAtEveryNestingLevelAndEscapedAliases() {
        assertFailsWith<IllegalArgumentException> {
            StrictJsonV2.requireValidObjectKeys("{\"a\":1,\"a\":2}", 1024)
        }
        assertFailsWith<IllegalArgumentException> {
            StrictJsonV2.requireValidObjectKeys("{\"items\":[{\"id\":1,\"id\":2}]}", 1024)
        }
        assertFailsWith<IllegalArgumentException> {
            StrictJsonV2.requireValidObjectKeys("{\"id\":1,\"\\u0069d\":2}", 1024)
        }
    }

    @Test
    fun rejectsOversizedOrDeepFramingBeforeDeserialization() {
        assertFailsWith<IllegalArgumentException> {
            StrictJsonV2.requireValidObjectKeys("{\"value\":\"${"x".repeat(32)}\"}", 16)
        }
        val deep = "[".repeat(66) + "0" + "]".repeat(66)
        assertFailsWith<IllegalArgumentException> {
            StrictJsonV2.requireValidObjectKeys(deep, 1024)
        }
    }
}
