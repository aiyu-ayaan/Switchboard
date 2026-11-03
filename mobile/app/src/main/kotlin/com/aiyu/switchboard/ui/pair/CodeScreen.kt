package com.aiyu.switchboard.ui.pair

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.BuildConfig
import com.aiyu.switchboard.data.CODE_LENGTH
import com.aiyu.switchboard.data.PairSecret
import com.aiyu.switchboard.data.fingerprint
import com.aiyu.switchboard.data.normaliseCode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CodeScreen(vm: AppViewModel, onBack: () -> Unit, onPairing: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val found by vm.connections.discovery.found.collectAsStateWithLifecycle()
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val target by vm.codeTarget.collectAsStateWithLifecycle()
    val candidates = found.values.filter { t -> data.hosts.none { it.id == t.id } }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    LaunchedEffect(candidates.size) {
        if (target == null && candidates.size == 1) vm.codeTarget.value = candidates.first()
    }
    LaunchedEffect(target) { if (target != null) runCatching { focus.requestFocus() } }

    val submit = {
        val t = target
        if (t != null && code.length == CODE_LENGTH) {
            vm.pair(t, PairSecret.Code(code))
            vm.codeTarget.value = null
            onPairing()
        }
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("Enter code") },
                subtitle = { Text("Shown on the computer under Pair a phone") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Computer", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (candidates + listOfNotNull(target).filter { t -> candidates.none { it.id == t.id } }).forEach { t ->
                    FilterChip(
                        selected = target?.id == t.id,
                        onClick = { vm.codeTarget.value = t },
                        label = { Text(t.name) },
                        leadingIcon = { Icon(Icons.Rounded.Computer, null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (candidates.isEmpty() && target == null) {
                Text("No computer found on this network yet. Type its address instead:", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim(); probeError = null },
                    label = { Text("Address (e.g. 192.168.1.15)") },
                    singleLine = true,
                    isError = probeError != null,
                    supportingText = probeError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = address.isNotBlank() && !probing,
                    onClick = {
                        probing = true
                        scope.launch {
                            val t = vm.connections.probe(address, BuildConfig.DEFAULT_PORT)
                            probing = false
                            if (t == null) probeError = "No Switchboard there. Check the address and that it's running."
                            else vm.codeTarget.value = t
                        }
                    },
                ) { Text(if (probing) "Looking…" else "Find") }
            }

            target?.let {
                Text("Fingerprint ${fingerprint(it.pk)} — the computer shows the same.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }

            Text("Code", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            BasicTextField(
                value = code,
                onValueChange = { code = normaliseCode(it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus).semantics { contentDescription = "Pairing code" },
                decorationBox = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in 0 until CODE_LENGTH / 4) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (i in row * 4 until row * 4 + 4) {
                                    val active = i == code.length
                                    val border by animateColorAsState(if (active) cs.primary else cs.outlineVariant, label = "box")
                                    Box(
                                        Modifier.weight(1f).height(60.dp)
                                            .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(14.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(code.getOrNull(i)?.toString() ?: "", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = cs.onSurface)
                                    }
                                }
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = submit, enabled = target != null && code.length == CODE_LENGTH, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text("Pair", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
