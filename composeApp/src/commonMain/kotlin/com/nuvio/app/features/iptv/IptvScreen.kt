package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun IptvScreen(
    topChromePadding: Dp?,
    onPlay: (title: String, url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val account = IptvUiState.account
    Box(modifier.fillMaxSize().padding(top = topChromePadding ?: 0.dp)) {
        if (account == null) {
            IptvLoginForm(onSaved = { IptvUiState.account = it })
        } else {
            IptvBrowser(account, onPlay)
        }
    }
}

@Composable
private fun IptvLoginForm(onSaved: (IptvAccount) -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val acc = IptvAccount(normalizeServer(server.trim()), user.trim(), pass.trim())
        if (acc.server.isEmpty() || acc.username.isEmpty() || acc.password.isEmpty()) {
            error = "Fill in server, username and password"; return
        }
        busy = true; error = null
        scope.launch {
            val client = XtreamClient(acc)
            try {
                client.authenticate()
                IptvStorage.save(Json.encodeToString(acc))
                onSaved(acc)
            } catch (e: IptvException) {
                error = e.message
            } finally {
                client.close(); busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("IPTV (Xtream Codes)", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        val w = Modifier.width(420.dp)
        OutlinedTextField(server, { server = it }, w, label = { Text("Server URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, w, label = { Text("Username") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, w, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::submit, enabled = !busy, modifier = w) { Text(if (busy) "Checking..." else "Save") }
    }
}
