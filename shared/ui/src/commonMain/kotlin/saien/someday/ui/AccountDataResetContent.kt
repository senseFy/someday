package saien.someday.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import saien.someday.domain.settings.AccountDataReplacementMode
import saien.someday.domain.settings.AccountDataResetIssue
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AccountDataResetReview
import saien.someday.ui.designsystem.SomedayDesignDefaults
import saien.someday.ui.i18n.rememberAccountResetUiStrings
import saien.someday.ui.settings.AccountResetUiOperation
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.SettingsUiState
import saien.someday.ui.settings.SyncConnectionUi
import saien.someday.ui.settings.SyncIssueAction
import saien.someday.ui.settings.message
import org.jetbrains.compose.resources.stringResource
import saien.someday.ui.resources.Res
import saien.someday.ui.resources.common_email
import saien.someday.ui.resources.sync_retry
import saien.someday.ui.resources.sync_status_syncing

private enum class AccountResetStep { Overview, RemoteConfirmation, Authentication, Replacement, Offline }

/** Forms are deliberately remember-only: no saved-state password or recovery secret. */
@Composable
internal fun AccountDataResetContent(state: SettingsUiState, controller: SettingsUiController, actionScope: CoroutineScope) {
    val reset = state.sync.accountReset
    if (!reset.supported) return
    val strings = rememberAccountResetUiStrings()
    val snapshot = reset.snapshot
    val review = snapshot?.review
    val entryTitle = if (state.sync.needsAccountDataResolution) strings.resumeTitle else strings.title
    val canKeepOffline = state.sync.operation == null && snapshot?.offlineEditing != true &&
        reset.operation !in setOf(AccountResetUiOperation.KeepingOffline, AccountResetUiOperation.Replacing)
    var showDetails by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(AccountResetStep.Overview) }
    var capturedReview by remember { mutableStateOf<AccountDataResetReview?>(null) }
    var presentationRevision by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var accountEmailInput by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var discardConfirmed by remember { mutableStateOf(false) }
    var formAttempted by remember { mutableStateOf(false) }
    var replacementMode by remember { mutableStateOf(AccountDataReplacementMode.Fresh) }

    fun clearForm() {
        password = ""; accountEmailInput = ""; phrase = ""; secret = ""; discardConfirmed = false; formAttempted = false
    }
    fun overview() {
        step = AccountResetStep.Overview
        capturedReview = null
        clearForm()
    }
    fun openStep(next: AccountResetStep, target: AccountDataResetReview) {
        if (exporting) return
        presentationRevision++
        clearForm()
        capturedReview = target
        accountEmailInput = target.accountEmail
        step = next
        showDetails = true
    }
    fun dismiss() {
        if (exporting) return
        presentationRevision++
        showDetails = false
        overview()
        actionScope.launch { controller.cancelAccountResetReview() }
    }
    fun exportLocalData() {
        if (exporting) return
        exporting = true
        val revision = ++presentationRevision
        showDetails = false
        overview()
        actionScope.launch {
            var completed = false
            try {
                // Release the Compose dialog before the platform presents its save picker.
                withFrameNanos { }
                controller.runLocalExport()
                completed = true
            } finally {
                if (revision == presentationRevision) {
                    exporting = false
                    // A cancelled coroutine may mean the native picker has not detached.
                    if (completed) showDetails = true
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { presentationRevision++ }
    }
    LaunchedEffect(controller, state.sync.connection) { controller.loadAccountResetState() }
    LaunchedEffect(showDetails) {
        if (showDetails && !state.sync.busy && review != null && !snapshot.requiresAuthentication) {
            controller.refreshAccountReset()
        }
    }
    LaunchedEffect(review?.id, snapshot?.requiresAuthentication) {
        if (capturedReview != null && (capturedReview?.id != review?.id ||
                (snapshot?.requiresAuthentication == true && step in setOf(AccountResetStep.RemoteConfirmation, AccountResetStep.Replacement)))) overview()
    }
    val issueMessage = snapshot?.issue?.message(strings)
    val resetUnavailableMessage = snapshot?.resetUnavailableIssue?.message(strings)
    val status = when {
        state.sync.syncing -> stringResource(Res.string.sync_status_syncing)
        reset.operation == AccountResetUiOperation.Submitting -> strings.submitting
        reset.operation == AccountResetUiOperation.Authenticating -> strings.authenticating
        reset.operation == AccountResetUiOperation.Replacing && snapshot?.phase != AccountDataResetPhase.LocalReady -> strings.replacing
        reset.busy -> strings.checking
        snapshot?.offlineEditing == true -> strings.offlineTitle
        snapshot?.phase == AccountDataResetPhase.OutcomeUnknown -> strings.unknownTitle
        snapshot?.phase == AccountDataResetPhase.RemoteCommittedLocalPending -> strings.committedTitle
        snapshot?.phase == AccountDataResetPhase.ResetRequired -> strings.changedTitle
        snapshot?.phase == AccountDataResetPhase.LocalReady -> strings.ready
        issueMessage != null -> issueMessage
        resetUnavailableMessage != null -> resetUnavailableMessage
        snapshot?.phase == AccountDataResetPhase.Unavailable -> strings.unavailable
        else -> strings.summary
    }
    val stateWarning = when (snapshot?.phase) {
        AccountDataResetPhase.OutcomeUnknown -> strings.unknownBody
        AccountDataResetPhase.RemoteCommittedLocalPending -> strings.committedBody
        AccountDataResetPhase.ResetRequired -> strings.changedBody
        else -> null
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shape = SomedayDesignDefaults.SectionShape,
        color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !exporting, role = Role.Button) {
                if (exporting) return@clickable
                if (snapshot?.resetAvailable == true && !snapshot.requiresAuthentication && review != null && !state.sync.busy) {
                    openStep(AccountResetStep.RemoteConfirmation, review)
                } else {
                    overview(); showDetails = true
                }
            }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = SomedayDesignDefaults.IconShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Lucide.RefreshCw, contentDescription = null, modifier = Modifier.size(19.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entryTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stateWarning != null) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(stateWarning, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                TextButton(onClick = { if (!exporting) { overview(); showDetails = true } }, enabled = !exporting, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(strings.detailsAction)
                }
            }
        }
    }
    if (!showDetails) return
    val captured = capturedReview
    val revision = presentationRevision
    val detailsScrollState = remember(step, snapshot?.phase) { ScrollState(0) }
    LaunchedEffect(snapshot?.issue, step, formAttempted) {
        if (formAttempted && snapshot?.issue != null) detailsScrollState.scrollTo(0)
    }
    AlertDialog(
        modifier = Modifier.imePadding(), onDismissRequest = ::dismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(when (step) {
            AccountResetStep.Overview -> entryTitle
            AccountResetStep.RemoteConfirmation -> strings.title
            AccountResetStep.Authentication -> strings.authenticateAction
            AccountResetStep.Replacement -> strings.replaceTitle
            AccountResetStep.Offline -> strings.offlineAction
        }) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(detailsScrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reset.busy || step == AccountResetStep.Overview) {
                    Text(status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                issueMessage?.takeIf { (step == AccountResetStep.Overview && it != status) || (step != AccountResetStep.Overview && formAttempted) }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                when (step) {
                    AccountResetStep.Overview -> {
                        stateWarning?.let { Text(it) }
                        if (snapshot?.phase == AccountDataResetPhase.LocalReady && resetUnavailableMessage != null) {
                            Text(resetUnavailableMessage, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (snapshot?.phase == AccountDataResetPhase.LocalReady &&
                            (state.sync.issue != null || state.sync.recovery.blocksSync)) {
                            state.sync.issue?.let { Text(it.reason.localizedMessage(), color = MaterialTheme.colorScheme.error) }
                            state.sync.recovery.failureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.sync.connection is SyncConnectionUi.Connected &&
                                (state.sync.issue?.action == SyncIssueAction.RetrySync || state.sync.issue == null)) {
                                Text(strings.syncPending)
                                Button(onClick = { actionScope.launch { controller.runUserSync() } }, enabled = !state.sync.busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.sync_retry)) }
                            }
                        }
                        if (snapshot?.offlineEditing == true) Text(strings.offlineWarning)
                        if (review != null) {
                            if (snapshot.requiresAuthentication && snapshot.issue != AccountDataResetIssue.DeviceRevoked) {
                                Button(onClick = { openStep(AccountResetStep.Authentication, review) }, enabled = !state.sync.busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(strings.authenticateAction) }
                            } else if (snapshot.phase == AccountDataResetPhase.OutcomeUnknown) {
                                Button(onClick = { actionScope.launch { controller.reconcileAccountReset(review) } },
                                    enabled = !state.sync.busy, modifier = Modifier.fillMaxWidth()) { Text(strings.checkAction) }
                                TextButton(onClick = { openStep(AccountResetStep.RemoteConfirmation, review) }, enabled = !state.sync.busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(strings.retryAction) }
                            }
                            if (snapshot.canReplaceLocal && !snapshot.requiresAuthentication && snapshot.issue != AccountDataResetIssue.DeviceRevoked) {
                                Text(strings.replaceTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                                Text(strings.replacementHelp, style = MaterialTheme.typography.bodySmall)
                                AccountDataReplacementMode.entries.forEach { mode ->
                                    OutlinedButton(onClick = { replacementMode = mode; openStep(AccountResetStep.Replacement, review) },
                                        enabled = !state.sync.busy, modifier = Modifier.fillMaxWidth()) {
                                        Text(when (mode) {
                                            AccountDataReplacementMode.Fresh -> strings.freshAction
                                            AccountDataReplacementMode.Pair -> strings.pairAction
                                            AccountDataReplacementMode.Recover -> strings.recoverAction
                                        })
                                    }
                                }
                            }
                            if (snapshot.resetAvailable && !snapshot.requiresAuthentication) {
                                Button(onClick = { openStep(AccountResetStep.RemoteConfirmation, review) }, enabled = !state.sync.busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(strings.reviewAction) }
                            }
                            if (reset.blocksSync || reset.operation == AccountResetUiOperation.Submitting) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TextButton(onClick = { openStep(AccountResetStep.Offline, review) }, enabled = canKeepOffline,
                                    modifier = Modifier.fillMaxWidth()) { Text(strings.offlineAction) }
                            }
                        }
                        if (snapshot?.canExport == true && (reset.blocksSync || reset.operation == AccountResetUiOperation.Submitting)) {
                            OutlinedButton(onClick = ::exportLocalData, enabled = !state.sync.busy && !exporting,
                                modifier = Modifier.fillMaxWidth()) { Text(strings.exportAction) }
                            Text(strings.exportWarning, style = MaterialTheme.typography.bodySmall)
                        }
                        if (snapshot?.phase != AccountDataResetPhase.OutcomeUnknown && snapshot?.requiresAuthentication != true) {
                            TextButton(onClick = { actionScope.launch { controller.refreshAccountReset() } }, enabled = !state.sync.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(strings.refreshAction) }
                        }
                    }
                    AccountResetStep.RemoteConfirmation -> if (captured != null) {
                        AccountResetTarget(captured)
                        Text(strings.scopeWarning); Text(strings.irreversibleWarning)
                        Text(strings.exportWarning, style = MaterialTheme.typography.bodySmall)
                        if (snapshot?.canExport == true) {
                            OutlinedButton(onClick = ::exportLocalData, enabled = !state.sync.busy && !exporting,
                                modifier = Modifier.fillMaxWidth()) { Text(strings.exportAction) }
                        }
                        Text(strings.retentionWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(strings.confirmationPhrase, style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(phrase, { phrase = it.take(120) }, label = { Text(strings.phrasePrompt) }, enabled = !state.sync.busy,
                            singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                        AccountResetPasswordField(password, { password = it }, strings.passwordLabel, enabled = !state.sync.busy)
                    }
                    AccountResetStep.Authentication -> if (captured != null) {
                        AccountResetTarget(captured, showEmail = false)
                        Text(strings.signInRequired)
                        OutlinedTextField(accountEmailInput, { accountEmailInput = it.take(320) }, label = { Text(stringResource(Res.string.common_email)) },
                            enabled = !state.sync.busy, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                        AccountResetPasswordField(password, { password = it }, strings.passwordLabel, enabled = !state.sync.busy)
                    }
                    AccountResetStep.Replacement -> if (captured != null) {
                        AccountResetTarget(captured)
                        Text(strings.replaceWarning)
                        Text(when (replacementMode) {
                            AccountDataReplacementMode.Fresh -> strings.freshHelp
                            AccountDataReplacementMode.Pair -> strings.pairHelp
                            AccountDataReplacementMode.Recover -> strings.recoverHelp
                        })
                        if (replacementMode != AccountDataReplacementMode.Fresh) {
                            OutlinedTextField(secret, { secret = it.take(160) }, enabled = !state.sync.busy,
                                label = { Text(if (replacementMode == AccountDataReplacementMode.Pair) strings.pairingLabel else strings.recoveryLabel) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done))
                        }
                        Row(Modifier.fillMaxWidth().toggleable(value = discardConfirmed, enabled = !state.sync.busy,
                            role = Role.Checkbox, onValueChange = { discardConfirmed = it })) {
                            Checkbox(discardConfirmed, onCheckedChange = null, enabled = !state.sync.busy)
                            Text(strings.discardConsent, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        }
                    }
                    AccountResetStep.Offline -> Text(strings.offlineWarning)
                }
                if (reset.busy && reset.blocksSync && canKeepOffline && step != AccountResetStep.Overview && step != AccountResetStep.Offline && review != null) {
                    TextButton(onClick = { openStep(AccountResetStep.Offline, review) }, modifier = Modifier.fillMaxWidth()) { Text(strings.offlineAction) }
                }
            }
        },
        confirmButton = {
            when (step) {
                AccountResetStep.Overview -> TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = ::dismiss) { Text(strings.close) }
                AccountResetStep.RemoteConfirmation -> TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val target = captured ?: return@TextButton
                    val enteredPassword = password; val enteredPhrase = phrase
                    password = ""; capturedReview = null; step = AccountResetStep.Overview; formAttempted = true
                    actionScope.launch {
                        val succeeded = controller.submitAccountReset(target, enteredPassword, enteredPhrase)
                        if (showDetails && revision == presentationRevision && step == AccountResetStep.Overview) {
                            if (succeeded || controller.state.sync.accountReset.blocksSync) overview()
                            else if (controller.state.sync.accountReset.snapshot?.review?.id == target.id) {
                                capturedReview = target; step = AccountResetStep.RemoteConfirmation; phrase = enteredPhrase; formAttempted = true
                            }
                        }
                    }
                }, enabled = captured != null && password.isNotBlank() && phrase == strings.confirmationPhrase && !state.sync.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (captured?.operationId == null) strings.submitAction else strings.retryAction)
                }
                AccountResetStep.Authentication -> TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val target = captured ?: return@TextButton
                    val enteredPassword = password; val email = accountEmailInput.trim()
                    password = ""; formAttempted = true
                    actionScope.launch {
                        val succeeded = controller.reauthenticateAccountReset(target, enteredPassword, email)
                        if (showDetails && revision == presentationRevision && succeeded) overview()
                    }
                }, enabled = captured != null && password.isNotBlank() && accountEmailInput.isNotBlank() && !state.sync.busy) { Text(strings.authenticateAction) }
                AccountResetStep.Replacement -> TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val target = captured ?: return@TextButton
                    val enteredSecret = secret; val mode = replacementMode
                    discardConfirmed = false; formAttempted = true
                    actionScope.launch {
                        val succeeded = controller.replaceAccountWorkspace(target, mode, true, enteredSecret)
                        if (showDetails && revision == presentationRevision && succeeded) overview()
                        else if (showDetails && revision == presentationRevision && controller.state.sync.accountReset.snapshot?.requiresAuthentication == true) overview()
                    }
                }, enabled = captured != null && discardConfirmed && (replacementMode == AccountDataReplacementMode.Fresh || secret.isNotBlank()) && !state.sync.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.replaceAction) }
                AccountResetStep.Offline -> TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val target = captured ?: return@TextButton
                    formAttempted = true
                    actionScope.launch {
                        if (controller.keepAccountCopyOffline(target) && showDetails && revision == presentationRevision) overview()
                    }
                }, enabled = captured != null && canKeepOffline) { Text(strings.offlineAction) }
            }
        },
        dismissButton = {
            if (step != AccountResetStep.Overview) {
                TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { presentationRevision++; overview() }, enabled = !state.sync.busy) { Text(strings.back) }
            }
        },
    )
}

@Composable
private fun AccountResetTarget(review: AccountDataResetReview, showEmail: Boolean = true) {
    Surface(shape = SomedayDesignDefaults.CellShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showEmail && review.accountEmail.isNotBlank()) Text(review.accountEmail, style = MaterialTheme.typography.titleSmall)
            Text(review.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountResetPasswordField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value, { onValueChange(it.take(1024)) }, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth())
}
