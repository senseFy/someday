package saien.someday.server.auth

fun normalizeAccountEmail(email: String): String = email.trim().lowercase()

fun isValidAccountEmail(email: String): Boolean =
    email.length in MIN_ACCOUNT_EMAIL_LENGTH..MAX_ACCOUNT_EMAIL_LENGTH &&
        "@" in email &&
        "." in email.substringAfter("@")

fun isValidAccountPassword(password: String): Boolean =
    password.length in MIN_ACCOUNT_PASSWORD_LENGTH..MAX_ACCOUNT_PASSWORD_LENGTH

const val MIN_ACCOUNT_EMAIL_LENGTH: Int = 3
const val MAX_ACCOUNT_EMAIL_LENGTH: Int = 320
const val MIN_ACCOUNT_PASSWORD_LENGTH: Int = 8
const val MAX_ACCOUNT_PASSWORD_LENGTH: Int = 128
