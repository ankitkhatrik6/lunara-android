package com.lunara.music.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lunara.music.service.innertube.YouTubeMusicLogin
import com.lunara.music.service.innertube.YouTubeSession
import com.lunara.music.ui.theme.BackgroundDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginScreen(
    onNavigateBack: () -> Unit,
    onSignedIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose { runCatching { webView?.destroy() } }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("youtube_login_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Connect YouTube Music", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Sign in with your Google account, then tap Done. " +
                    "Your library, history and uploads unlock inside Lunara.",
                color = Color(0xFFA8A29E)
            )
            Spacer(modifier = Modifier.height(8.dp))
            currentUrl?.let { Text(it, color = Color(0xFF6B7280)) }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        YouTubeMusicLogin.createWebView(ctx) { url -> currentUrl = url }.also {
                            webView = it
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (isChecking) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Color.White)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        isChecking = true
                        message = "Checking session..."
                        val result = withContext(Dispatchers.IO) { YouTubeMusicLogin.captureSession() }
                        val cookie = result?.cookie
                        if (!cookie.isNullOrBlank()) {
                            YouTubeSession.signIn(cookie)
                            result?.accountLabel?.let { YouTubeSession.setAccountLabel(it) }
                            val label = withContext(Dispatchers.IO) { YouTubeSession.describeAccount() }
                            message = if (!label.isNullOrBlank()) "Signed in as $label" else "Signed in"
                            isChecking = false
                            onSignedIn()
                        } else {
                            message = "No YouTube session found yet. Finish signing in, then tap Done again."
                            isChecking = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("youtube_login_done"),
                enabled = !isChecking
            ) {
                Text(if (isChecking) "Checking..." else "Done - Connect")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    webView?.loadUrl("https://music.youtube.com/")
                    message = "Opened YouTube Music. Sign in if asked, then tap Done."
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isChecking
            ) {
                Text("Open YouTube Music")
            }
            if (isChecking) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator()
            }
        }
    }
}
