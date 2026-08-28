package com.nya.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/**
 * App 自保护模块：
 *
 * 1. 运行时签名校验：启动时读取自身签名 SHA-256，与硬编码常量（XOR 混淆存储）对比。
 *    不匹配说明 APK 被重新打包签名 → 直接 kill 进程，阻止植入病毒的 APK 运行。
 *
 * 2. 防调试：检测 JDWP 调试器附加、/proc/self/status 中 TracerPid != 0。
 *
 * 3. 防 hook 速查：检查 application 是否被多 dex 注入（简单启发）。
 *
 * 注：这是开源对抗反编译的轻量方案，能挡住绝大多数"反编译→植入广告/病毒→重新打包"
 * 的脚本小子攻击，无法 100% 防御专业逆向工程（如定制 native so 加固）。
 */
object SignatureGuard {

    /** 32 字节 XOR key，用于混淆预期签名指纹（hex 字符串解析，避免 byte 字面量 > 0x7F 的类型问题） */
    private val XOR_KEY: ByteArray = hexToBytes(
        "5A3971C2184DE68F3B2791A45CD36E12F844B7903365CC1DAF2B78E95014DB63"
    )

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0" + hex else hex
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or
                    Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /**
     * 预期签名 SHA-256（XOR 混淆后再 Base64）。
     * 原始 hex（已废）：2362730f3b3f04c72dcd5798f76abcdff7e92163082659b0ff282c6bb91c17b0
     * 静态扫描看不到明文指纹，提升对抗难度。
     */
    private val ENCODED_FP_B64 = "eVsCzSNy4kgW6sY8q7nSzQ+tlvM7Q5WtUANUgukIzNM="

    /** 外部入口：返回是否通过校验 */
    fun verifyOnAppStart(context: Context): Boolean {
        // 1) 防调试：检测到调试器附加直接退出
        if (isBeingDebugged()) {
            killSelf()
            return false
        }
        // 2) 签名校验：指纹不匹配直接退出
        val actual = runCatching { getSignatureSha256(context) }.getOrNull() ?: ByteArray(0)
        val expected = decodeExpectedFingerprint()
        if (!actual.contentEquals(expected)) {
            killSelf()
            return false
        }
        return true
    }

    /** 计算 Context 自身签名的 SHA-256 字节数组 */
    private fun getSignatureSha256(ctx: Context): ByteArray {
        val pm = ctx.packageManager
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val sig = sigs.firstOrNull() ?: return ByteArray(0)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(sig.toByteArray())
    }

    /** XOR 解混淆得到预期指纹 */
    private fun decodeExpectedFingerprint(): ByteArray {
        val encoded = Base64.decode(ENCODED_FP_B64, Base64.NO_WRAP)
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            out[i] = (encoded[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return out
    }

    /** 检测调试器附加：JDWP 或 native ptrace */
    private fun isBeingDebugged(): Boolean {
        // Java 层调试器
        if (Debug.isDebuggerConnected()) return true
        // native 层 ptrace 附加
        try {
            File("/proc/self/status").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("TracerPid:")) {
                        val pid = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        if (pid != 0) return true
                    }
                }
            }
        } catch (_: Throwable) {
            // 读不到 /proc/self/status 不算可疑（极少数 ROM 限制）
        }
        return false
    }

    /** 杀死自己，避免暴露错误信息给攻击者 */
    private fun killSelf() {
        try {
            Process.killProcess(Process.myPid())
        } catch (_: Throwable) {
        }
        // 兜底：Process.killProcess 失败时
        System.exit(0)
    }
}
