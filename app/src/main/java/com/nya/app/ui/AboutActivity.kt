package com.nya.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.app.R

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE91E63),
                secondary = Color(0xFFF48FB1),
                tertiary = Color(0xFF7C4DFF)
            )) {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember(ctx) {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrDefault("1.0.0")
    }
    val githubUrl = "https://github.com/juechichuan/NyaApp"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("关于", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("✕ 返回", color = Color(0xFFE91E63), fontWeight = FontWeight.Medium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFFF5F8)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFFFFFBFC))
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            // 应用信息
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_nya_icon),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("🐱 喵输入法助手", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("v$versionName", color = Color.Gray, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "通过无障碍服务自动在用户输入末尾追加文本（默认「喵」），适配 Android 16 / ColorOS 15。",
                fontSize = 13.sp,
                color = Color(0xFF444444),
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(4.dp))
            Text("开源协议：MIT License", fontSize = 12.sp, color = Color.Gray)

            Spacer(Modifier.height(20.dp))

            // 源代码
            Text("开源项目", fontWeight = FontWeight.SemiBold, color = Color(0xFFE25C8A), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔗", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("源代码仓库", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(githubUrl, fontSize = 11.sp, color = Color.Gray)
                    }
                    Text("›", color = Color.Gray)
                }
            }

            Spacer(Modifier.height(24.dp))

            // 关于作者
            Text("关于作者", fontWeight = FontWeight.SemiBold, color = Color(0xFFE25C8A), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "开发者邮箱：3627714945@qq.com",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("by 掘尺川", fontSize = 13.sp, color = Color(0xFFE25C8A), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
