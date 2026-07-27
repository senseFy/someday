package saien.someday.ui

import saien.someday.domain.settings.ClientSettings

internal fun initialSelectedNotebookId(settings: ClientSettings): String? =
    settings.lastSelectedNotebookId ?: settings.defaultNotebookId
