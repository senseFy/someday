package saien.someday.sync.causality.v2

/** Normalizes the UUID used as a writer identity on every platform. */
fun normalizeWriterDeviceIdV2(value: String): String {
    val normalized = value.trim().lowercase()
    require(UUID_V4_PATTERN_SYSTEM_V2.matches(normalized)) {
        "The local installation does not have a valid V2 writer UUID."
    }
    return normalized
}
