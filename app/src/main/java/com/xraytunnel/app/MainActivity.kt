
package com.xraytunnel.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import com.xraytunnel.app.core.ConnLog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.xraytunnel.app.data.ServerFile
import java.io.File
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xraytunnel.app.data.Defaults
import com.xraytunnel.app.data.DnsPrefs
import com.xraytunnel.app.ui.NpvGreen
import com.xraytunnel.app.data.Server
import com.xraytunnel.app.data.ServerExporter
import com.xraytunnel.app.data.TimeCredit
import com.xraytunnel.app.ui.*
import com.xraytunnel.app.vpn.VpnState
import com.xraytunnel.app.vpn.XrayVpnService
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var connectInterstitial: InterstitialAd? = null

    // ---- App Open ad state ----
    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoadedAt = 0L
    private var appOpenLoading = false
    private var adsInitialized = false
    private var fullScreenAdShowing = false
    private var wentToBackground = false

    /** يُفعَّل قبل فتح واجهة خارجية (اختيار ملف/مشاركة/إذن VPN) كي لا تُحسب خروجاً من التطبيق. */
    @Volatile var suppressAppOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force English UI regardless of system language
        val en = Locale.ENGLISH
        Locale.setDefault(en)
        val config = Configuration(resources.configuration)
        config.setLocale(en)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XrayTheme { MainScreen(vm) } }

        MobileAds.initialize(this) {
            runOnUiThread {
                adsInitialized = true
                preloadConnectInterstitial()
                if (savedInstanceState == null && !isFinishing && !isDestroyed) {
                    showAppOpenAd()
                } else {
                    loadAppOpenAd()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (suppressAppOpen) {
            // رجوع من نافذة نظام/مشاركة داخلية: لا إعلان.
            suppressAppOpen = false
            wentToBackground = false
            return
        }

        // رجوع من زر Home / التطبيقات الأخيرة: إعلان فتح التطبيق في كل مرة.
        if (wentToBackground) {
            wentToBackground = false
            if (adsInitialized) showAppOpenAd()
        }
    }

    override fun onStop() {
        super.onStop()

        // إعلانات ملء الشاشة وتغيير الاتجاه ونوافذ النظام الداخلية لا تُحسب خروجاً.
        if (!isChangingConfigurations && !fullScreenAdShowing && !suppressAppOpen) {
            wentToBackground = true
            if (adsInitialized) loadAppOpenAd() // تجهيز الإعلان قبل العودة
        }
    }

    private fun appOpenFresh(): Boolean =
        appOpenAd != null && System.currentTimeMillis() - appOpenLoadedAt < 4L * 60L * 60L * 1000L

    private fun loadAppOpenAd(onReady: (() -> Unit)? = null) {
        if (appOpenFresh()) {
            onReady?.invoke()
            return
        }
        if (appOpenLoading) return
        appOpenLoading = true

        AppOpenAd.load(
            applicationContext,
            TEST_APP_OPEN_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenLoading = false
                    appOpenAd = ad
                    appOpenLoadedAt = System.currentTimeMillis()
                    onReady?.invoke()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenLoading = false
                    appOpenAd = null
                }
            }
        )
    }

    /** يعرض إعلان فتح التطبيق: عند التشغيل، وعند كل رجوع من الخلفية. */
    private fun showAppOpenAd() {
        if (isFinishing || isDestroyed || fullScreenAdShowing) return

        loadAppOpenAd {
            // قد ينتهي التحميل بعد خروج المستخدم من التطبيق مجدداً.
            if (isFinishing || isDestroyed || fullScreenAdShowing) return@loadAppOpenAd
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@loadAppOpenAd

            val ad = appOpenAd ?: return@loadAppOpenAd
            appOpenAd = null

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    fullScreenAdShowing = true
                }

                override fun onAdDismissedFullScreenContent() {
                    fullScreenAdShowing = false
                    loadAppOpenAd()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    fullScreenAdShowing = false
                    loadAppOpenAd()
                }
            }

            try {
                fullScreenAdShowing = true
                ad.show(this)
            } catch (_: Exception) {
                fullScreenAdShowing = false
            }
        }
    }

    private fun preloadConnectInterstitial() {
        InterstitialAd.load(
            this,
            TEST_INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    connectInterstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    connectInterstitial = null
                }
            }
        )
    }

    /** Show an interstitial after VPN reaches Connected. */
    fun showConnectedInterstitial() {
        if (isFinishing || isDestroyed) return

        val cached = connectInterstitial

        if (cached != null) {
            connectInterstitial = null
            showConnectedAd(cached)
            return
        }

        InterstitialAd.load(
            this,
            TEST_INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (isFinishing || isDestroyed) {
                        preloadConnectInterstitial()
                        return
                    }
                    showConnectedAd(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    preloadConnectInterstitial()
                }
            }
        )
    }

    private fun showConnectedAd(ad: InterstitialAd) {
        ad.fullScreenContentCallback =
            object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    fullScreenAdShowing = true
                }

                override fun onAdDismissedFullScreenContent() {
                    fullScreenAdShowing = false
                    preloadConnectInterstitial()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    fullScreenAdShowing = false
                    preloadConnectInterstitial()
                }
            }

        try {
            fullScreenAdShowing = true
            ad.show(this)
        } catch (_: Exception) {
            fullScreenAdShowing = false
            preloadConnectInterstitial()
        }
    }

    fun showRewardedVideo(
        onReward: () -> Unit,
        onUnavailable: () -> Unit = {}
    ) {
        RewardedAd.load(
            this,
            TEST_REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    if (isFinishing || isDestroyed) return

                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdShowedFullScreenContent() {
                            fullScreenAdShowing = true
                        }

                        override fun onAdDismissedFullScreenContent() {
                            fullScreenAdShowing = false
                        }

                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            fullScreenAdShowing = false
                        }
                    }

                    fullScreenAdShowing = true
                    ad.show(this@MainActivity) {
                        TimeCredit.grantReward(this@MainActivity)
                        onReward()
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!isFinishing && !isDestroyed) {
                        onUnavailable()
                    }
                }
            }
        )
    }

    companion object {
        // Official Google test ad unit IDs.
        const val TEST_APP_OPEN_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/9257395921"

        const val TEST_INTERSTITIAL_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/1033173712"

        const val TEST_REWARDED_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/5224354917"

        const val TEST_BANNER_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/6300978111"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val hostActivity = ctx as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current

    val servers by vm.servers.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val state by vm.vpnState.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val error by XrayVpnService.lastError.collectAsStateWithLifecycle()
    val connLines by ConnLog.lines.collectAsStateWithLifecycle()
    // 0 = Home, 1 = Logs
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val snack = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Server?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var shareFor by remember { mutableStateOf<Server?>(null) }
    var exportAllDialog by remember { mutableStateOf(false) }
    var newServer by remember { mutableStateOf<Server?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var showTelegram by remember { mutableStateOf(true) } // every cold start
    var updateBanner by remember { mutableStateOf<String?>(null) }
    var remainingCredit by remember {
        mutableLongStateOf(TimeCredit.remaining(ctx))
    }

    LaunchedEffect(state) {
        while (true) {
            remainingCredit = TimeCredit.remaining(ctx)
            delay(1000L)
        }
    }

    // شريط "Xray Tunnel Connected" بنفس شكل ولون "Update Success" عند انتقال الحالة إلى متصل
    var prevVpnState by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val justConnected = state == VpnState.Connected && prevVpnState == VpnState.Connecting
        prevVpnState = state
        if (justConnected) {
            val text = ctx.getString(R.string.tunnel_connected)
            updateBanner = text
            delay(3000)
            if (updateBanner == text) updateBanner = null
        }
    }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect

        if (current == ctx.getString(R.string.update_success)) {
            updateBanner = current
            delay(3000)

            if (updateBanner == current) {
                updateBanner = null
            }

            vm.consumeMessage()
        } else {
            snack.showSnackbar(current)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            val long = it.length > 80 // رسائل التشخيص الطويلة تبقى حتى يغلقها المستخدم
            snack.showSnackbar(
                it,
                withDismissAction = long,
                duration = if (long) SnackbarDuration.Indefinite else SnackbarDuration.Short
            )
        }
    }

    // Interstitial on VPN connect disabled by request.
    // var lastObservedVpnState by remember { mutableStateOf(state) }
    // LaunchedEffect(state) {
    //     val justConnected =
    //         state == VpnState.Connected &&
    //         lastObservedVpnState != VpnState.Connected
    //     lastObservedVpnState = state
    //     if (justConnected) {
    //         hostActivity?.showConnectedInterstitial()
    //     }
    // }

    // Automatic subscription update.
    LaunchedEffect(Unit) {
        vm.updateIfDue()
    }

    // Conditional check every 30 seconds.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.checkForNewServers()
                delay(30_000L)
            }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startVpn() {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, XrayVpnService::class.java)
        )
    }

    fun stopVpn() {
        ctx.startService(
            Intent(ctx, XrayVpnService::class.java)
                .setAction(XrayVpnService.ACTION_STOP)
        )
    }

    fun chooseServer(id: String) {
        if (id == selectedId) return

        if (state != VpnState.Disconnected) {
            ctx.startService(
                Intent(ctx, XrayVpnService::class.java).apply {
                    action = XrayVpnService.ACTION_SWITCH_SERVER
                    putExtra(XrayVpnService.EXTRA_SERVER_ID, id)
                }
            )
        } else {
            vm.select(id)
        }
    }

    val vpnPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            startVpn()
        }
    }

    fun toggle() {
        if (state != VpnState.Disconnected) {
            stopVpn()
            return
        }

        if (TimeCredit.remaining(ctx) <= 0L) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.time_expired_toast),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (servers.isEmpty()) {
            vm.toast(R.string.no_servers)
            return
        }

        val intent = VpnService.prepare(ctx)

        if (intent != null) {
            hostActivity?.suppressAppOpen = true
            vpnPerm.launch(intent)
        } else {
            startVpn()
        }
    }

    fun importClipboard() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager

        vm.importText(
            cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        )
    }

    // Import a .xtun file (or a text file of links).
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().takeIf { it.size <= 2 * 1024 * 1024 }
                    ?.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }

        if (text == null) {
            vm.toast(R.string.file_read_failed)
        } else {
            vm.importFileText(text)
        }
    }

    fun importFile() {
        hostActivity?.suppressAppOpen = true
        importFileLauncher.launch(arrayOf("*/*"))
    }

    // Export servers as an encrypted .xtun file; locked = recipient cannot view/edit it.
    fun exportFile(list: List<Server>, locked: Boolean) {
        val exportable = list.filter { it.isEditable }

        if (exportable.isEmpty()) {
            vm.toast(R.string.export_nothing)
            return
        }

        try {
            val dir = File(ctx.cacheDir, "exports").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val file = File(dir, ServerFile.fileName(exportable))
            file.writeText(ServerFile.export(exportable, locked), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            hostActivity?.suppressAppOpen = true
            ctx.startActivity(Intent.createChooser(send, file.name))
        } catch (_: Exception) {
            hostActivity?.suppressAppOpen = false
            vm.toast(R.string.file_read_failed)
        }
    }

    fun openLink(url: String) {
        val isYoutube = url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true)
        if (isYoutube) {
            // Open inside YouTube app (not browser / not home).
            // Prefer explicit app intents; @handle works better with these forms.
            val handle = url.substringAfter("@", "").substringBefore("/").substringBefore("?").trim()
            val candidates = buildList {
                if (handle.isNotEmpty()) {
                    add("https://www.youtube.com/@$handle")
                    add("https://www.youtube.com/@$handle/featured")
                    add("https://www.youtube.com/@$handle/videos")
                    add("vnd.youtube://www.youtube.com/@$handle")
                    add("https://m.youtube.com/@$handle")
                }
                add(url)
            }.distinct()
            for (candidate in candidates) {
                val uri = android.net.Uri.parse(candidate)
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, uri)
                            .setPackage("com.google.android.youtube")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return
                } catch (_: Exception) {
                }
            }
            // Intent URL scheme forced to YouTube package
            try {
                val intentUrl =
                    "intent://www.youtube.com/@${handle.ifBlank { "radouane_tech" }}#Intent;" +
                        "scheme=https;package=com.google.android.youtube;end"
                ctx.startActivity(
                    Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
            }
        }
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    fun watchReward() {
        hostActivity?.showRewardedVideo(
            onReward = {
                remainingCredit = TimeCredit.remaining(ctx)
            },
            onUnavailable = {
                uiScope.launch {
                    snack.showSnackbar(ctx.getString(R.string.ad_unavailable))
                }
            }
        )
    }

    val selected = servers.firstOrNull { it.id == selectedId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snack)
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    if (selectedTab == 1) {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, null)
                        }
                        DropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            containerColor = Color.White
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_clipboard)) },
                                onClick = {
                                    menu = false
                                    importClipboard()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_file)) },
                                onClick = {
                                    menu = false
                                    importFile()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_file)) },
                                onClick = {
                                    menu = false
                                    exportAllDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_imported)) },
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_configs)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_logs)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_more)) }
                )
            }
        }
    ) { pad ->

        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {

            when (selectedTab) {
                2 -> {
                // ===================== LOGS PAGE =====================
                LogsPage(
                    state = state,
                    lines = connLines,
                    onCopy = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("connection log", ConnLog.asText()))
                        Toast.makeText(ctx, ctx.getString(R.string.conn_log_copied), Toast.LENGTH_SHORT).show()
                    },
                    onClear = { ConnLog.clear() }
                )
                }
                3 -> {
                    MorePage(
                        onSaveDns = { a, b ->
                            DnsPrefs.set(ctx, a, b)
                            uiScope.launch { snack.showSnackbar(ctx.getString(R.string.dns_saved)) }
                        },
                        onOpenTelegram = { openLink(Defaults.telegramChannelUrl()) },
                        onOpenYoutube = { openLink(Defaults.youtubeUrl()) },
                        onOpenVpnSettings = {
                            try {
                                ctx.startActivity(
                                    Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {}
                        }
                    )
                }
                0 -> {
            // ===================== HOME PAGE (NPV style) =====================
            NpvHomePage(
                state = state,
                selected = selected,
                remainingCredit = remainingCredit,
                onToggle = ::toggle,
                onWatchReward = ::watchReward,
                onOpenConfigs = { selectedTab = 1 }
            )
                }
                else -> {
            // ===================== CONFIGS PAGE =====================
            Column(
                Modifier.fillMaxSize()
            ) {

                // Connection header.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {

                    // Server tools (no Hero on configs — power is on Home)
                    // kept list below

                    // 3. SERVER LIST HEADER AND UPDATE BUTTON.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Text(
                            "${stringResource(R.string.servers)} (${servers.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )

                        FilledTonalIconButton(
                            onClick = vm::updateAll,
                            enabled = !updating,
                            modifier = Modifier.size(42.dp)
                        ) {
                            if (updating) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    stringResource(R.string.update_servers)
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Box {
                            Button(
                                onClick = { addMenu = true },
                                contentPadding = PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 8.dp
                                ),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NpvGreen,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    null,
                                    Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.add_server))
                            }

                            DropdownMenu(
                                expanded = addMenu,
                                onDismissRequest = { addMenu = false },
                                containerColor = Color.White
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.protocol_v2ray)) },
                                    onClick = {
                                        addMenu = false
                                        newServer = Server(
                                            name = "V2Ray",
                                            protocol = "vless",
                                            port = 443,
                                            network = "tcp",
                                            security = "tls"
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.protocol_ssh)) },
                                    onClick = {
                                        addMenu = false
                                        newServer = Server(
                                            name = "SSH",
                                            protocol = "ssh",
                                            port = 443,
                                            network = "ws",
                                            security = "tls"
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.protocol_openvpn)) },
                                    onClick = {
                                        addMenu = false
                                        newServer = Server(
                                            name = "OpenVPN",
                                            protocol = "ovpn",
                                            port = 443,
                                            network = "ws",
                                            security = "none",
                                            payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: Websocket[crlf]Connection: Keep-Alive[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // SERVER LIST.
                if (servers.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState()
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 2.dp,
                            bottom = 32.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(servers, key = { it.id }) { s ->

                            ServerRow(
                                s,
                                s.id == selectedId,
                                onClick = {
                                    chooseServer(s.id)
                                },
                                onEdit = {
                                    editing = s
                                },
                                onShare = {
                                    shareFor = s
                                },
                                onDelete = {
                                    vm.delete(s.id)
                                }
                            )
                        }
                    }
                }

                // AdMob banner.
                AdMobBannerAd()
            }
                } // end else configs
            } // end when(selectedTab)

            // Update success banner (visible on Home).
            if (selectedTab == 0) {
            updateBanner?.let { text ->

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    color = ConnectedGreen,
                    shadowElevation = 4.dp,
                    contentColor = Color.White
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )

                        Text(
                            text,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
            } // end if selectedTab == 0 for updateBanner
        }
    }

    shareFor?.let { srv ->
        AlertDialog(
            onDismissRequest = { shareFor = null },
            title = { Text(stringResource(R.string.share_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShareOption(
                        stringResource(R.string.file_locked),
                        stringResource(R.string.file_locked_hint)
                    ) {
                        shareFor = null
                        exportFile(listOf(srv), locked = true)
                    }

                    ShareOption(
                        stringResource(R.string.file_unlocked),
                        stringResource(R.string.file_unlocked_hint)
                    ) {
                        shareFor = null
                        exportFile(listOf(srv), locked = false)
                    }

                    ShareOption(stringResource(R.string.share_json), null) {
                        shareFor = null
                        hostActivity?.suppressAppOpen = true
                        shareText(ctx, ServerExporter.toJson(srv), "JSON · ${srv.name}")
                    }

                    ShareOption(stringResource(R.string.share_uri), null) {
                        shareFor = null
                        val uri = ServerExporter.toUri(srv)

                        if (uri != null) {
                            hostActivity?.suppressAppOpen = true
                            shareText(ctx, uri, "URI · ${srv.name}")
                        } else {
                            vm.toast(R.string.uri_export_not_supported)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { shareFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (exportAllDialog) {
        AlertDialog(
            onDismissRequest = { exportAllDialog = false },
            title = { Text(stringResource(R.string.export_file)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShareOption(
                        stringResource(R.string.file_locked),
                        stringResource(R.string.file_locked_hint)
                    ) {
                        exportAllDialog = false
                        exportFile(servers, locked = true)
                    }

                    ShareOption(
                        stringResource(R.string.file_unlocked),
                        stringResource(R.string.file_unlocked_hint)
                    ) {
                        exportAllDialog = false
                        exportFile(servers, locked = false)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { exportAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showTelegram) {
        TelegramDialog {
            showTelegram = false
        }
    }

    newServer?.let { s ->
        EditServerDialog(
            s,
            onSave = vm::addServer,
            onClose = { newServer = null }
        )
    }

    editing?.let { s ->
        EditServerDialog(
            s,
            onSave = vm::save,
            onClose = { editing = null }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = {
                confirmClear = false
            },
            title = {
                Text(stringResource(R.string.delete_imported))
            },
            text = {
                Text(stringResource(R.string.delete_imported_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearImported()
                        confirmClear = false
                    }
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// ----------------------------------------------------------------
// AdMob banner
// ----------------------------------------------------------------

@Composable
private fun AdMobBannerAd() {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = MainActivity.TEST_BANNER_AD_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

// ----------------------------------------------------------------
// Hero
// ----------------------------------------------------------------


@Composable
private fun NpvHomePage(
    state: VpnState,
    selected: Server?,
    remainingCredit: Long,
    onToggle: () -> Unit,
    onWatchReward: () -> Unit,
    onOpenConfigs: () -> Unit
) {
    val statusColor by animateColorAsState(
        when (state) {
            VpnState.Connected -> ConnectedGreen
            VpnState.Connecting -> ConnectingAmber
            else -> Color(0xFF8FA396)
        },
        label = "npv-status"
    )
    val statusText = when (state) {
        VpnState.Connected -> "CONNECTED"
        VpnState.Connecting -> "CONNECTING..."
        else -> "NOT CONNECTED"
    }
    val statusHint = when (state) {
        VpnState.Connected -> selected?.name ?: "Tunnel active"
        VpnState.Connecting -> "Preparing the connection..."
        else -> "Choose a configuration, then tap the power button"
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(3.dp, statusColor.copy(alpha = 0.45f), CircleShape)
                .clickable(enabled = state != VpnState.Connecting, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = "Connect",
                modifier = Modifier.size(72.dp),
                tint = statusColor
            )
        }
        Spacer(Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (state == VpnState.Connected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    null,
                    tint = statusColor
                )
                Column(Modifier.weight(1f)) {
                    Text(statusText, fontWeight = FontWeight.Bold, color = statusColor)
                    Text(statusHint, color = Color(0xFF6B7F72), fontSize = 13.sp, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onWatchReward)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Stars, null, tint = NpvGreen)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ad-free time", fontWeight = FontWeight.SemiBold)
                    Text(TimeCredit.format(remainingCredit), color = Color(0xFF6B7F72), fontSize = 13.sp)
                }
                Text("Watch +1h", color = NpvGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9BB0A3))
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenConfigs)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "ACTIVE CONFIGURATION",
                    color = Color(0xFF7A9184),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = NpvGreen, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected?.name ?: "No configuration",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Host/port hidden on Home for privacy
                    }
                    if (selected != null) {
                        Text(
                            selected.protocol.uppercase().take(8),
                            color = NpvGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9BB0A3))
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFFE2EDE6))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DOWNLINK", color = Color(0xFF7A9184), fontSize = 11.sp)
                        Text("—", fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("UPLINK", color = Color(0xFF7A9184), fontSize = 11.sp)
                        Text("—", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AdMobBannerAd()
    }
}

@Composable
private fun MorePage(
    onSaveDns: (String, String) -> Unit,
    onOpenTelegram: () -> Unit,
    onOpenYoutube: () -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    val ctx = LocalContext.current
    var primary by remember { mutableStateOf(DnsPrefs.primary(ctx)) }
    var secondary by remember { mutableStateOf(DnsPrefs.secondary(ctx)) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("MORE", fontWeight = FontWeight.Bold, color = NpvGreen, fontSize = 18.sp)
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dns_settings), fontWeight = FontWeight.SemiBold)
                OutlinedTextField(primary, { primary = it }, label = { Text("Primary DNS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secondary, { secondary = it }, label = { Text("Secondary DNS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1.1.1.1" to "1.0.0.1", "8.8.8.8" to "8.8.4.4", "9.9.9.9" to "149.112.112.112").forEach { (a, b) ->
                        AssistChip(onClick = { primary = a; secondary = b }, label = { Text(a, fontSize = 11.sp) })
                    }
                }
                Button(onClick = { onSaveDns(primary, secondary) }, colors = ButtonDefaults.buttonColors(containerColor = NpvGreen)) {
                    Text("Save DNS")
                }
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTelegram)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Send, null, tint = NpvGreen)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.telegram_channel), fontWeight = FontWeight.SemiBold)
                    Text("News and updates", color = Color(0xFF6B7F72), fontSize = 13.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9BB0A3))
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenYoutube)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayCircle, null, tint = NpvGreen)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.youtube_channel), fontWeight = FontWeight.SemiBold)
                    Text("YouTube channel", color = Color(0xFF6B7F72), fontSize = 13.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9BB0A3))
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenVpnSettings)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnKey, null, tint = NpvGreen)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("System VPN profiles", fontWeight = FontWeight.SemiBold)
                    Text("Open Android VPN settings", color = Color(0xFF6B7F72), fontSize = 13.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9BB0A3))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("ABOUT", color = Color(0xFF7A9184), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("Xray Tunnel 1.4.0", color = Color(0xFF4A6354), fontSize = 13.sp)
    }
}

@Composable
private fun Hero(
    state: VpnState,
    serverName: String?,
    remainingCredit: Long,
    onWatchReward: () -> Unit,
    onToggle: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    val statusColor by animateColorAsState(
        when (state) {
            VpnState.Connected -> ConnectedGreen
            VpnState.Connecting -> ConnectingAmber
            else -> cs.primary
        },
        label = "connection-status"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        statusColor.copy(alpha = 0.16f),
                        cs.surfaceVariant
                    )
                )
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        ConnectButton(
            state,
            onToggle,
            Modifier.weight(1f)
        )

        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                Text(
                    stringResource(
                        when (state) {
                            VpnState.Connected -> R.string.connected
                            VpnState.Connecting -> R.string.connecting
                            else -> R.string.disconnected
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                serverName ?: stringResource(R.string.no_server_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Compact remaining time + "+1 hour" button in the free space beside the status.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                TimeCredit.format(remainingCredit),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (remainingCredit > 0L) cs.primary else cs.error
            )

            FilledTonalButton(
                onClick = onWatchReward,
                modifier = Modifier
                    .height(28.dp)
                    .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(13.dp))
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.reward_plus_one_hour), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConnectButton(
    state: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    val color by animateColorAsState(
        when (state) {
            VpnState.Connected -> ConnectedGreen
            VpnState.Connecting -> ConnectingAmber
            else -> cs.primary
        },
        label = "connect-button-color"
    )

    Button(
        onClick = onClick,
        enabled = state != VpnState.Connecting,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(17.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.75f),
            disabledContentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 1.dp
        ),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {

        Text(
            text = when (state) {
                VpnState.Connected ->
                    stringResource(R.string.disconnect).uppercase()

                VpnState.Connecting ->
                    stringResource(R.string.connecting).uppercase()

                else ->
                    stringResource(R.string.connect).uppercase()
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ----------------------------------------------------------------
// Empty state
// ----------------------------------------------------------------

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Icon(
            Icons.Default.Info,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        Text(
            stringResource(R.string.no_servers),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ----------------------------------------------------------------
// Server row
// ----------------------------------------------------------------

@Composable
private fun ServerRow(
    s: Server,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accent = protocolColor(s.protocol)

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                cs.primaryContainer
            else
                cs.surfaceVariant
        ),
        border = if (selected)
            BorderStroke(1.5.dp, cs.primary)
        else
            BorderStroke(1.dp, cs.outline.copy(alpha = 0.45f))
    ) {

        Row(
            Modifier.padding(
                start = 12.dp,
                end = 4.dp,
                top = 10.dp,
                bottom = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    protocolLabel(s.protocol),
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {

                Text(
                    s.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )

                // Only editable servers show host and port.
                if (s.isEditable) {
                    Text(
                        "${s.address}:${s.port}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }

                Text(
                    s.networkInfo(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = cs.primary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (s.isEditable) {
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        stringResource(R.string.share_server),
                        tint = cs.onSurfaceVariant
                    )
                }

                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.edit_server),
                        tint = cs.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.delete),
                        tint = cs.error
                    )
                }
            } else if (s.isDeletable) {
                // Locked server imported from a file: details hidden, delete only.
                Icon(
                    Icons.Default.Lock,
                    stringResource(R.string.locked_file_server),
                    Modifier
                        .padding(start = 12.dp, end = 4.dp)
                        .size(18.dp),
                    tint = cs.onSurfaceVariant.copy(alpha = 0.7f)
                )

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.delete),
                        tint = cs.error
                    )
                }
            } else {
                Icon(
                    Icons.Default.Lock,
                    stringResource(R.string.locked_server),
                    Modifier
                        .padding(horizontal = 12.dp)
                        .size(18.dp),
                    tint = cs.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ----------------------------------------------------------------
// Server helpers
// ----------------------------------------------------------------

private fun Server.networkInfo(): String = buildString {
    if (protocol == "ssh") append("SSH · ")
    if (protocol == "ovpn") {
        append("OVPN · ")
        // proto الأصلي من ملف .ovpn (udp/tcp) محفوظ في method
        val proto = method.lowercase().takeIf { it == "udp" || it == "tcp" }
        if (proto != null) {
            append(proto.uppercase())
            if (network == "ws") append(" · WS")
        } else {
            append(network.uppercase())
        }
    } else {
        append(network.uppercase())
    }

    if (security != "none") {
        append(" · ").append(security.uppercase())
    }
}

private fun protocolLabel(p: String) = when (p) {
    "shadowsocks" -> "SS"
    "hysteria2" -> "HY2"
    "ssh" -> "SSH"
    "ovpn" -> "OVPN"
    "wireguard" -> "WG"
    else -> p.uppercase().take(6)
}

private fun protocolColor(p: String) = when (p) {
    "vless" -> Color(0xFF2E7D4F)
    "vmess" -> Color(0xFF16A34A)
    "trojan" -> Color(0xFF15803D)
    "shadowsocks" -> Color(0xFF22C55E)
    "hysteria2" -> Color(0xFF4ADE80)
    "wireguard" -> Color(0xFF166534)
    "ssh" -> Color(0xFF15803D)
    "ovpn" -> Color(0xFF22C55E)
    else -> Color(0xFF6B7F72)
}

@Composable
private fun ShareOption(title: String, hint: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)

        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ----------------------------------------------------------------
// Share text
// ----------------------------------------------------------------

private fun shareText(
    context: Context,
    text: String,
    title: String
) {
    // Copy only — no share sheet
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(title, text))
    Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
}


// ----------------------------------------------------------------
// LOGS PAGE (full screen)
// ----------------------------------------------------------------

@Composable
private fun LogsPage(
    state: VpnState,
    lines: List<ConnLog.Line>,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) {
        kotlinx.coroutines.delay(30)
        scroll.animateScrollTo(scroll.maxValue)
    }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }

    val bg = Color(0xFFF3F7F4)
    val card = Color.White
    val dim = Color(0xFF6B7F72)
    val titleColor = Color(0xFF1A2E22)
    val dot = when (state) {
        VpnState.Connected -> ConnectedGreen
        VpnState.Connecting -> ConnectingAmber
        else -> dim
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxSize()
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.conn_log_title),
                    color = titleColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.conn_log_copy), color = NpvGreen, fontSize = 13.sp)
                }
                TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.conn_log_clear), color = NpvGreen, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (lines.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.logs_empty),
                        color = dim,
                        fontSize = 15.sp
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = card,
                    modifier = Modifier.fillMaxSize()
                ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lines.forEach { l ->
                        val color = when (l.level) {
                            ConnLog.Level.OK -> Color(0xFF15803D)
                            ConnLog.Level.WARN -> Color(0xFFB45309)
                            ConnLog.Level.ERROR -> Color(0xFFDC2626)
                            ConnLog.Level.DEBUG -> dim
                            ConnLog.Level.INFO -> Color(0xFF1A2E22)
                        }
                        Row {
                            Text(
                                fmt.format(java.util.Date(l.time)),
                                color = dim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                l.text,
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// CONNECTION LOG PANEL (compact, kept for compatibility)
// ----------------------------------------------------------------

@Composable
private fun ConnLogPanel(
    state: VpnState,
    lines: List<ConnLog.Line>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onHide: (() -> Unit)? = null
) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) {
        kotlinx.coroutines.delay(30)
        scroll.animateScrollTo(scroll.maxValue)
    }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }

    val bg = Color.White
    val dim = Color(0xFF6B7F72)
    val dot = when (state) {
        VpnState.Connected -> ConnectedGreen
        VpnState.Connecting -> ConnectingAmber
        else -> dim
    }

    // السجل تقني: نثبّت الاتجاه LTR ليبقى الترتيب (وقت ثم رسالة) واضحاً
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bg,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.conn_log_title),
                        color = Color(0xFF1A2E22),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.conn_log_copy), color = NpvGreen, fontSize = 12.sp)
                    }
                    TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.conn_log_clear), color = NpvGreen, fontSize = 12.sp)
                    }
                    if (onHide != null) {
                        IconButton(onClick = onHide, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, null, tint = Color(0xFF94A3B8))
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(scroll)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        lines.forEach { l ->
                            val color = when (l.level) {
                                ConnLog.Level.OK -> Color(0xFF34D399)
                                ConnLog.Level.WARN -> Color(0xFFFBBF24)
                                ConnLog.Level.ERROR -> Color(0xFFF87171)
                                ConnLog.Level.DEBUG -> dim
                                ConnLog.Level.INFO -> Color(0xFFCBD5E1)
                            }
                            Row {
                                Text(
                                    fmt.format(java.util.Date(l.time)),
                                    color = dim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    l.text,
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
