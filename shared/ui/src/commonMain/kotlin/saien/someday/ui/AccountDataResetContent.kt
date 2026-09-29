package saien.someday.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import saien.someday.domain.settings.AccountDataReplacementMode
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AccountDataResetReview
import saien.someday.ui.i18n.rememberAccountResetUiStrings
import saien.someday.ui.settings.AccountResetUiOperation
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.SettingsUiState
import saien.someday.ui.settings.message

/** Forms are deliberately remember-only: no saved-state password or recovery secret. */
@Composable
internal fun AccountDataResetContent(state: SettingsUiState, controller: SettingsUiController, actionScope: CoroutineScope) {
    val reset = state.sync.accountReset
    if (!reset.supported) return
    val strings = rememberAccountResetUiStrings()
    val snapshot = reset.snapshot
    val review = snapshot?.review
    val canKeepOffline = state.sync.operation == null && !snapshot.let { it?.offlineEditing == true } &&
        reset.operation !in setOf(AccountResetUiOperation.KeepingOffline, AccountResetUiOperation.Replacing)
    var remoteReview by remember { mutableStateOf<AccountDataResetReview?>(null) }
    var localReview by remember { mutableStateOf<AccountDataResetReview?>(null) }
    var authenticateReview by remember { mutableStateOf<AccountDataResetReview?>(null) }
    var offlineReview by remember { mutableStateOf<AccountDataResetReview?>(null) }
    var password by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var discardConfirmed by remember { mutableStateOf(false) }
    var replacementMode by remember { mutableStateOf(AccountDataReplacementMode.Fresh) }
    LaunchedEffect(controller, state.sync.connection) { controller.loadAccountResetState() }
    LaunchedEffect(review?.id) {
        if (remoteReview?.id != review?.id) remoteReview = null
        if (localReview?.id != review?.id) localReview = null
        if (authenticateReview?.id != review?.id) authenticateReview = null
        if (offlineReview?.id != review?.id) offlineReview = null
        password = ""; phrase = ""; secret = ""; discardConfirmed = false
    }
    fun dismiss() {
        remoteReview = null; localReview = null; authenticateReview = null; offlineReview = null
        password = ""; phrase = ""; secret = ""; discardConfirmed = false
        actionScope.launch { controller.cancelAccountResetReview() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.dangerZone, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(strings.title, style = MaterialTheme.typography.titleLarge)
            snapshot?.endpoint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            snapshot?.accountEmail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val status = when {
                reset.operation == AccountResetUiOperation.Submitting -> strings.submitting
                reset.busy -> strings.checking
                snapshot?.offlineEditing == true -> strings.offlineTitle
                snapshot?.phase == AccountDataResetPhase.OutcomeUnknown -> strings.unknownTitle
                snapshot?.phase == AccountDataResetPhase.RemoteCommittedLocalPending -> strings.committedTitle
                snapshot?.phase == AccountDataResetPhase.ResetRequired -> strings.changedTitle
                snapshot?.phase == AccountDataResetPhase.LocalReady -> strings.ready
                snapshot?.phase == AccountDataResetPhase.Unavailable && snapshot.issue == null -> strings.unavailable
                else -> null
            }
            status?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            when (snapshot?.phase) {
                AccountDataResetPhase.OutcomeUnknown -> Text(strings.unknownBody)
                AccountDataResetPhase.RemoteCommittedLocalPending -> Text(strings.committedBody)
                AccountDataResetPhase.ResetRequired -> Text(strings.changedBody)
                else -> Unit
            }
            if (snapshot?.offlineEditing == true) Text(strings.offlineWarning)
            snapshot?.issue?.let { Text(it.message(strings), color = MaterialTheme.colorScheme.error) }
            if (snapshot?.canExport == true) {
                OutlinedButton(onClick = { actionScope.launch { controller.runLocalExport() } }, modifier = Modifier.fillMaxWidth()) { Text(strings.exportAction) }
                Text(strings.exportWarning, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { actionScope.launch { controller.refreshAccountReset() } }, enabled = !state.sync.busy, modifier = Modifier.fillMaxWidth()) { Text(strings.refreshAction) }
            if (review != null) {
                if (snapshot.resetAvailable || snapshot.phase == AccountDataResetPhase.OutcomeUnknown) {
                    Button(onClick = { remoteReview = review; password = ""; phrase = "" }, enabled = !state.sync.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                        Text(if (snapshot.phase == AccountDataResetPhase.OutcomeUnknown) strings.retryAction else strings.reviewAction)
                    }
                }
                if (snapshot.operationId != null) {
                    OutlinedButton(onClick = { actionScope.launch { controller.reconcileAccountReset(review) } }, enabled = !state.sync.busy, modifier = Modifier.fillMaxWidth()) { Text(strings.checkAction) }
                }
                if (reset.blocksSync || reset.operation == AccountResetUiOperation.Submitting) {
                    OutlinedButton(onClick = { offlineReview = review }, enabled = canKeepOffline, modifier = Modifier.fillMaxWidth()) { Text(strings.offlineAction) }
                }
                if (reset.blocksSync || snapshot.issue == saien.someday.domain.settings.AccountDataResetIssue.SignInRequired) {
                    OutlinedButton(onClick = { authenticateReview = review; password = "" }, enabled = !state.sync.busy, modifier = Modifier.fillMaxWidth()) { Text(strings.authenticateAction) }
                }
                if (snapshot.canReplaceLocal) {
                    HorizontalDivider()
                    Text(strings.replaceTitle, style = MaterialTheme.typography.titleMedium)
                    Text(strings.replaceWarning)
                    AccountDataReplacementMode.entries.forEach { mode ->
                        OutlinedButton(onClick = { localReview = review; replacementMode = mode; secret = ""; password = ""; discardConfirmed = false }, enabled = !state.sync.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(when (mode) { AccountDataReplacementMode.Fresh -> strings.freshAction; AccountDataReplacementMode.Pair -> strings.pairAction; AccountDataReplacementMode.Recover -> strings.recoverAction })
                        }
                    }
                }
            }
        }
    }
    remoteReview?.let { captured ->
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = ::dismiss,
            title = { Text(strings.title) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(captured.endpoint); Text(captured.accountEmail)
                    Text(strings.scopeWarning); Text(strings.irreversibleWarning); Text(strings.retentionWarning); Text(strings.exportWarning)
                    Text(strings.confirmationPhrase, style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(phrase, { phrase = it.take(120) }, label = { Text(strings.phrasePrompt) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth())
                    AccountResetPasswordField(password, { password = it }, strings.passwordLabel)
                }
            },
            confirmButton = { TextButton(onClick = {
                val entered = password; val confirmation = phrase
                password = ""; phrase = ""; remoteReview = null
                actionScope.launch { controller.submitAccountReset(captured, entered, confirmation) }
            }, enabled = password.isNotBlank() && phrase == strings.confirmationPhrase && !state.sync.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if (captured.operationId == null) strings.submitAction else strings.retryAction)
            } },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(strings.cancel) } },
        )
    }
    authenticateReview?.let { captured ->
        AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = ::dismiss, title = { Text(strings.authenticateAction) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(captured.endpoint); Text(captured.accountEmail); Text(strings.signInRequired)
                AccountResetPasswordField(password, { password = it }, strings.passwordLabel)
            } },
            confirmButton = { TextButton(onClick = { val entered = password; password = ""; authenticateReview = null; actionScope.launch { controller.reauthenticateAccountReset(captured, entered) } }, enabled = password.isNotBlank() && !state.sync.busy) { Text(strings.authenticateAction) } },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(strings.cancel) } })
    }
    offlineReview?.let { captured ->
        AlertDialog(onDismissRequest = ::dismiss, title = { Text(strings.offlineAction) }, text = { Text(strings.offlineWarning) },
            confirmButton = { TextButton(onClick = { offlineReview = null; actionScope.launch { controller.keepAccountCopyOffline(captured) } }, enabled = canKeepOffline) { Text(strings.offlineAction) } },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(strings.cancel) } })
    }
    localReview?.let { captured ->
        AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = ::dismiss, title = { Text(strings.replaceTitle) },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(captured.endpoint); Text(captured.accountEmail); Text(strings.replaceWarning); Text(strings.exportWarning)
                Text(when (replacementMode) { AccountDataReplacementMode.Fresh -> strings.freshAction; AccountDataReplacementMode.Pair -> strings.pairAction; AccountDataReplacementMode.Recover -> strings.recoverAction }, style = MaterialTheme.typography.titleMedium)
                if (replacementMode != AccountDataReplacementMode.Fresh) {
                    OutlinedTextField(secret, { secret = it.take(160) }, label = { Text(if (replacementMode == AccountDataReplacementMode.Pair) strings.pairingLabel else strings.recoveryLabel) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done))
                }
                Row(Modifier.fillMaxWidth().toggleable(value = discardConfirmed, role = Role.Checkbox, onValueChange = { discardConfirmed = it })) {
                    Checkbox(discardConfirmed, onCheckedChange = null)
                    Text(strings.discardConsent, modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
            } },
            confirmButton = { TextButton(onClick = {
                val entered = secret; val mode = replacementMode
                secret = ""; localReview = null; discardConfirmed = false
                actionScope.launch { controller.replaceAccountWorkspace(captured, mode, true, entered) }
            }, enabled = discardConfirmed && (replacementMode == AccountDataReplacementMode.Fresh || secret.isNotBlank()) && !state.sync.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.replaceAction) } },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(strings.cancel) } })
    }
}

@Composable
private fun AccountResetPasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value, { onValueChange(it.take(1024)) }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth())
}
