package com.bankcardreader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bankcardreader.nfc.EmvReader
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            // 设备不支持 NFC
            setContent {
                NoNfcScreen()
            }
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            // NFC 未启用
            setContent {
                NfcDisabledScreen()
            }
            return
        }

        setContent {
            BankCardReaderApp(
                onResume = { enableReaderMode() },
                onPause = { disableReaderMode() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        adapter.enableReaderMode(
            this,
            readerCallback,
            flags,
            null
        )
    }

    private fun disableReaderMode() {
        try {
            nfcAdapter?.disableReaderMode(this)
        } catch (_: Exception) {
            // 忽略异常
        }
    }

    private val readerCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "不支持的卡片类型",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.w(TAG, "Tag 不支持 IsoDep")
                }
                return
            }

            // 通知 UI 正在读取
            runOnUiThread { currentStatus.value = Status.Reading }

            try {
                val cardData = EmvReader.readCard(isoDep)
                runOnUiThread {
                    currentCard.value = cardData
                    currentStatus.value = Status.Success
                }
            } catch (e: IOException) {
                Log.e(TAG, "读取卡片失败", e)
                runOnUiThread {
                    currentStatus.value = Status.Error(
                        when {
                            e.message?.contains("状态字") == true -> "卡片拒绝了读取请求"
                            e.message?.contains("TimeOut") == true -> "通信超时，请将卡片靠近一些"
                            e.message?.contains("失败") == true -> "无法识别该银行卡"
                            else -> "读取失败：${e.message}"
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取卡片异常", e)
                runOnUiThread {
                    currentStatus.value = Status.Error("不支持该卡类型或卡片数据格式异常")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BankCardReader"
        val currentStatus = mutableStateOf<Status>(Status.Waiting)
        val currentCard = mutableStateOf<EmvReader.EmvCardData?>(null)
    }
}

// ─── 状态定义 ───────────────────────────────────────────────

sealed class Status {
    data object Waiting : Status()
    data object Reading : Status()
    data object Success : Status()
    data class Error(val message: String) : Status()
}

// ─── 主 Composable ──────────────────────────────────────────

@Composable
fun BankCardReaderApp(
    onResume: () -> Unit,
    onPause: () -> Unit
) {
    val status by remember { MainActivity.currentStatus }
    val card by remember { MainActivity.currentCard }

    // 生命周期感知
    DisposableEffect(Unit) {
        onResume()
        onDispose { onPause() }
    }

    MaterialTheme(
        colorScheme = darkBlueScheme
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // ── 应用图标 ──
                Icon(
                    imageVector = Icons.Default.CreditCard,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "银行卡读取",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(40.dp))

                // ── 卡片显示区域 ──
                CardDisplay(card = card, status = status)

                Spacer(modifier = Modifier.height(32.dp))

                // ── 状态提示 ──
                StatusIndicator(status = status)

                Spacer(modifier = Modifier.height(24.dp))

                // ── 重置按钮 ──
                AnimatedVisibility(
                    visible = status is Status.Success || status is Status.Error,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Button(
                        onClick = {
                            MainActivity.currentStatus.value = Status.Waiting
                            MainActivity.currentCard.value = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重新读取")
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── 免责声明 ──
                Text(
                    text = "本应用仅在本地读取卡片信息，不会存储或上传任何数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ── 卡片显示 ────────────────────────────────────────────────

@Composable
fun CardDisplay(
    card: EmvReader.EmvCardData?,
    status: Status
) {
    val isVisible = card != null && status is Status.Success

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF1A237E),
                        Color(0xFF283593),
                        Color(0xFF3949AB)
                    )
                )
            )
            .padding(24.dp)
    ) {
        if (isVisible && card != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 卡组织
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = card.cardScheme,
                        color = Color(0xFFFFD54F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Icon(
                        imageVector = Icons.Default.Nfc,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 卡号（模拟压印效果）
                Column {
                    Text(
                        text = "卡号",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = card.pan,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.sp
                    )
                }

                // 有效期和持卡人
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "有效期",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                        Text(
                            text = card.expiryDate.ifEmpty { "—" },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "持卡人",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                        Text(
                            text = card.holderName.ifEmpty { "—" },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            // 空卡片占位
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Contactless,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "等待银行卡…",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 16.sp
                )
            }
        }
    }
}

// ── 状态指示器 ──────────────────────────────────────────────

@Composable
fun StatusIndicator(status: Status) {
    when (status) {
        is Status.Waiting -> StatusRow(
            icon = Icons.Default.TouchApp,
            text = "请将银行卡靠近手机背面 NFC 感应区",
            color = MaterialTheme.colorScheme.primary
        )
        is Status.Reading -> StatusRow(
            icon = Icons.Default.Sync,
            text = "正在读取卡片…",
            color = MaterialTheme.colorScheme.tertiary,
            isAnimating = true
        )
        is Status.Success -> StatusRow(
            icon = Icons.Default.CheckCircle,
            text = "读取成功",
            color = Color(0xFF4CAF50)
        )
        is Status.Error -> StatusRow(
            icon = Icons.Default.Error,
            text = status.message,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun StatusRow(
    icon: ImageVector,
    text: String,
    color: Color,
    isAnimating: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (isAnimating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = color,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── 无 NFC / NFC 未启用 屏幕 ─────────────────────────────────

@Composable
fun NoNfcScreen() {
    MaterialTheme(colorScheme = darkBlueScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SignalWifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "设备不支持 NFC",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "您的设备没有 NFC 硬件，无法使用此应用读取银行卡。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun NfcDisabledScreen() {
    MaterialTheme(colorScheme = darkBlueScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "NFC 未启用",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "请在系统设置中开启 NFC 功能后重试。\n（设置 → 连接与共享 → NFC）",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 主题颜色 ────────────────────────────────────────────────

private val darkBlueScheme = darkColorScheme(
    primary = Color(0xFF5C6BC0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3949AB),
    secondary = Color(0xFF7C4DFF),
    secondaryContainer = Color(0xFF4527A0),
    tertiary = Color(0xFF26C6DA),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFF85149),
    onError = Color.White
)
