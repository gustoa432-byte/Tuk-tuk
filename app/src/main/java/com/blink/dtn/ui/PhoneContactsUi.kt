package com.blink.dtn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blink.dtn.contacts.PhoneBookReader
import com.blink.dtn.net.PhoneContactsMatcher
import com.blink.dtn.net.UsersApi
import com.blink.dtn.net.VpsConfig
import com.blink.dtn.ui.theme.TextPrimary
import com.blink.dtn.ui.theme.TextSecondary
import com.blink.dtn.ui.theme.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PhoneBookPhase { Explain, Denied, Loading, Error, Results }

private suspend fun runPhoneSync(
    context: android.content.Context,
    myNodeId: String
): Result<PhoneContactsMatcher.Plan> = withContext(Dispatchers.IO) {
    runCatching {
        VpsConfig.init(context)
        if (!VpsConfig.isConfigured(context)) error("gateway_down")
        if (!com.blink.dtn.auth.AuthSessionStore.hasVpsSession(context)) error("need_session")
        val book = PhoneBookReader.load(context)
        val numbers = PhoneContactsMatcher.normalizeBook(book)
        val hashes = PhoneContactsMatcher.uniqueHashes(numbers)
        val hits = ArrayList<com.blink.dtn.net.PhoneHit>()
        val api = UsersApi(context)
        for (chunk in PhoneContactsMatcher.chunkHashes(hashes)) {
            val resp = api.lookupPhones(chunk).getOrThrow()
            hits += resp.results
        }
        PhoneContactsMatcher.match(numbers, hits, myNodeId)
    }
}

@Composable
fun PhoneContactsPanel(
    viewModel: BLinkViewModel,
    onBack: () -> Unit,
    onOpenedChat: () -> Unit
) {
    val lang by AppLang.lang.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(PhoneBookPhase.Loading) }
    var errorCode by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf(PhoneContactsMatcher.Plan(emptyList(), emptyList())) }
    var writingId by remember { mutableStateOf<String?>(null) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun openSystemPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun sync() {
        phase = PhoneBookPhase.Loading
        errorCode = ""
        scope.launch {
            runPhoneSync(context, viewModel.myNodeId).fold(
                onSuccess = { matched ->
                    plan = matched
                    phase = PhoneBookPhase.Results
                },
                onFailure = {
                    errorCode = if (it.message == "need_session") {
                        "need_session"
                    } else {
                        PhoneContactsMatcher.mapLookupFailure(it)
                    }
                    phase = PhoneBookPhase.Error
                }
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        PhoneContactsPrefs.markExplained(context)
        if (PhoneContactsMatcher.afterPermission(granted) == PhoneContactsMatcher.Gate.Ready) {
            sync()
        } else {
            phase = PhoneBookPhase.Denied
        }
    }

    LaunchedEffect(Unit) {
        val explained = PhoneContactsPrefs.explained(context)
        when (PhoneContactsMatcher.gate(explained, hasPermission())) {
            PhoneContactsMatcher.Gate.NeedExplain -> phase = PhoneBookPhase.Explain
            PhoneContactsMatcher.Gate.Denied -> phase = PhoneBookPhase.Denied
            PhoneContactsMatcher.Gate.NeedPermission ->
                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            PhoneContactsMatcher.Gate.Ready -> sync()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsBackRow(S.phoneContacts(lang), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        when (phase) {
            PhoneBookPhase.Explain -> {
                Text(S.phoneContactsFindTitle(lang), color = TextPrimary, style = Typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(S.phoneContactsFindBody(lang), color = TextSecondary, style = Typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                QqButton(onClick = {
                    PhoneContactsPrefs.markExplained(context)
                    permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) {
                    Text(S.phoneContactsContinue(lang), color = TextPrimary)
                }
            }
            PhoneBookPhase.Denied -> {
                Text(S.phoneContactsDeniedTitle(lang), color = TextPrimary, style = Typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(S.phoneContactsDeniedBody(lang), color = TextSecondary, style = Typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                QqButton(onClick = { openSystemPermissionSettings() }) {
                    Text(S.phoneContactsOpenSettings(lang), color = TextPrimary)
                }
            }
            PhoneBookPhase.Loading -> {
                Text(S.phoneContacts(lang), color = TextSecondary, style = Typography.bodySmall)
            }
            PhoneBookPhase.Error -> {
                Text(
                    if (errorCode == "need_session") S.usernameNeedSignIn(lang)
                    else S.phoneContactsLookupFailed(lang),
                    color = TextPrimary,
                    style = Typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(S.phoneContactsDeniedBody(lang), color = TextSecondary, style = Typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                QqButton(onClick = {
                    if (hasPermission()) sync() else permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) {
                    Text(S.phoneContactsRefresh(lang), color = TextPrimary)
                }
            }
            PhoneBookPhase.Results -> {
                QqButton(onClick = { sync() }) {
                    Text(S.phoneContactsRefresh(lang), color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (plan.inQq.isEmpty() && plan.invite.isEmpty()) {
                    Text(S.phoneContactsEmpty(lang), color = TextSecondary, style = Typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                ) {
                    if (plan.inQq.isNotEmpty()) {
                        item {
                            Text(
                                S.phoneContactsInQq(lang),
                                color = TextSecondary,
                                style = Typography.labelSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        items(plan.inQq, key = { it.nodeId }) { row ->
                            ContactActionRow(
                                name = row.displayName.ifBlank { row.username.ifBlank { row.nodeId } },
                                action = S.phoneContactsWrite(lang),
                                enabled = writingId == null
                            ) {
                                if (writingId != null) return@ContactActionRow
                                writingId = row.nodeId
                                viewModel.addFromPhoneDiscovery(
                                    nodeId = row.nodeId,
                                    publicKey = row.publicKey,
                                    username = row.username,
                                    nick = row.displayName
                                ) { ok, meshId, msg ->
                                    writingId = null
                                    if (ok) {
                                        viewModel.setCurrentDialog(meshId)
                                        onOpenedChat()
                                    } else {
                                        val text = when (msg) {
                                            "key_changed" -> S.keyChangedBody(lang)
                                            else -> S.phoneContactsLookupFailed(lang)
                                        }
                                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                                        if (msg == "key_changed" && meshId.isNotBlank()) {
                                            viewModel.setCurrentDialog(meshId)
                                            onOpenedChat()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (plan.invite.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                S.phoneContactsInviteSection(lang),
                                color = TextSecondary,
                                style = Typography.labelSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        items(plan.invite, key = { "inv-${it.contactId}" }) { row ->
                            ContactActionRow(
                                name = row.displayName,
                                action = S.phoneContactsInvite(lang)
                            ) {
                                shareText(
                                    context,
                                    S.qqInviteShareText(lang),
                                    S.inviteFriends(lang)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactActionRow(
    name: String,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            name,
            color = TextPrimary,
            style = Typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Text(
            action,
            color = if (enabled) TextPrimary else TextSecondary,
            style = Typography.labelLarge,
            modifier = Modifier.bounceClick(onClick)
        )
    }
}
