package com.lanbridge.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lanbridge.app.LanBridgeApp
import com.lanbridge.app.R
import com.lanbridge.app.data.model.ConnectionState
import com.lanbridge.app.data.model.HistoryItem
import com.lanbridge.app.data.model.HistoryKind
import com.lanbridge.app.service.LanBridgeForegroundService
import com.lanbridge.app.ui.theme.BgDark
import com.lanbridge.app.ui.theme.BorderDark
import com.lanbridge.app.ui.theme.ErrorRed
import com.lanbridge.app.ui.theme.LanBridgeTheme
import com.lanbridge.app.ui.theme.PrimaryAccent
import com.lanbridge.app.ui.theme.SecondaryAccent
import com.lanbridge.app.ui.theme.SuccessGreen
import com.lanbridge.app.ui.theme.SurfaceDark
import com.lanbridge.app.ui.theme.SurfaceVariantDark
import com.lanbridge.app.ui.theme.TextPrimary
import com.lanbridge.app.ui.theme.TextSecondary
import com.lanbridge.app.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start Foreground Service if paired device is present
        val app = LanBridgeApp.instance
        if (app.preferencesManager.getPairedDevice() != null) {
            val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                action = LanBridgeForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        setContent {
            LanBridgeTheme {
                MainScreen(
                    onScanQrClick = {
                        startActivity(Intent(this, QrScannerActivity::class.java))
                    },
                    onSendFileClick = { uri, mime ->
                        val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                            action = LanBridgeForegroundService.ACTION_SEND_FILE
                            putExtra(LanBridgeForegroundService.EXTRA_FILE_URI, uri)
                            putExtra(LanBridgeForegroundService.EXTRA_FILE_MIME, mime)
                        }
                        startService(serviceIntent)
                    },
                    onSendClipboardClick = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).coerceToText(this)?.toString()
                            if (!text.isNullOrEmpty()) {
                                val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                                    action = LanBridgeForegroundService.ACTION_SEND_TEXT
                                    putExtra(LanBridgeForegroundService.EXTRA_CLIPBOARD_TEXT, text)
                                }
                                startService(serviceIntent)
                                Toast.makeText(this, "Буфер отправлен на ПК", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCopyItemClick = { content ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LanBridge", content))
                        Toast.makeText(this, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    onScanQrClick: () -> Unit,
    onSendFileClick: (Uri, String) -> Unit,
    onSendClipboardClick: () -> Unit,
    onCopyItemClick: (String) -> Unit
) {
    val connectionState by LanBridgeForegroundService.connectionState.collectAsState()
    val activeDevice by LanBridgeForegroundService.activeDevice.collectAsState()
    val pingMs by LanBridgeForegroundService.pingMs.collectAsState()
    val historyItems by LanBridgeApp.instance.historyStorage.itemsFlow.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendFileClick(uri, "*/*")
        }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBarLanBridge(connectionState = connectionState)
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 1. Connection Status Card
            ConnectionStatusCard(
                state = connectionState,
                device = activeDevice,
                pingMs = pingMs,
                onScanQrClick = onScanQrClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onScanQrClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "QR-сканер", color = TextPrimary, fontSize = 12.sp)
                }

                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Отправить файл", color = TextPrimary, fontSize = 12.sp)
                }

                Button(
                    onClick = onSendClipboardClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Буфер на ПК", color = TextPrimary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Clipboard History Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "История буфера обмена",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "${historyItems.size} записей",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. History Feed List
            if (historyItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "История пуста. Скопируйте что-нибудь на ПК или телефоне.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(historyItems, key = { it.id }) { item ->
                        HistoryCard(item = item, onCopyClick = { onCopyItemClick(item.content) })
                    }
                }
            }
        }
    }
}

@Composable
fun TopAppBarLanBridge(connectionState: ConnectionState) {
    Surface(
        color = SurfaceDark,
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LanBridge",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(SurfaceVariantDark, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = "P2P", fontSize = 10.sp, color = SecondaryAccent)
                }
            }

            // Connection Indicator Dot
            val dotColor = when (connectionState) {
                is ConnectionState.Connected -> SuccessGreen
                is ConnectionState.Connecting -> WarningAmber
                is ConnectionState.Discovering -> WarningAmber
                else -> TextSecondary
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

@Composable
fun ConnectionStatusCard(
    state: ConnectionState,
    device: com.lanbridge.app.data.model.PairedDevice?,
    pingMs: Long,
    onScanQrClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = device?.deviceName ?: "ПК не привязан",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = when (state) {
                            is ConnectionState.Connected -> "Подключено • IP: ${state.ip}:${state.port}"
                            is ConnectionState.Connecting -> "Подключение к ${state.deviceName}…"
                            is ConnectionState.Discovering -> "Поиск ПК в локальной сети…"
                            is ConnectionState.Disconnected -> "Не в сети"
                            is ConnectionState.AuthFailed -> "Ошибка: ${state.reason}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state) {
                            is ConnectionState.Connected -> SuccessGreen
                            is ConnectionState.AuthFailed -> ErrorRed
                            else -> TextSecondary
                        }
                    )
                }

                if (state is ConnectionState.Connected) {
                    Box(
                        modifier = Modifier
                            .background(SurfaceVariantDark, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$pingMs мс",
                            color = SecondaryAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (device == null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onScanQrClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Привязать компьютер (QR-код)", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
fun HistoryCard(item: HistoryItem, onCopyClick: () -> Unit) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeString = timeFormat.format(Date(item.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                when (item.kind) {
                    HistoryKind.TEXT -> {
                        Text(
                            text = item.content,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HistoryKind.IMAGE -> {
                        Text(
                            text = "[Изображение / Скриншот]",
                            color = SecondaryAccent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HistoryKind.FILE -> {
                        Text(
                            text = "📁 ${item.fileName ?: "Файл"}",
                            color = PrimaryAccent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeString,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (item.kind == HistoryKind.TEXT) {
                Button(
                    onClick = onCopyClick,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(text = "Копировать", color = TextPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}
