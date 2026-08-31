package com.example.mmsrcsmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private var mmsRcsObserver: ContentObserver? = null
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_MMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECEIVE_MMS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startMonitoring()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("Permissions granted. Starting monitor...")
                startMonitoring()
            } else {
                appendLog("Error: Permissions denied. Cannot read messages.")
            }
        }
    }

    private fun startMonitoring() {
        val mainHandler = Handler(Looper.getMainLooper())
        
        mmsRcsObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val queryUri = uri ?: Uri.parse("content://mms")
                if (queryUri.toString().contains("mms")) {
                    mainHandler.postDelayed({
                        processMmsRcsMessage(queryUri)
                    }, 500)
                }
            }
        }

        contentResolver.registerContentObserver(
            Uri.parse("content://mms-sms/"),
            true,
            mmsRcsObserver!!
        )
        appendLog("ContentObserver registered for 'content://mms-sms/'")
    }

    private fun processMmsRcsMessage(mmsUri: Uri) {
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_BOX)
        var cursor: Cursor? = null
        
        try {
            cursor = contentResolver.query(mmsUri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val msgId = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
                val msgBox = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                
                val isReceived = msgBox == Telephony.Mms.MESSAGE_BOX_INBOX
                val phoneNumbers = getMmsAddresses(this, msgId, isReceived)
                val messageText = getMmsTextBody(this, msgId)
                
                val logLine = when (msgBox) {
                    Telephony.Mms.MESSAGE_BOX_INBOX -> "[RECEIVED] From: ${phoneNumbers.sender}\nText: $messageText"
                    Telephony.Mms.MESSAGE_BOX_SENT -> "[SENT] To: ${phoneNumbers.recipients.joinToString()}\nText: $messageText"
                    else -> "[STATUS CHANGE] Box Type ($msgBox) ID: $msgId\nText: $messageText"
                }
                appendLog(logLine)
            }
        } catch (e: Exception) {
            appendLog("Error querying MMS info: ${e.localizedMessage}")
        } finally {
            cursor?.close()
        }
    }

    data class MmsParticipants(val sender: String, val recipients: List<String>)

    private fun getMmsAddresses(context: Context, msgId: String, isReceived: Boolean): MmsParticipants {
        val uri = Uri.parse("content://mms/$msgId/addr")
        val projection = arrayOf("address", "type")
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        
        var sender = "Unknown"
        val recipients = mutableListOf<String>()
        
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                val type = it.getInt(it.getColumnIndexOrThrow("type"))
                when (type) {
                    137 -> sender = address
                    151, 130 -> recipients.add(address)
                }
            }
        }
        
        if (!isReceived && (sender == "insert-address-token" || sender == "Unknown")) {
            sender = "Me"
        }
        return MmsParticipants(sender, recipients)
    }

    private fun getMmsTextBody(context: Context, msgId: String): String {
        val uri = Uri.parse("content://mms/part")
        val selection = "mid = ? AND ct = ?"
        val selectionArgs = arrayOf(msgId, "text/plain")
        val projection = arrayOf("text")
        
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        var bodyText = "[No Text Body Content found]"
        
        cursor?.use {
            if (it.moveToFirst()) {
                val directText = it.getString(it.getColumnIndexOrThrow("text"))
                if (!directText.isNullOrEmpty()) {
                    bodyText = directText
                }
            }
        }
        return bodyText
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            val currentText = logTextView.text.toString()
            logTextView.text = "$currentText\n\n-------------------------\n$text"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mmsRcsObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
    }
}
