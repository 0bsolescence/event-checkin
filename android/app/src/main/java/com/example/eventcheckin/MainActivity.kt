package com.example.eventcheckin

import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Single-activity twin of the Windows MainForm. The device's own NFC radio is
 * the badge reader: reader mode is enabled while the activity is in the
 * foreground, each tap delivers the ISO 14443 anti-collision UID via Tag.id,
 * and the flow mirrors the Windows app exactly — enroll-on-first-tap,
 * duplicate taps at the same event refused (not doubled), live headcount,
 * formula-neutralized CSV export through the Storage Access Framework (no
 * storage permission), and no network permission anywhere in the manifest.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var db: Db
    private var nfc: NfcAdapter? = null
    private var beeper: ToneGenerator? = null

    private lateinit var eventSpinner: Spinner
    private lateinit var headcount: TextView
    private lateinit var status: TextView
    private lateinit var rows: ArrayAdapter<String>

    private var events: List<Pair<Long, String>> = emptyList()
    private var eventId: Long? = null
    private var eventName: String? = null

    // CSV content built at click time, written when the SAF picker returns.
    private var pendingCsv: String? = null
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val csv = pendingCsv
            pendingCsv = null
            if (uri == null || csv == null) {
                status.text = getString(R.string.export_cancelled)
                return@registerForActivityResult
            }
            contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            status.text = getString(R.string.exported)
            Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = Db(this)
        beeper = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        nfc = NfcAdapter.getDefaultAdapter(this)

        eventSpinner = findViewById(R.id.event_spinner)
        headcount = findViewById(R.id.headcount)
        status = findViewById(R.id.status)
        rows = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        findViewById<ListView>(R.id.attendance).adapter = rows

        findViewById<Button>(R.id.new_event).setOnClickListener { newEvent() }
        findViewById<Button>(R.id.export_csv).setOnClickListener { export() }
        eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                selectEvent(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {
                eventId = null; eventName = null
            }
        }

        updateCount()
        refreshEvents()
        if (events.isEmpty()) newEvent()
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc
        when {
            adapter == null -> status.text = getString(R.string.status_no_nfc)
            !adapter.isEnabled -> status.text = getString(R.string.status_nfc_off)
            else -> {
                // Reader mode = foreground-only dispatch: taps reach this app
                // alone while it is visible, nothing is dispatched elsewhere,
                // and NDEF handling is skipped — we only want the 14443 UID.
                adapter.enableReaderMode(
                    this,
                    { tag -> runOnUiThread { onTap(tag) } },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null)
                status.text = getString(R.string.status_ready)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        beeper?.release()
        if (::db.isInitialized) db.close()
    }

    private fun onTap(tag: Tag) {
        val uid = tag.id
        if (uid == null || uid.isEmpty()) {
            status.text = getString(R.string.status_unreadable)
            return
        }
        if (eventId == null) {
            status.text = getString(R.string.select_event_first)
            return
        }
        val hash = db.hashUid(uid)
        val known = db.lookupName(hash)
        if (known != null) {
            checkIn(hash, known)
        } else {
            prompt(getString(R.string.enroll_title), getString(R.string.enroll_message)) { name ->
                if (name.isNullOrBlank()) {
                    status.text = getString(R.string.enrollment_cancelled)
                } else {
                    val trimmed = name.trim()
                    db.enroll(hash, trimmed)
                    checkIn(hash, trimmed)
                }
            }
        }
    }

    private fun checkIn(hash: String, name: String) {
        // Re-read: the selection can change while the enroll dialog is open.
        val ev = eventId ?: run { status.text = getString(R.string.select_event_first); return }
        if (db.recordTap(ev, hash)) {
            rows.insert("$name — ${pretty(Db.now())}", 0)
            status.text = getString(R.string.checked_in, name)
            beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } else {
            status.text = getString(R.string.already_checked_in, name)
        }
        updateCount()
    }

    private fun newEvent() =
        prompt(getString(R.string.new_event_title), getString(R.string.new_event_message)) { name ->
            if (!name.isNullOrBlank()) {
                db.createEvent(name.trim())
                refreshEvents()
            }
        }

    private fun refreshEvents() {
        events = db.listEvents()
        eventSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, events.map { it.second })
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (events.isNotEmpty()) {
            eventSpinner.setSelection(0) // newest first, mirrors the Windows combo
        } else {
            eventId = null; eventName = null
            rows.clear()
            updateCount()
        }
    }

    private fun selectEvent(pos: Int) {
        val (id, name) = events.getOrNull(pos) ?: return
        eventId = id
        eventName = name
        rows.clear()
        // Newest at the top, matching where live taps are inserted.
        for ((who, at) in db.attendance(id).asReversed()) rows.add("$who — ${pretty(at)}")
        updateCount()
    }

    private fun export() {
        val ev = eventId ?: return
        val name = eventName ?: return
        pendingCsv = db.buildCsv(ev, name)
        val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        // SAF uniquifies on collision — an earlier audit snapshot is never overwritten.
        exportLauncher.launch("attendance_${safe}_$stamp.csv")
    }

    private fun updateCount() {
        headcount.text = getString(R.string.headcount, rows.count)
    }

    private fun pretty(iso: String): String = try {
        OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    } catch (_: Exception) {
        iso
    }

    private fun prompt(title: String, message: String, onResult: (String?) -> Unit) {
        val box = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onResult(box.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(null) }
            .setOnCancelListener { onResult(null) }
            .show()
    }
}
