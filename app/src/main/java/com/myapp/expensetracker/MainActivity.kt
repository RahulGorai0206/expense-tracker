package com.myapp.expensetracker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.myapp.expensetracker.ui.components.APP_LOCK_GRACE_MS
import com.myapp.expensetracker.ui.components.LockedScreen
import com.myapp.expensetracker.ui.components.rememberHaptics
import com.myapp.expensetracker.ui.components.isAppLockEnabled
import com.myapp.expensetracker.ui.components.promptForUnlock
import androidx.activity.compose.BackHandler
import kotlin.math.hypot
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapp.expensetracker.ui.screens.*
import com.myapp.expensetracker.ui.theme.LedgerTheme
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// FragmentActivity rather than ComponentActivity: BiometricPrompt requires it
// for the app lock. Compose is unaffected — FragmentActivity extends
// ComponentActivity, so setContent and enableEdgeToEdge behave identically.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        GoogleSheetsLogger.init(this)

        setContent {
            val context = LocalContext.current
            val sharedPrefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
            val systemInDarkTheme = isSystemInDarkTheme()
            
            var followSystemTheme by remember { 
                mutableStateOf(sharedPrefs.getBoolean("follow_system_theme", true)) 
            }
            var darkTheme by remember { 
                mutableStateOf(sharedPrefs.getBoolean("dark_theme", true)) 
            }

            val currentTheme = if (followSystemTheme) systemInDarkTheme else darkTheme

            var showBrandedSplash by rememberSaveable {
                mutableStateOf(savedInstanceState == null)
            }

            LaunchedEffect(Unit) {
                if (!showBrandedSplash) return@LaunchedEffect

                // Hold the brand only until the app has actually drawn a frame,
                // with a short floor so it doesn't flash past on fast devices.
                //
                // This replaced a flat delay(1700), which blocked every cold
                // start for 1.7s regardless of whether the UI was ready — by far
                // the largest source of perceived slowness in the app.
                val minimumVisibleMs = 450L
                val startedAt = System.currentTimeMillis()
                withFrameNanos { /* resumes once the first frame is produced */ }
                val remaining = minimumVisibleMs - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) delay(remaining)

                showBrandedSplash = false
            }
            
            // App lock.
            //
            // While locked, the app content is NOT composed at all. An overlay is
            // not enough: Compose dialogs and bottom sheets render in their own
            // window, so a sheet left open underneath (e.g. the split
            // add-member sheet after picking a contact) drew on top of the lock
            // screen. Content is composed again only for the unlock reveal, so
            // there is something to reveal.
            var locked by rememberSaveable { mutableStateOf(isAppLockEnabled(context)) }
            var revealing by remember { mutableStateOf(false) }
            var promptInFlight by remember { mutableStateOf(false) }
            var backgroundedAt by rememberSaveable { mutableLongStateOf(0L) }
            val unlockReveal = remember { Animatable(0f) }
            val unlockScope = rememberCoroutineScope()

            fun requestUnlock() {
                if (promptInFlight) return
                promptInFlight = true
                promptForUnlock(
                    activity = this@MainActivity,
                    onSuccess = {
                        promptInFlight = false
                        unlockScope.launch {
                            // Compose the app behind the lock screen, then burst
                            // outward from the centre to uncover it.
                            revealing = true
                            unlockReveal.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(620, easing = FastOutSlowInEasing)
                            )
                            locked = false
                            revealing = false
                            unlockReveal.snapTo(0f)
                        }
                    },
                    onFailure = { promptInFlight = false }
                )
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            // Note the time instead of locking outright, and skip
                            // it entirely while our own prompt is up — the device
                            // credential fallback stops the activity, which would
                            // otherwise re-arm the lock mid-authentication.
                            if (isAppLockEnabled(context) && !locked && !promptInFlight) {
                                backgroundedAt = System.currentTimeMillis()
                            }
                        }

                        // Prompted on RESUME, not during composition:
                        // BiometricPrompt is fragment-backed and throws if the
                        // activity isn't ready, which previously left
                        // promptInFlight stuck true and made every later tap of
                        // Unlock do nothing.
                        Lifecycle.Event.ON_RESUME -> {
                            if (isAppLockEnabled(context)) {
                                val awayFor = if (backgroundedAt == 0L) 0L
                                else System.currentTimeMillis() - backgroundedAt
                                if (!locked && awayFor >= APP_LOCK_GRACE_MS) {
                                    locked = true
                                }
                                backgroundedAt = 0L
                                if (locked) requestUnlock()
                            }
                        }

                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LedgerTheme(darkTheme = currentTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (!locked || revealing) {
                    MainScreen(
                        isDarkTheme = darkTheme,
                        onDarkThemeChange = {
                            darkTheme = it
                            sharedPrefs.edit { putBoolean("dark_theme", it) }
                        },
                        followSystemTheme = followSystemTheme,
                        onFollowSystemThemeChange = {
                            followSystemTheme = it
                            sharedPrefs.edit { putBoolean("follow_system_theme", it) }
                        }
                    )

                    AnimatedVisibility(
                        visible = showBrandedSplash && !locked,
                        enter = fadeIn(animationSpec = tween(220)),
                        exit = fadeOut(animationSpec = tween(420))
                    ) {
                        BrandedSplashScreen()
                    }
                    }

                    if (locked) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // Offscreen compositing is required for the
                                    // Clear blend below to punch a hole in this
                                    // layer rather than the whole window.
                                    compositingStrategy = CompositingStrategy.Offscreen
                                    val progress = unlockReveal.value
                                    val magnify = 1f + progress * 0.18f
                                    scaleX = magnify
                                    scaleY = magnify
                                    alpha = 1f - (progress * progress) * 0.35f
                                }
                                .drawWithContent {
                                    drawContent()
                                    // A growing circular cut-out from the centre:
                                    // the lock screen is removed outward from the
                                    // middle rather than fading as a whole.
                                    val reach = hypot(size.width, size.height) / 2f * 1.08f
                                    drawCircle(
                                        color = Color.Transparent,
                                        radius = reach * unlockReveal.value,
                                        center = center,
                                        blendMode = BlendMode.Clear
                                    )
                                }
                        ) {
                            LockedScreen(onUnlockClick = { requestUnlock() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    isDarkTheme: Boolean, 
    onDarkThemeChange: (Boolean) -> Unit,
    followSystemTheme: Boolean,
    onFollowSystemThemeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    var isSetupComplete by remember { mutableStateOf(sharedPrefs.getBoolean("is_setup_complete", false)) }

    AnimatedContent(
        targetState = isSetupComplete,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(animationSpec = tween(520)) { it / 3 } +
                        fadeIn(animationSpec = tween(420)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(420)) { -it / 5 } +
                                fadeOut(animationSpec = tween(260))
                    )
            } else {
                (fadeIn(animationSpec = tween(300)))
                    .togetherWith(fadeOut(animationSpec = tween(240)))
            }
        },
        label = "SetupToAppTransition"
    ) { setupComplete ->
        if (!setupComplete) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                SetupScreen(onSetupComplete = {
                    isSetupComplete = true
                })
            }
        } else {
            MainAppContent(
                isDarkTheme = isDarkTheme,
                onDarkThemeChange = onDarkThemeChange,
                followSystemTheme = followSystemTheme,
                onFollowSystemThemeChange = onFollowSystemThemeChange
            )
        }
    }
}

@Composable
private fun MainAppContent(
    isDarkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    followSystemTheme: Boolean,
    onFollowSystemThemeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Update widget whenever app is minimized or backgrounded
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Use WorkManager for background reliability
                enqueueWidgetUpdate(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var selectedSplitEventId by remember { mutableStateOf<Long?>(null) }
    var selectedPersonId by remember { mutableStateOf<Long?>(null) }
    var selectedPotId by remember { mutableStateOf<Long?>(null) }

    // Keep reference to the last selected transaction for exit animation
    var lastSelectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    if (selectedTransaction != null) {
        lastSelectedTransaction = selectedTransaction
    }
    var lastSelectedSplitEventId by remember { mutableStateOf<Long?>(null) }
    if (selectedSplitEventId != null) {
        lastSelectedSplitEventId = selectedSplitEventId
    }
    var lastSelectedPersonId by remember { mutableStateOf<Long?>(null) }
    if (selectedPersonId != null) {
        lastSelectedPersonId = selectedPersonId
    }
    var lastSelectedPotId by remember { mutableStateOf<Long?>(null) }
    if (selectedPotId != null) {
        lastSelectedPotId = selectedPotId
    }

    // ── Predictive back ─────────────────────────────────────────────
    // Dismissing a detail screen follows the finger: the overlay shrinks and
    // slides as the gesture progresses, so the user can see where back leads
    // and abandon it. Committing the gesture dismisses; cancelling springs back.
    val detailVisible = selectedTransaction != null || selectedSplitEventId != null ||
        selectedPersonId != null || selectedPotId != null
    val backProgress = remember { Animatable(0f) }

    PredictiveBackHandler(enabled = detailVisible) { events ->
        try {
            events.collect { event -> backProgress.snapTo(event.progress) }
            // Flow completed without cancellation — the gesture was committed.
            // Only one detail is ever open at a time; checked in the order they
            // can be opened so the innermost always wins.
            when {
                selectedTransaction != null -> selectedTransaction = null
                selectedSplitEventId != null -> selectedSplitEventId = null
                selectedPersonId != null -> selectedPersonId = null
                else -> selectedPotId = null
            }
            backProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            // Gesture abandoned — ease the peek back rather than snapping.
            backProgress.animateTo(0f, tween(220))
        }
    }

    // Only reachable when no detail is open, so the two handlers never contend.
    BackHandler(enabled = !detailVisible && pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0, animationSpec = tween(400))
        }
    }

    // ── Bottom nav hide-on-scroll ───────────────────────────────────
    // The nav bar height + padding ≈ 100dp. We track cumulative scroll
    // delta and translate the bar off-screen when scrolling down.
    val navBarHeightPx = with(LocalDensity.current) { 100.dp.toPx() }
    var navBarOffsetPx by remember { mutableFloatStateOf(0f) }

    // Reset nav bar when switching tabs
    LaunchedEffect(pagerState.currentPage) {
        navBarOffsetPx = 0f
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // When scrolling UP, show the bar immediately
                if (available.y > 0) {
                    val newOffset = navBarOffsetPx - available.y
                    navBarOffsetPx = newOffset.coerceIn(0f, navBarHeightPx * 2)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // When scrolling DOWN, hide the bar ONLY if the child consumed some scroll
                // (meaning there was actual scrollable content)
                if (consumed.y < 0) {
                    val newOffset = navBarOffsetPx - consumed.y
                    navBarOffsetPx = newOffset.coerceIn(0f, navBarHeightPx * 2)
                }
                return Offset.Zero
            }
        }
    }

    val animatedNavOffset by animateFloatAsState(
        targetValue = navBarOffsetPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navOffset"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0) // Force drawing behind bars
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            // === Layer 1: Main content — ALWAYS in composition tree ===
            // This ensures scroll position, data state, etc. are never lost
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .nestedScroll(nestedScrollConnection)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 1, not 2: this composed up to five tabs at once, including
                    // the two largest screens in the app. One neighbour is
                    // enough to keep swipes smooth.
                    beyondViewportPageCount = 1
                ) { targetTab ->
                    when (targetTab) {
                        0 -> HomeScreen(
                            onTransactionClick = { selectedTransaction = it },
                            onSeeAllClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(
                                        1,
                                        animationSpec = tween(400)
                                    )
                                }
                            },
                            onSettingsClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(4, animationSpec = tween(400))
                                }
                            }
                        )

                        1 -> TransactionScreen(onTransactionClick = { selectedTransaction = it })
                        2 -> LedgersScreen(
                            onEventClick = { selectedSplitEventId = it },
                            onPersonClick = { selectedPersonId = it },
                            onPotClick = { selectedPotId = it }
                        )

                        3 -> AnalyticsScreen()
                        4 -> SettingsScreen(
                            isDarkTheme = isDarkTheme,
                            onDarkThemeChange = onDarkThemeChange,
                            followSystemTheme = followSystemTheme,
                            onFollowSystemThemeChange = onFollowSystemThemeChange
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .offset { IntOffset(0, animatedNavOffset.roundToInt()) },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(100.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .padding(vertical = 8.dp, horizontal = 8.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavItem(pagerState.targetPage == 0, Icons.Default.Home, "Home") {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0, animationSpec = tween(400))
                            }
                        }
                        NavItem(
                            pagerState.targetPage == 1,
                            Icons.AutoMirrored.Filled.ReceiptLong,
                            "History"
                        ) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1, animationSpec = tween(400))
                            }
                        }
                        NavItem(
                            pagerState.targetPage == 2,
                            Icons.Default.AccountBalanceWallet,
                            "Ledgers"
                        ) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2, animationSpec = tween(400))
                            }
                        }
                        NavItem(
                            pagerState.targetPage == 3,
                            Icons.Default.Analytics,
                            "Analytics"
                        ) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(3, animationSpec = tween(400))
                            }
                        }
                        NavItem(
                            pagerState.targetPage == 4,
                            Icons.Default.Settings,
                            "Settings"
                        ) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(4, animationSpec = tween(400))
                            }
                        }
                    }
                }
            }

            // === Layer 2: Detail screen overlay — slides in/out on top ===
            AnimatedVisibility(
                visible = selectedTransaction != null,
                modifier = Modifier.graphicsLayer {
                    // Follows the back gesture: ease away from the edge so the
                    // screen underneath is revealed progressively.
                    val progress = backProgress.value
                    translationX = progress * size.width * 0.18f
                    scaleX = 1f - progress * 0.08f
                    scaleY = 1f - progress * 0.08f
                    alpha = 1f - progress * 0.15f
                },
                enter = slideInHorizontally(animationSpec = tween(500)) { it } + fadeIn(
                    animationSpec = tween(500)
                ),
                exit = slideOutHorizontally(animationSpec = tween(500)) { it } + fadeOut(
                    animationSpec = tween(500)
                )
            ) {
                lastSelectedTransaction?.let { transaction ->
                    TransactionDetailScreen(
                        initialTransaction = transaction,
                        onBack = { selectedTransaction = null }
                    )
                }
            }

            AnimatedVisibility(
                visible = selectedSplitEventId != null,
                modifier = Modifier.graphicsLayer {
                    // Follows the back gesture: ease away from the edge so the
                    // screen underneath is revealed progressively.
                    val progress = backProgress.value
                    translationX = progress * size.width * 0.18f
                    scaleX = 1f - progress * 0.08f
                    scaleY = 1f - progress * 0.08f
                    alpha = 1f - progress * 0.15f
                },
                enter = slideInHorizontally(animationSpec = tween(500)) { it } + fadeIn(
                    animationSpec = tween(500)
                ),
                exit = slideOutHorizontally(animationSpec = tween(500)) { it } + fadeOut(
                    animationSpec = tween(500)
                )
            ) {
                lastSelectedSplitEventId?.let { eventId ->
                    SplitEventDetailScreen(
                        eventId = eventId,
                        onBack = { selectedSplitEventId = null }
                    )
                }
            }

            AnimatedVisibility(
                visible = selectedPersonId != null,
                modifier = Modifier.graphicsLayer {
                    val progress = backProgress.value
                    translationX = progress * size.width * 0.18f
                    scaleX = 1f - progress * 0.08f
                    scaleY = 1f - progress * 0.08f
                    alpha = 1f - progress * 0.15f
                },
                enter = slideInHorizontally(animationSpec = tween(500)) { it } + fadeIn(
                    animationSpec = tween(500)
                ),
                exit = slideOutHorizontally(animationSpec = tween(500)) { it } + fadeOut(
                    animationSpec = tween(500)
                )
            ) {
                lastSelectedPersonId?.let { personId ->
                    PersonDetailScreen(
                        personId = personId,
                        onBack = { selectedPersonId = null }
                    )
                }
            }

            AnimatedVisibility(
                visible = selectedPotId != null,
                modifier = Modifier.graphicsLayer {
                    val progress = backProgress.value
                    translationX = progress * size.width * 0.18f
                    scaleX = 1f - progress * 0.08f
                    scaleY = 1f - progress * 0.08f
                    alpha = 1f - progress * 0.15f
                },
                enter = slideInHorizontally(animationSpec = tween(500)) { it } + fadeIn(
                    animationSpec = tween(500)
                ),
                exit = slideOutHorizontally(animationSpec = tween(500)) { it } + fadeOut(
                    animationSpec = tween(500)
                )
            ) {
                lastSelectedPotId?.let { potId ->
                    PotDetailScreen(
                        potId = potId,
                        onBack = { selectedPotId = null }
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.NavItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()

    // Wrapped once here so all five tabs feel identical. Re-tapping the current
    // tab stays silent — nothing changed, so there is nothing to confirm.
    val onNavClick: () -> Unit = {
        if (!selected) haptics.tick()
        onClick()
    }

    val scale by animateFloatAsState(
        targetValue = if (selected) 1.0f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "NavScale"
    )

    val contentColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(300),
        label = "NavColor"
    )

    val containerColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "NavContainerColor"
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .height(58.dp)
            .padding(horizontal = 1.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(100.dp))
            .background(containerColor)
            .clickable(onClick = onNavClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(if (selected) 23.dp else 21.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.sp
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
