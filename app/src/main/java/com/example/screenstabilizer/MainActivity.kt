package com.example.screenstabilizer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.example.screenstabilizer.databinding.ActivityMainBinding
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity(), SensorHandler.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorHandler: SensorHandler

    // PDF Renderer specific variables
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var currentPageIndex: Int = 0

    // State variables
    private val contentScale = 1.4f
    private var sensitivity = 1.0f
    private var isStabilizationOn = true
    private var isFileLoaded = false
    private var currentFileUri: Uri? = null

    // Auto-hide controls handler
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadContent(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(ThemeManager.getSavedTheme(this))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        sensorHandler = SensorHandler(this, this)
        setupUI()
        handleGyroscopeAvailability()

        if (savedInstanceState != null) {
            // UPDATED: Using non-deprecated getParcelable
            val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable(KEY_FILE_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable(KEY_FILE_URI)
            }
            savedUri?.let { uri ->
                val page = savedInstanceState.getInt(KEY_PAGE_INDEX, 0)
                loadContent(uri, page)
            }
        } else {
            checkForSavedFile()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_FILE_URI, currentFileUri)
        outState.putInt(KEY_PAGE_INDEX, currentPageIndex)
    }

    private fun setupUI() {
        binding.contentContainer.scaleX = contentScale
        binding.contentContainer.scaleY = contentScale

        binding.fabOpenFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("image/*", "application/pdf"))
        }

        binding.viewport.setOnClickListener {
            if (isFileLoaded) {
                showAndScheduleHideControls()
            }
        }

        binding.switchStabilization.setOnCheckedChangeListener { _, isChecked ->
            isStabilizationOn = isChecked
            if (isChecked && isFileLoaded) {
                sensorHandler.start()
            } else {
                sensorHandler.stop()
                recenterView()
            }
        }

        binding.btnRecenter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sensorHandler.recenter()
            recenterView()
        }

        binding.seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = 0.1f + (progress / 100f) * 1.9f
                binding.textSensitivityValue.text = String.format(Locale.getDefault(), "%.1fx", sensitivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.textSensitivityValue.text = String.format(Locale.getDefault(), "%.1fx", sensitivity)

        binding.seekbarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val brightnessValue = if (progress < 10) 0.1f else progress / 100f
                val layoutParams = window.attributes
                layoutParams.screenBrightness = brightnessValue
                window.attributes = layoutParams
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.seekbarBrightness.progress = 50

        binding.radioGroupModes.setOnCheckedChangeListener { _, checkedId ->
            val alpha = when (checkedId) {
                R.id.radio_low -> 0.98f
                R.id.radio_high -> 0.90f
                else -> 0.95f
            }
            sensorHandler.setFilterAlpha(alpha)
        }

        binding.btnNextPage.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); showPage(currentPageIndex + 1) }
        binding.btnPrevPage.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); showPage(currentPageIndex - 1) }
        binding.btnGoToPage.setOnClickListener { showGoToPageDialog() }
    }

    private fun loadContent(uri: Uri, startPage: Int = 0) {
        currentFileUri = uri
        closeRenderer()

        val type = contentResolver.getType(uri)
        binding.imageView.visibility = View.GONE
        binding.pdfNavControls.visibility = View.GONE

        try {
            when {
                type == "application/pdf" -> {
                    openRenderer(uri)
                    showPage(startPage)
                    onFileLoaded()
                }
                type?.startsWith("image/") == true -> {
                    val bitmap = getSafeBitmapFromUri(uri)
                    if (bitmap != null) {
                        binding.imageView.setImageBitmap(bitmap)
                        binding.imageView.visibility = View.VISIBLE
                        onFileLoaded()
                    } else {
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    Toast.makeText(this, "Unsupported file type: $type", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFileLoaded() {
        isFileLoaded = true
        binding.placeholderContainer.visibility = View.GONE
        invalidateOptionsMenu()
        if (isStabilizationOn) {
            sensorHandler.start()
        }
        showAndScheduleHideControls()
        saveLastFile()
        showFirstTimeHint()
    }

    @Throws(IOException::class)
    private fun openRenderer(uri: Uri) {
        parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
        parcelFileDescriptor?.let {
            pdfRenderer = PdfRenderer(it)
        }
    }

    private fun closeRenderer() {
        try {
            currentPage?.close()
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            pdfRenderer = null
            currentPage = null
            parcelFileDescriptor = null
        }
    }

    private fun showPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= renderer.pageCount) {
            return
        }

        currentPage?.close()
        currentPage = renderer.openPage(index).also { currentPageIndex = index }
        val page = currentPage ?: return

        // UPDATED: Using modern WindowMetrics API
        val screenWidth: Int
        val screenHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            screenWidth = windowMetrics.bounds.width()
            screenHeight = windowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        val scale = min(screenWidth.toFloat() / page.width, screenHeight.toFloat() / page.height)
        val bitmapWidth = (page.width * scale).toInt()
        val bitmapHeight = (page.height * scale).toInt()

        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            Toast.makeText(this, "Invalid page dimensions", Toast.LENGTH_SHORT).show()
            return
        }

        // UPDATED: Using KTX createBitmap function
        val bitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        binding.imageView.setImageBitmap(bitmap)
        binding.imageView.visibility = View.VISIBLE

        updatePageIndicator()
        binding.btnPrevPage.isEnabled = index > 0
        binding.btnNextPage.isEnabled = index + 1 < renderer.pageCount
        saveLastFile()
    }

    private fun updatePageIndicator() {
        val pageCount = pdfRenderer?.pageCount ?: 0
        binding.textPageNumber.text = getString(R.string.page_indicator_format, currentPageIndex + 1, pageCount)
    }

    private fun getSafeBitmapFromUri(uri: Uri): Bitmap? {
        var inputStream: InputStream? = null
        try {
            inputStream = contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // UPDATED: Using modern WindowMetrics API
            val screenWidth: Int
            val screenHeight: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                screenWidth = windowMetrics.bounds.width()
                screenHeight = windowMetrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
            }

            options.inSampleSize = calculateInSampleSize(options, screenWidth, screenHeight)
            options.inJustDecodeBounds = false
            inputStream = contentResolver.openInputStream(uri)
            return BitmapFactory.decodeStream(inputStream, null, options)
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        } finally {
            inputStream?.close()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun onRotationChanged(rotationX: Float, rotationY: Float) {
        // UPDATED: Using modern getRotation() for API 30+
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val (finalRotationX, finalRotationY) = when (rotation) {
            Surface.ROTATION_90 -> rotationY to -rotationX
            Surface.ROTATION_270 -> -rotationY to rotationX
            else -> rotationX to rotationY
        }

        val maxTranslationX = (binding.viewport.width * (contentScale - 1f)) / 2f
        val maxTranslationY = (binding.viewport.height * (contentScale - 1f)) / 2f
        binding.contentContainer.translationX = -finalRotationY * maxTranslationX * sensitivity
        binding.contentContainer.translationY = finalRotationX * maxTranslationY * sensitivity
    }

    private fun recenterView() {
        binding.contentContainer.animate().translationX(0f).translationY(0f).setDuration(150).start()
    }

    override fun onResume() {
        super.onResume()
        if (isStabilizationOn && isFileLoaded) {
            sensorHandler.start()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorHandler.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeRenderer()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_toggle_controls).isVisible = isFileLoaded
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_theme -> {
                toggleTheme()
                true
            }
            R.id.action_toggle_controls -> {
                toggleControlsPanel()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleControlsPanel() {
        if (binding.controlsPanel.isVisible) {
            binding.controlsPanel.animate()
                .translationY(binding.controlsPanel.height.toFloat())
                .alpha(0.0f)
                .setDuration(300)
                .withEndAction { binding.controlsPanel.visibility = View.GONE }
        } else {
            binding.controlsPanel.visibility = View.VISIBLE
            binding.controlsPanel.alpha = 0.0f
            binding.controlsPanel.translationY = binding.controlsPanel.height.toFloat()
            binding.controlsPanel.animate()
                .translationY(0f)
                .alpha(1.0f)
                .setDuration(300)
                .start()
        }
    }

    private fun toggleTheme() {
        val currentTheme = ThemeManager.getSavedTheme(this)
        val newTheme = if (currentTheme == ThemeManager.THEME_DARK) ThemeManager.THEME_LIGHT else ThemeManager.THEME_DARK
        ThemeManager.saveTheme(this, newTheme)
        recreate()
    }

    private fun showAndScheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        binding.pdfNavControls.animate().alpha(1.0f).setDuration(200).start()
        binding.fabOpenFile.show()
        hideHandler.postDelayed(hideRunnable, 3000)
    }

    private fun hideControls() {
        binding.pdfNavControls.animate().alpha(0.0f).setDuration(500).start()
        binding.fabOpenFile.hide()
    }

    private fun showGoToPageDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_go_to_page, null)
        val editText = dialogView.findViewById<EditText>(R.id.edit_text_page_number)

        AlertDialog.Builder(this)
            .setTitle(R.string.go_to_page_title)
            .setView(dialogView)
            .setPositiveButton(R.string.go_to_page_go) { dialog, _ ->
                val pageNumStr = editText.text.toString()
                if (pageNumStr.isNotEmpty()) {
                    try {
                        val pageNum = pageNumStr.toInt() - 1
                        showPage(pageNum)
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, R.string.go_to_page_error, Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.go_to_page_cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun saveLastFile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // UPDATED: Using KTX SharedPreferences.edit
        prefs.edit {
            putString(KEY_LAST_URI, currentFileUri?.toString())
            putInt(KEY_PAGE_INDEX, currentPageIndex)
        }
    }

    private fun checkForSavedFile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_LAST_URI, null)
        val page = prefs.getInt(KEY_PAGE_INDEX, 0)
        if (uriString != null) {
            // UPDATED: Using KTX String.toUri
            val uri = uriString.toUri()
            val hasPermission = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (hasPermission) {
                loadContent(uri, page)
            }
        }
    }

    private fun showFirstTimeHint() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasShownHint = prefs.getBoolean(KEY_HINT_SHOWN, false)
        if (!hasShownHint) {
            AlertDialog.Builder(this)
                .setTitle(R.string.hint_title)
                .setMessage(R.string.hint_message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
            // UPDATED: Using KTX SharedPreferences.edit
            prefs.edit {
                putBoolean(KEY_HINT_SHOWN, true)
            }
        }
    }

    private fun handleGyroscopeAvailability() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) {
            Toast.makeText(this, R.string.no_gyroscope_message, Toast.LENGTH_LONG).show()
            binding.switchStabilization.isEnabled = false
            binding.btnRecenter.isEnabled = false
            binding.seekbarSensitivity.isEnabled = false
            binding.radioGroupModes.isEnabled = false
            for (i in 0 until binding.radioGroupModes.childCount) {
                binding.radioGroupModes.getChildAt(i).isEnabled = false
            }
        }
    }

    companion object {
        private const val KEY_FILE_URI = "key_file_uri"
        private const val KEY_PAGE_INDEX = "key_page_index"
        private const val PREFS_NAME = "StabilizerPrefs"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_HINT_SHOWN = "hint_shown"
    }
}
