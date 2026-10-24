package com.downls10

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class DownLS10MainActivity : Activity() {

    private var showSpeed = true
    private var storagePermissionAsked = false

    private lateinit var adapter: DownloadAdapter
    private val refreshListener: () -> Unit = { adapter.notifyDataSetChanged() }
    private lateinit var downloadFileAdapter: DownloadFileAdapter
    private val downloadFilesList = mutableListOf<DownloadFileItem>()

    private fun mainPrefs() = getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showSpeed = mainPrefs().getBoolean("showSpeed", true)
        
        // طلب إذن التخزين مرة واحدة فقط عند أول تشغيل
        requestStoragePermissionOnce()
        
        DownloadsRepository.ensureLoaded(this)

        val btnAddUrl = findViewById<Button>(R.id.btnAddUrl)
        val btnBrowser = findViewById<Button>(R.id.btnBrowser)
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val btnDeleteCompleted = findViewById<Button>(R.id.btnDeleteCompleted)
        val btnDeleteAllDownloads = findViewById<Button>(R.id.btnDeleteAllDownloads)
        val btnShowDownloadFiles = findViewById<Button>(R.id.btnShowDownloadFiles)
        val listView = findViewById<ListView>(R.id.listView)
        val downloadFilesContainer = findViewById<LinearLayout>(R.id.downloadFilesContainer)
        val downloadFilesListView = findViewById<ListView>(R.id.downloadFilesList)

        adapter = DownloadAdapter(
            this,
            DownloadsRepository.downloadList,
            showSpeed,
            onPauseResumeClick = { item -> handlePauseResume(item) },
            onDeleteClick = { item -> showDeleteDialog(item) }
        )
        listView.adapter = adapter

        // إعداد قائمة ملفات التنزيلات
        downloadFileAdapter = DownloadFileAdapter(
            this,
            downloadFilesList,
            onAddClick = { item -> onAddDownloadFile(item) },
            onDownloadClick = { item -> onDownloadFile(item) },
            onCancelClick = { item -> onCancelDownloadFile(item) }
        )
        downloadFilesListView.adapter = downloadFileAdapter

        btnAddUrl.setOnClickListener { showAddUrlDialog() }
        btnDeleteCompleted.setOnClickListener { confirmDeleteCompletedDownloads() }
        btnDeleteAllDownloads.setOnClickListener { confirmDeleteAllDownloads() }
        btnShowDownloadFiles.setOnClickListener { showDownloadFilesList() }
        // منطقة لمس أكبر للكرة الرمادية مع الإبقاء على شكلها الحالي.
        btnBrowser.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> true
                android.view.MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnBrowser.setOnClickListener {
            val browserIntent = Intent(this, BrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(browserIntent)
        }
        btnMenu.setOnClickListener { showOptionsMenu() }
    }

    override fun onResume() {
        super.onResume()
        DownloadsRepository.addListener(refreshListener)
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        DownloadsRepository.removeListener(refreshListener)
    }

    /**
     * يطلب إذن التخزين مرة واحدة فقط عند أول تشغيل للتطبيق.
     * على Android 10+ (API 29+) نستخدم MediaStore ولا نحتاج إذن WRITE_EXTERNAL_STORAGE.
     * على Android 9 وأقدم، نطلب الإذن ونحفظ أننا طلبناه.
     */
    private fun requestStoragePermissionOnce() {
        val prefs = mainPrefs()
        storagePermissionAsked = prefs.getBoolean("storagePermissionAsked", false)
        
        // إذا كنا طلبنا الإذن من قبل، لا نطلبه مرة أخرى
        if (storagePermissionAsked) return
        
        // Android 10+ يستخدم MediaStore/Scoped Storage ولا يحتاج WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // نعتبر أننا طلبنا الإذن ولا نحتاج لطلبه
            prefs.edit().putBoolean("storagePermissionAsked", true).apply()
            return
        }
        
        // Android 9 وأقدم: نطلب إذن WRITE_EXTERNAL_STORAGE
        val hasPermission = ContextCompat.checkSelfPermission(
            this, 
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        
        if (hasPermission) {
            // الإذن ممنوح بالفعل، نعتبر أننا طلبناه
            prefs.edit().putBoolean("storagePermissionAsked", true).apply()
        } else {
            // نطلب الإذن
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERM_CODE
            )
        }
    }

    companion object {
        private const val STORAGE_PERM_CODE = 4102
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERM_CODE) {
            // حفظ أننا طلبنا الإذن بغض النظر عن النتيجة (المستخدم وافق أو رفض)
            mainPrefs().edit().putBoolean("storagePermissionAsked", true).apply()
        }
    }

    private fun confirmDeleteCompletedDownloads() {
        val completed = DownloadsRepository.downloadList.filter { it.state == DownloadState.COMPLETED }
        if (completed.isEmpty()) {
            Toast.makeText(this, "لا توجد تنزيلات مكتملة للحذف", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف التنزيلات المكتملة")
            .setMessage("سيتم حذف ${completed.size} تنزيل مكتمل من القائمة والملفات المحفوظة. هل تريد المتابعة؟")
            .setPositiveButton("موافق") { _, _ ->
                completed.toList().forEach { DownloadsRepository.deleteDownload(this, it, deleteFileOnDisk = true) }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmDeleteAllDownloads() {
        val all = DownloadsRepository.downloadList.toList()
        if (all.isEmpty()) {
            Toast.makeText(this, "قائمة التنزيلات فارغة", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف جميع التنزيلات")
            .setMessage("سيتم إيقاف التنزيلات وحذف ${all.size} عنصرًا من القائمة والملفات الجزئية أو المكتملة. هل تريد المتابعة؟")
            .setPositiveButton("موافق") { _, _ ->
                all.forEach { DownloadsRepository.deleteDownload(this, it, deleteFileOnDisk = true) }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAddUrlDialog() {
        val input = EditText(this)
        input.hint = "إلصق رابط التنزيل هنا..."

        AlertDialog.Builder(this)
            .setTitle("إضافة تنزيل جديد")
            .setView(input)
            .setPositiveButton("تنزيل") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    DownloadsRepository.startNewDownload(this, url, showToast = false)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showOptionsMenu() {
        val options = arrayOf(
            if (showSpeed) "إخفاء سرعة التنزيل" else "إظهار سرعة التنزيل",
            "التنزيلات المتزامنة",
            "السجل (History)"
        )

        AlertDialog.Builder(this)
            .setTitle("الخيارات")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        showSpeed = !showSpeed
                        mainPrefs().edit().putBoolean("showSpeed", showSpeed).apply()
                        adapter.setShowSpeed(showSpeed)
                    }
                    1 -> showConcurrentDownloadsDialog()
                    2 -> showHistoryDialog()
                }
            }
            .show()
    }

    private fun showConcurrentDownloadsDialog() {
        val values = (1..10).toList()
        val current = DownloadsRepository.getMaxConcurrentDownloads(this)

        AlertDialog.Builder(this)
            .setTitle("التنزيلات المتزامنة")
            .setSingleChoiceItems(
                values.map { it.toString() }.toTypedArray(),
                current - 1
            ) { dialog, which ->
                DownloadsRepository.setMaxConcurrentDownloads(this, values[which])
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("رابط", text))
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        val history = DownloadHistory.get(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "سجل التنزيلات فارغ", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        history.forEach { entry ->
            val row = TextView(this).apply {
                text = entry.url
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener {
                    val browserIntent = Intent(this@DownLS10MainActivity, BrowserActivity::class.java).apply {
                        putExtra(BrowserActivity.EXTRA_OPEN_URL, entry.url)
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    startActivity(browserIntent)
                }
                setOnLongClickListener {
                    copyToClipboard(entry.url)
                    true
                }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("سجل التنزيلات")
            .setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ ->
                DownloadHistory.clear(this)
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun handlePauseResume(item: DownloadItem) {
        if (item.state == DownloadState.DOWNLOADING) {
            DownloadsRepository.pauseDownload(this, item)
        } else if (item.state == DownloadState.PAUSED || item.state == DownloadState.ERROR) {
            DownloadsRepository.resumeDownload(this, item)
        }
    }

    private fun showDeleteDialog(item: DownloadItem) {
        val options = arrayOf("الحذف من القائمة فقط", "الحذف النهائي من الجهاز")

        AlertDialog.Builder(this)
            .setTitle("حذف التنزيل")
            .setItems(options) { _, which ->
                DownloadsRepository.deleteDownload(this, item, deleteFileOnDisk = (which == 1))
            }
            .show()
    }

    // دوال عرض قائمة ملفات التنزيلات والإجراءات عليها

    private fun showDownloadFilesList() {
        val downloadFilesContainer = findViewById<LinearLayout>(R.id.downloadFilesContainer)
        val downloadFilesListView = findViewById<ListView>(R.id.downloadFilesList)
        val tvDownloadFilesTitle = findViewById<TextView>(R.id.tvDownloadFilesTitle)

        // إخفاء قائمة التنزيلات الرئيسية
        val mainListView = findViewById<ListView>(R.id.listView)
        mainListView.visibility = View.GONE

        // عرض عنوان قائمة ملفات التنزيلات
        tvDownloadFilesTitle.text = "ملفات التنزيلات"

        // تحديث قائمة ملفات التنزيلات
        updateDownloadFilesList()

        // عرض حاوية ملفات التنزيلات
        downloadFilesContainer.visibility = View.VISIBLE
        downloadFilesListView.visibility = View.VISIBLE
    }

    private fun updateDownloadFilesList() {
        // تحميل قائمة ملفات التنزيلات من المجلد المخصص
        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(""), "DownLS10")
        val filesList = mutableListOf<DownloadFileItem>()

        if (downloadsDir.exists() && downloadsDir.isDirectory) {
            val files = downloadsDir.listFiles { _, name -> 
                // عرض جميع أنواع الملفات
                true
            }

            if (files != null) {
                for (file in files) {
                    if (file.isFile) {
                        val extension = getFileExtension(file.name)
                        val fileSize = file.length()
                        filesList.add(
                            DownloadFileItem(
                                fileName = file.name,
                                extension = extension,
                                fileSize = fileSize,
                                filePath = file.absolutePath
                            )
                        )
                    }
                }
            }
        }

        downloadFilesList.clear()
        downloadFilesList.addAll(filesList)
        downloadFileAdapter.notifyDataSetChanged()
    }

    private fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L * 1024L) return (bytes / 1024L).toString() + " KB"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun onAddDownloadFile(item: DownloadFileItem) {
        // إضافة الملف إلى قائمة التنزيلات دون بدء التنزيل
        val existingItem = DownloadsRepository.downloadList.find { it.fileName == item.fileName }
        if (existingItem != null) {
            Toast.makeText(this, "الملف ${item.fileName} موجود بالفعل في قائمة التنزيلات", Toast.LENGTH_SHORT).show()
            return
        }

        // إنشاء عنصر تنزيل جديد
        val newItem = DownloadItem(
            id = System.currentTimeMillis(),
            url = item.filePath,
            fileName = item.fileName,
            extension = item.extension,
            progress = 0,
            status = "في الانتظار...",
            speed = "0 KB/s",
            state = DownloadState.PAUSED,
            downloadedBytes = 0L,
            totalBytes = item.fileSize,
            localUri = item.filePath
        )

        DownloadsRepository.downloadList.add(newItem)
        adapter.notifyDataSetChanged()

        Toast.makeText(this, "تمت إضافة ${item.fileName} إلى قائمة التنزيلات", Toast.LENGTH_SHORT).show()
    }

    private fun onDownloadFile(item: DownloadFileItem) {
        // بدء تنزيل الملف فورًا
        val file = File(item.filePath)
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(this, "الملف ${item.fileName} غير موجود أو لا يمكن قراءته", Toast.LENGTH_SHORT).show()
            return
        }

        // التحقق من وجود الملف بالفعل في قائمة التنزيلات
        val existingItem = DownloadsRepository.downloadList.find { it.fileName == item.fileName }
        if (existingItem != null) {
            // إذا كان الملف موجودًا بالفعل، ابدأ تنزيلة
            DownloadsRepository.resumeDownload(this, existingItem)
            Toast.makeText(this, "بدء تنزيل ${item.fileName}", Toast.LENGTH_SHORT).show()
        } else {
            // إضافة وتنزيل الملف الجديد
            val newItem = DownloadItem(
                id = System.currentTimeMillis(),
                url = item.filePath,
                fileName = item.fileName,
                extension = item.extension,
                progress = 0,
                status = "في الانتظار...",
                speed = "0 KB/s",
                state = DownloadState.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = item.fileSize,
                localUri = item.filePath
            )

            DownloadsRepository.downloadList.add(newItem)
            adapter.notifyDataSetChanged()

            // بدء التنزيل
            DownloadsRepository.resumeDownload(this, newItem)
            Toast.makeText(this, "بدء تنزيل ${item.fileName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCancelDownloadFile(item: DownloadFileItem) {
        // إلغاء إضافة أو تنزيل الملف
        val existingItem = DownloadsRepository.downloadList.find { it.fileName == item.fileName }
        if (existingItem != null) {
            DownloadsRepository.deleteDownload(this, existingItem, deleteFileOnDisk = false)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "تم إلغاء ${item.fileName}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "الملف ${item.fileName} غير موجود في قائمة التنزيلات", Toast.LENGTH_SHORT).show()
        }
    }
}
