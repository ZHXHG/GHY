package dev.suyu.suyu_emu.fragments

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import android.app.ProgressDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.security.NoSuchAlgorithmException
import java.io.InputStream

public object UpdateManager {
    private val client = OkHttpClient()

    fun checkAndInstallUpdate(context: Context) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) {
            val updateInfo = getUpdateInfoFromServer()
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName

            // 比较版本号，如果需要更新才显示更新对话框
            if (isUpdateAvailable(currentVersion, updateInfo.versionName)) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(context, updateInfo, currentVersion)
                }
            }
        }
    }

    private suspend fun getUpdateInfoFromServer(): UpdateInfo {
        val request = Request.Builder()
            .url("http://mkoc.cn/update.php")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }

            val inputStream = response.body?.byteStream()

            if (inputStream != null) {
                val responseBody = inputStream.bufferedReader().use { it.readText() }
                JSONObject(responseBody).let { json ->
                    if (
                        json.has("title") &&
                        json.has("content") &&
                        json.has("versionName") &&
                        json.has("downloadUrl") &&
                        json.has("sha256Hash")
                    ) {
                        UpdateInfo(
                            title = json.getString("title"),
                            content = json.getString("content"),
                            versionName = json.getString("versionName"),
                            downloadUrl = json.getString("downloadUrl"),
                            hashValue = json.getString("sha256Hash")
                        )
                    } else {
                        Log.e("UpdateManager", "响应数据格式错误")
                        UpdateInfo("", "", "", "", "")
                    }
                }
            } else {
                Log.e("UpdateManager", "未获取到有效的输入流")
                UpdateInfo("", "", "", "", "")
            }
        } catch (e: IOException) {
            Log.e("UpdateManager", "处理响应时出错: ${e.message}")
            UpdateInfo("", "", "", "", "")
        } catch (e: JSONException) {
            Log.e("UpdateManager", "解析更新信息时出错: ${e.message}")
            UpdateInfo("", "", "", "", "")
        }
    }

    private fun showUpdateDialog(
        context: Context,
        updateInfo: UpdateInfo,
        currentVersion: String
    ) {
        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle(updateInfo.title)
            .setMessage(updateInfo.content)

        val downloadDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val currentTimeStamp = System.currentTimeMillis()
        val apkFileName = "yuzu_${updateInfo.versionName}_$currentTimeStamp.apk"
        val apkFileFullPath = File(downloadDirectory, apkFileName)

        val isApkValid = isApkIntegrityValid(apkFileFullPath.absolutePath, updateInfo.hashValue)

        if (isApkValid) {
            dialogBuilder.setPositiveButton("安装") { _, _ ->
                installUpdate(context, apkFileFullPath.absolutePath)
            }
        } else {
            dialogBuilder.setPositiveButton("更新") { _, _ ->
                val progressDialog = createProgressDialog(context)
                downloadAndInstallUpdate(
                    context,
                    updateInfo.downloadUrl,
                    progressDialog,
                    updateInfo.versionName,
                    apkFileFullPath.absolutePath
                )
            }
        }

        dialogBuilder.setNegativeButton("稍后") { _, _ ->
            // 用户选择稍后更新或安装
        }.show()
    }

    private fun createProgressDialog(context: Context): ProgressDialog {
        val progressDialog = ProgressDialog(context)
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setTitle("下载中")
        progressDialog.setMessage("请稍候...")
        progressDialog.isIndeterminate = false
        progressDialog.setCancelable(false)
        progressDialog.max = 100
        return progressDialog
    }

    private fun downloadAndInstallUpdate(
        context: Context,
        downloadUrl: String,
        progressDialog: ProgressDialog,
        versionName: String,
        apkFilePath: String
    ) {
        val oldApkFile = File(apkFilePath)
        if (oldApkFile.exists()) {
            oldApkFile.delete()
        }

        val downloadDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val currentTimeStamp = System.currentTimeMillis()
        val apkFileName = "yuzu_${versionName}_$currentTimeStamp.apk"
        val apkFileFullPath = File(downloadDirectory, apkFileName)

        // 创建下载请求
        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        progressDialog.show()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                    Log.e("UpdateManager", "下载失败: ${e.message}")
                    progressDialog.dismiss()
                    showErrorMessageDialog(context, "下载失败，请检查网络连接")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                var inputStream: InputStream? = null
                try {
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected response code: ${response.code}")
                    }

                    inputStream = response.body?.byteStream()

                    if (inputStream != null) {
                        apkFileFullPath.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead: Long = 0
                            val contentLength = response.body!!.contentLength()
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead.toLong()
                                val progress = (totalBytesRead * 100 / contentLength).toInt()
                                (context as LifecycleOwner)
                                    .lifecycleScope
                                    .launch(Dispatchers.Main) {
                                        progressDialog.progress = progress
                                        progressDialog.setMessage("下载进度: $progress%")
                                    }
                            }
                        }
                        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                            progressDialog.dismiss()
                            installUpdate(context, apkFileFullPath.absolutePath)
                        }
                    } else {
                        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                            Log.e("UpdateManager", "下载失败: HTTP ${response.code}")
                            progressDialog.dismiss()
                            showErrorMessageDialog(context, "下载失败，HTTP ${response.code}")
                        }
                    }
                } catch (e: IOException) {
                    (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) {
                        Log.e("UpdateManager", "复制文件时出错: ${e.message}")
                        progressDialog.dismiss()
                        showErrorMessageDialog(context, "下载失败，复制文件时出错")
                    }
                } finally {
                    inputStream?.close()
                }
            }
        })
    }

    private fun showErrorMessageDialog(context: Context, message: String) {
        AlertDialog.Builder(context)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun isApkIntegrityValid(apkFilePath: String, expectedHash: String): Boolean {
        val file = File(apkFilePath)
        if (!file.exists()) {
            return false
        }

        return try {
            val calculatedHash = calculateSHA256Hash(file)
            calculatedHash == expectedHash
        } catch (e: IOException) {
            Log.e("UpdateManager", "读取APK文件时出错: ${e.message}")
            false
        } catch (e: NoSuchAlgorithmException) {
            Log.e("UpdateManager", "计算哈希时出错: ${e.message}")
            false
        }
    }

    private fun calculateSHA256Hash(file: File): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val byteArray = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(byteArray).also { bytesRead = it } != -1) {
                messageDigest.update(byteArray, 0, bytesRead)
            }
        }
        val hashBytes = messageDigest.digest()

        val hexStringBuilder = StringBuilder(2 * hashBytes.size)
        for (hashByte in hashBytes) {
            val hex = Integer.toHexString(0xff and hashByte.toInt())
            if (hex.length == 1) {
                hexStringBuilder.append('0')
            }
            hexStringBuilder.append(hex)
        }
        return hexStringBuilder.toString()
    }

    private fun installUpdate(context: Context, apkFilePath: String) {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            File(apkFilePath)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (installIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(installIntent)
        } else {
            showErrorMessageDialog(context, "没有找到可用的应用程序来安装更新")
        }
    }

    data class UpdateInfo(
        val title: String,
        val content: String,
        val versionName: String,
        val downloadUrl: String,
        val hashValue: String
    )

    private fun isUpdateAvailable(
        currentVersion: String,
        latestVersion: String
    ): Boolean {
        return compareSemanticVersion(currentVersion, latestVersion) < 0
    }

    private fun compareSemanticVersion(
        currentVersion: String,
        otherVersion: String
    ): Int {
        val currentParts = currentVersion.split('.')
        val otherParts = otherVersion.split('.')

        val currentMajor = currentParts.getOrNull(0)?.toIntOrNull() ?: 0
        val otherMajor = otherParts.getOrNull(0)?.toIntOrNull() ?: 0

        val currentMinor = currentParts.getOrNull(1)?.toIntOrNull() ?: 0
        val otherMinor = otherParts.getOrNull(1)?.toIntOrNull() ?: 0

        val currentPatch = currentParts.getOrNull(2)?.toIntOrNull() ?: 0
        val otherPatch = otherParts.getOrNull(2)?.toIntOrNull() ?: 0

        if (currentMajor != otherMajor) {
            return currentMajor.compareTo(otherMajor)
        }
        if (currentMinor != otherMinor) {
            return currentMinor.compareTo(otherMinor)
        }
        return currentPatch.compareTo(otherPatch)
    }
}
