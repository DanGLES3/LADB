package com.draco.ladb.viewmodels

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val _outputText = MutableLiveData<String>()
    val outputText: LiveData<String> = _outputText

    val adb = ADB.getInstance(getApplication<Application>().applicationContext).also {
        viewModelScope.launch(Dispatchers.IO) {
            it.initializeClient()
        }
    }

    init {
        startOutputThread()
    }

    /**
     * Show a dialog that tells the user that their ABI version is unsupported
     */
    fun abiUnsupportedDialog(dialog: MaterialAlertDialogBuilder) {
        if (Build.SUPPORTED_64_BIT_ABIS.isNullOrEmpty() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.show()
        }
    }

    /**
     * Continuously update shell output
     */
    private fun startOutputThread() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val out = readOutputFile(adb.outputBufferFile)
                val currentText = _outputText.value
                if (out != currentText)
                    _outputText.postValue(out)
                Thread.sleep(ADB.OUTPUT_BUFFER_DELAY_MS)
            }
        }
    }

    /**
     * Erase all shell text
     */
    fun clearOutputText() {
        adb.outputBufferFile.writeText("")
    }

    /**
     * Check if the user should be prompted to pair
     */
    fun shouldWePair(sharedPreferences: SharedPreferences): Boolean {
        val context = getApplication<Application>().applicationContext

        if (!sharedPreferences.getBoolean(context.getString(R.string.paired_key), false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                return true
        }

        return false
    }

    /**
     * Return the contents of the script from the intent
     */
    fun getScriptFromIntent(intent: Intent): String? {
        val context = getApplication<Application>().applicationContext

        return when (intent.type) {
            "text/x-sh" -> {
                val uri = Uri.parse(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM).toString())
                context.contentResolver.openInputStream(uri)?.bufferedReader().use {
                    it?.readText()
                }
            }
            "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }

    /**
     * Read the content of the ABD output file
     */
    private fun readOutputFile(file: File): String {
        val out = ByteArray(adb.getOutputBufferSize())

        synchronized(file) {
            if (!file.exists())
                return ""

            file.inputStream().use {
                val size = it.channel.size()

                if (size <= out.size)
                    return String(it.readBytes())

                val newPos = (it.channel.size() - out.size)
                it.channel.position(newPos)
                it.read(out)
            }
        }

        return String(out)
    }
}
