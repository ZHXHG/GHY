// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package dev.suyu.suyu_emu.fragments

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.suyu.suyu_emu.R
import dev.suyu.suyu_emu.adapters.DriverAdapter
import dev.suyu.suyu_emu.databinding.FragmentDriverManagerBinding
import dev.suyu.suyu_emu.features.settings.model.StringSetting
import dev.suyu.suyu_emu.model.Driver.Companion.toDriver
import dev.suyu.suyu_emu.model.DriverViewModel
import dev.suyu.suyu_emu.model.HomeViewModel
import dev.suyu.suyu_emu.utils.FileUtil
import dev.suyu.suyu_emu.utils.GpuDriverHelper
import dev.suyu.suyu_emu.utils.NativeConfig
import dev.suyu.suyu_emu.utils.ViewUtils.updateMargins
import dev.suyu.suyu_emu.utils.collect
import java.io.IOException
import android.app.ProgressDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.TextView
import android.widget.Toast
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.Timer
import java.util.TimerTask
import android.view.LayoutInflater
import android.view.WindowManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.os.Handler
import android.os.Looper

class DriverManagerFragment : Fragment() {
    private var _binding: FragmentDriverManagerBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    private val args by navArgs<DriverManagerFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverManagerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setNavigationVisibility(visible = false, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = false)

        driverViewModel.onOpenDriverManager(args.game)
        if (NativeConfig.isPerGameConfigLoaded()) {
            binding.toolbarDrivers.inflateMenu(R.menu.menu_driver_manager)
            driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
            binding.toolbarDrivers.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_driver_use_global -> {
                        StringSetting.DRIVER_PATH.global = true
                        driverViewModel.updateDriverList()
                        (binding.listDrivers.adapter as DriverAdapter)
                            .replaceList(driverViewModel.driverList.value)
                        driverViewModel.showClearButton(false)
                        true
                    }

                    else -> false
                }
            }

            driverViewModel.showClearButton.collect(viewLifecycleOwner) {
                binding.toolbarDrivers.menu.findItem(R.id.menu_driver_use_global).isVisible = it
            }
        }

        if (!driverViewModel.isInteractionAllowed.value) {
            DriversLoadingDialogFragment().show(
                childFragmentManager,
                DriversLoadingDialogFragment.TAG
            )
        }

        binding.toolbarDrivers.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        binding.buttonInstall.setOnClickListener {
            getDriver.launch(arrayOf("application/zip"))
        }

        fun downloadFile(context: Context, url: String, fileName: String, progressDialog: ProgressDialog): Long {
    val downloadDir = context.getExternalFilesDir(null)?.let { File(it, "gpu_drivers") }
    downloadDir?.mkdirs()

    val request = DownloadManager.Request(Uri.parse(url)).apply {
        setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        setDestinationUri(Uri.fromFile(File(downloadDir, fileName)))
        setTitle(fileName)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    }

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    val downloadId = dm?.enqueue(request) ?: -1

    // 注册监听器来更新下载进度
    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm?.query(query)
            cursor?.use {
                if (it.moveToFirst()) {
                    val status = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // 下载成功
                        progressDialog.dismiss()
                        // 显示下载完成提示
                        Toast.makeText(context, "下载完成", Toast.LENGTH_SHORT).show()

                        // 获取下载的文件
                        val downloadedFile = File(downloadDir, fileName)

                        // 执行操作
                        if (downloadedFile.exists() && downloadedFile.isFile) {
                            val driverData = GpuDriverHelper.getMetadataFromZip(downloadedFile)
                            val adapter = _binding?.listDrivers?.adapter as? DriverAdapter
                            adapter?.apply {
                                addItem(driverData.toDriver())
                                selectItem(currentList.indices.last)
                            }
                            driverViewModel?.let { viewModel ->
                                viewModel.onDriverAdded(Pair(downloadedFile.absolutePath, driverData))
                                viewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
                            }
                            // Show a message indicating processing completion
                            Toast.makeText(context, "GPU驱动程序处理完成", Toast.LENGTH_SHORT).show()
                        } else {
                            // 如果没有找到驱动程序压缩文件，则显示相应的消息
                            Toast.makeText(context, "未找到GPU驱动程序", Toast.LENGTH_SHORT).show()
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        // 下载失败
                        progressDialog.dismiss()
                        // 显示下载失败提示
                        Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    context.registerReceiver(receiver, filter)

    // 定时更新进度条
    val timer = Timer()
    timer.scheduleAtFixedRate(object : TimerTask() {
        override fun run() {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm?.query(query)
            cursor?.use {
                if (it.moveToFirst()) {
                    val bytesDownloaded = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = it.getInt(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val progress = (bytesDownloaded.toFloat() / bytesTotal.toFloat() * 100).toInt()
                    progressDialog.progress = progress
                }
            }
        }
    }, 0, 1000) // 每秒钟更新一次

    return downloadId
        }

        binding.buttonDownload.setOnClickListener {
    // 加载自定义布局
    val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_download, null)

    // 获取布局中的文本视图
    val textTitle1 = dialogView.findViewById<TextView>(R.id.text_title1)
    val textDownload1 = dialogView.findViewById<TextView>(R.id.text_download1)
    val textTitle2 = dialogView.findViewById<TextView>(R.id.text_title2)
    val textDownload2 = dialogView.findViewById<TextView>(R.id.text_download2)
    val textTitle3 = dialogView.findViewById<TextView>(R.id.text_title3)
    val textDownload3 = dialogView.findViewById<TextView>(R.id.text_download3)

    // 设置标题文本
    textTitle1.text = "Turnip-24.1.0.adpkg_R18"
    textTitle2.text = "Turnip-24.1.0.adpkg_R17"
    textTitle3.text = "Turnip-24.1.0.adpkg_R16"

    // 设置下载文本
    textDownload1.setOnClickListener {
        // 创建ProgressDialog
        val progressDialog = ProgressDialog(requireContext())
        progressDialog.setMessage("下载中...")
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.isIndeterminate = false
        progressDialog.setCancelable(false)
        
        // 显示ProgressDialog
        progressDialog.show()

        val url = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v24.1.0_R18/Turnip-24.1.0.adpkg_R18.zip"
        val downloadId = downloadFile(requireContext(), url, "Turnip-24.1.0.adpkg_R18.zip", progressDialog)
    }
    textDownload2.setOnClickListener {
        // 创建ProgressDialog
        val progressDialog = ProgressDialog(requireContext())
        progressDialog.setMessage("下载中...")
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.isIndeterminate = false
        progressDialog.setCancelable(false)
        
        // 显示ProgressDialog
        progressDialog.show()

        val url = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v24.1.0_R17/turnip-24.1.0.adpkg_R17-v2.zip"
        val downloadId = downloadFile(requireContext(), url, "Turnip-24.1.0.adpkg_R17.zip", progressDialog)
    }
    textDownload3.setOnClickListener {
        // 创建ProgressDialog
        val progressDialog = ProgressDialog(requireContext())
        progressDialog.setMessage("下载中...")
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.isIndeterminate = false
        progressDialog.setCancelable(false)
        
        // 显示ProgressDialog
        progressDialog.show()

        val url = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v24.1.0_R16/Turnip-24.1.0.adpkg_R16.zip"
        val downloadId = downloadFile(requireContext(), url, "Turnip-24.1.0.adpkg_R16.zip", progressDialog)
    }
    // 创建并显示对话框
    val dialogBuilder = AlertDialog.Builder(requireContext())
    dialogBuilder.setView(dialogView)
    val dialog = dialogBuilder.create()
    dialog.show()
        }

        binding.listDrivers.apply {
            layoutManager = GridLayoutManager(
                requireContext(),
                resources.getInteger(R.integer.grid_columns)
            )
            adapter = DriverAdapter(driverViewModel)
        }

        setInsets()
    }

    override fun onDestroy() {
        super.onDestroy()
        driverViewModel.onCloseDriverManager(args.game)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right

            binding.toolbarDrivers.updateMargins(left = leftInsets, right = rightInsets)
            binding.listDrivers.updateMargins(left = leftInsets, right = rightInsets)

            val fabSpacing = resources.getDimensionPixelSize(R.dimen.spacing_fab)
            binding.buttonInstall.updateMargins(
                left = leftInsets + fabSpacing,
                right = rightInsets + fabSpacing,
                bottom = barInsets.bottom + fabSpacing
            )

            binding.listDrivers.updatePadding(
                bottom = barInsets.bottom +
                    resources.getDimensionPixelSize(R.dimen.spacing_bottom_list_fab)
            )

            windowInsets
        }

    private val getDriver =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            ProgressDialogFragment.newInstance(
                requireActivity(),
                R.string.installing_driver,
                false
            ) { _, _ ->
                val driverPath =
                    "${GpuDriverHelper.driverStoragePath}${FileUtil.getFilename(result)}"
                val driverFile = File(driverPath)

                // Ignore file exceptions when a user selects an invalid zip
                try {
                    if (!GpuDriverHelper.copyDriverToInternalStorage(result)) {
                        throw IOException("Driver failed validation!")
                    }
                } catch (_: IOException) {
                    if (driverFile.exists()) {
                        driverFile.delete()
                    }
                    return@newInstance getString(R.string.select_gpu_driver_error)
                }

                val driverData = GpuDriverHelper.getMetadataFromZip(driverFile)
                val driverInList =
                    driverViewModel.driverData.firstOrNull { it.second == driverData }
                if (driverInList != null) {
                    return@newInstance getString(R.string.driver_already_installed)
                } else {
                    driverViewModel.onDriverAdded(Pair(driverPath, driverData))
                    withContext(Dispatchers.Main) {
                        if (_binding != null) {
                            val adapter = binding.listDrivers.adapter as DriverAdapter
                            adapter.addItem(driverData.toDriver())
                            adapter.selectItem(adapter.currentList.indices.last)
                            driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
                            binding.listDrivers
                                .smoothScrollToPosition(adapter.currentList.indices.last)
                        }
                    }
                }
                return@newInstance Any()
            }.show(childFragmentManager, ProgressDialogFragment.TAG)
        }
}
