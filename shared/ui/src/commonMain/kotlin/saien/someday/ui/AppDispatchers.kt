package saien.someday.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class AppDispatchers(
    val background: CoroutineDispatcher = Dispatchers.Default,
)
