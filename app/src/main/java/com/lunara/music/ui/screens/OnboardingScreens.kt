package com.lunara.music.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.R
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.UserPrefEntity
import com.lunara.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(
    onSplashFinished: (isOnboarded: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(1200)
        val db = LunaraDatabase.getDatabase(context)
        val onboarded = db.userPrefDao().getPref("is_onboarded") == "true"
        onSplashFinished(onboarded)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(600)) + scaleIn(tween(600), initialScale = 0.85f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.lunara_logo),
                    contentDescription = "Lunara Logo",
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Lunara",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "All the music, none of the clutter",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA8A29E)
                )
            }
        }
    }
}

data class OnboardStep(
    val title: String,
    val description: String,
    val isLyricsPreview: Boolean = false
)

@Composable
fun OnboardingScreen(
    onFinishOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        OnboardStep(
            title = "All the music, none of the clutter",
            description = "Millions of songs, albums and artists — searched, streamed and downloaded for offline listening, with no ads in the way."
        ),
        OnboardStep(
            title = "Lyrics that keep up",
            description = "Word-by-word synced lyrics from several sources, with translation and romanization when you want to sing along in any language.",
            isLyricsPreview = true
        ),
        OnboardStep(
            title = "Import directly from Spotify",
            description = "Paste any public playlist link from Spotify and Lunara matches all tracks with zero setup or credentials required."
        )
    )

    val pagerState = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        containerColor = BackgroundBlack,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = Color.White,
                    modifier = Modifier
                        .clickable(onClick = onFinishOnboarding)
                        .testTag("onboarding_skip_button")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val step = steps[page]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 3D Phone Mockup (Screenshot 2 & 3)
                    Box(
                        modifier = Modifier
                            .width(260.dp)
                            .height(380.dp)
                            .shadow(28.dp, RoundedCornerShape(32.dp), spotColor = Color.Black)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF141414))
                            .border(2.5.dp, Color(0xFF333333), RoundedCornerShape(32.dp))
                    ) {
                        if (step.isLyricsPreview) {
                            // Synced Lyrics Preview (Screenshot 3)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF181A12))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0x3344403C),
                                    modifier = Modifier.padding(bottom = 20.dp)
                                ) {
                                    Text(
                                        text = "Language",
                                        fontSize = 10.sp,
                                        color = Color(0xFFA8A29E),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Text("Baatein teri,\nraatein-saugaate", fontSize = 14.sp, color = Color(0x40FFFFFF), textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Kyun tera sab\nyeh ho gaya?\nHua kya?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Main kahin bhi\njaata hoon, tum", fontSize = 14.sp, color = Color(0x40FFFFFF), textAlign = TextAlign.Center)
                            }
                        } else {
                            // App Home Mockup (Screenshot 2)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0F0F0F))
                                    .padding(12.dp)
                            ) {
                                // Mini Top Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Text("Lunara", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                // Mini Hero Card
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(75.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(HeroBannerAmber)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                // Mini Search Pill
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(Color(0xFF222222))
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                // Quick picks rows
                                Text("Quick picks", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                repeat(3) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Box(modifier = Modifier.width(90.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444)))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(modifier = Modifier.width(50.dp).height(6.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2E2E2E)))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(36.dp))

                    // Title
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Description
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        ),
                        color = Color(0xFFA8A29E),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Page Indicator & Wide Purple Pill Button (Screenshot 2 & 3)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(steps.size) { iteration ->
                        val isCurrent = pagerState.currentPage == iteration
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isCurrent) Color(0xFF67E8F9) else Color(0xFF333333))
                                .height(6.dp)
                                .width(if (isCurrent) 22.dp else 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                // Wide Purple Button (#7C5CFC)
                Button(
                    onClick = {
                        if (pagerState.currentPage < steps.size - 1) {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onFinishOnboarding()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("onboarding_continue_button"),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) {
                    Text(
                        text = if (pagerState.currentPage == steps.size - 1) "Get Started" else "Next",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
fun NameSetupScreen(
    onNameSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nameInput by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("name_setup_screen"),
        containerColor = BackgroundBlack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = AccentLime,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "What's your name?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Personalize your home screen greetings with your name. No account required.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFA8A29E),
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    if (isError && it.isNotBlank()) isError = false
                },
                placeholder = { Text("Your name", color = Color(0xFF6E6E6E)) },
                singleLine = true,
                isError = isError,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentLime,
                    unfocusedBorderColor = Color(0xFF333333),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF161616),
                    unfocusedContainerColor = Color(0xFF161616)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("name_input_field")
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    val trimmed = nameInput.trim()
                    if (trimmed.isEmpty()) {
                        isError = true
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val db = LunaraDatabase.getDatabase(context)
                            db.userPrefDao().setPref(UserPrefEntity("user_name", trimmed))
                            db.userPrefDao().setPref(UserPrefEntity("is_onboarded", "true"))
                            launch(Dispatchers.Main) {
                                onNameSaved()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("name_continue_button"),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentLime)
            ) {
                Text(
                    text = "Continue",
                    color = AccentLimeDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
