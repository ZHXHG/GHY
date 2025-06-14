package dev.suyu.suyu_emu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AssetFileManager(private val context: Context) {

    fun copyProdKeys() {
        copyAsset("prod.keys", "keys", "prod.keys")
    }

    fun copyGpuDrivers() {
        val fileNames = arrayOf("Turnip-24.1.0.adpkg_R18.zip", "Turnip-24.1.0.adpkg_R16.zip", "Turnip-24.1.0.adpkg_R15.zip")
        fileNames.forEach { fileName ->
            copyAsset(fileName, "gpu_drivers", fileName)
        }
    }
    
    fun copyFolderFromAssets() {
        val sourceFolderName = "01007EF00011E000"
        val destinationDir = File(context.getExternalFilesDir("load"), sourceFolderName)

        if (!destinationDir.exists()) {
            try {
                destinationDir.mkdirs() // 创建目标文件夹
                copyAssetFolder(sourceFolderName, destinationDir)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyAssetFolder(assetFolder: String, destinationDir: File) {
        context.assets.list(assetFolder)?.forEach { assetItem ->
            val assetPath = "$assetFolder/$assetItem"
            val destinationFile = File(destinationDir, assetItem)

            if (context.assets.list(assetPath)?.isNotEmpty() == true) {
                // 如果是子文件夹，递归调用 copyAssetFolder
                destinationFile.mkdirs()
                copyAssetFolder(assetPath, destinationFile)
            } else {
                // 如果是文件，复制文件
                context.assets.open(assetPath).use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        val buffer = ByteArray(1024)
                        var length: Int
                        while (inputStream.read(buffer).also { length = it } > 0) {
                            outputStream.write(buffer, 0, length)
                        }
                    }
                }
            }
        }
    }

    private fun copyAsset(assetName: String, subDirectory: String, destinationFileName: String) {
        val destinationDir = File(context.getExternalFilesDir(null), subDirectory)
        destinationDir.mkdirs()

        val destinationFile = File(destinationDir, destinationFileName)

        if (!destinationFile.exists()) {
            try {
                context.assets.open(assetName).use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        val buffer = ByteArray(1024)
                        var length: Int
                        while (inputStream.read(buffer).also { length = it } > 0) {
                            outputStream.write(buffer, 0, length)
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
