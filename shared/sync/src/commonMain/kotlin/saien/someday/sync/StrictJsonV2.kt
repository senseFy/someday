package saien.someday.sync

import saien.someday.data.serialization.StrictJsonFraming

/** Strict framing check applied before kotlinx.serialization can collapse duplicate names. */
internal object StrictJsonV2 {
    fun requireValidObjectKeys(input: String, maxUtf8Bytes: Int) =
        StrictJsonFraming.requireValidObjectKeys(input, maxUtf8Bytes)
}
