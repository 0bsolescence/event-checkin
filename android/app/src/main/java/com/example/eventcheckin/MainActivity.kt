package com.example.eventcheckin

import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Single-activity twin of the Windows MainWindow. The device's own NFC radio is
 * the badge reader: reader mode is enabled while the activity is in the
 * foreground, each tap delivers the ISO 14443 anti-collision UID via Tag.id,
 * and the flow mirrors the Windows app exactly — enroll-on-first-tap,
 * duplicate taps at the same event refused (not doubled), live headcount,
 * formula-neutralized CSV export through the Storage Access Framework (no
 * storage permission), and no network permission anywhere in the manifest.
 *
 * All branding — title, palette, logo — comes from the Theme loaded at startup
 * and re-read after an import; the code carries no organization-specific
 * strings. Theme files, logos and personnel rosters all arrive through SAF, so
 * none of it costs a permission.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var db: Db
    private var nfc: NfcAdapter? = null
    private var beeper: ToneGenerator? = null

    private var brand: Theme = Theme.neutral()

    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var logoView: ImageView
    private lateinit var appTitle: TextView
    private lateinit var headcountLabel: TextView
    private lateinit var eventSpinner: Spinner
    private lateinit var eventLabel: TextView
    private lateinit var setupButton: Button
    private lateinit var newEventButton: Button
    private lateinit var endEventButton: Button
    private lateinit var exportButton: Button
    private lateinit var headcount: TextView
    private lateinit var status: TextView
    private lateinit var rows: ArrayAdapter<String>

    private var events: List<Pair<Long, String>> = emptyList()
    private var eventId: Long? = null
    private var eventName: String? = null

    // True while an enroll dialog or the identity picker is open: further taps
    // are ignored so two quick unknown-badge reads cannot stack dialogs.
    private var tapBusy = false

    // CSV content built at click time, written when the SAF picker returns.
    // Saved in onSaveInstanceState: the activity can be recreated while the
    // picker is open, and the result callback must still find the content.
    private var pendingCsv: String? = null
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val csv = pendingCsv
            pendingCsv = null
            if (uri == null || csv == null) {
                status.text = getString(R.string.export_cancelled)
                return@registerForActivityResult
            }
            // "Exported" only after a verified write — a null stream or a
            // throwing write must report failure, never success.
            try {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("resolver returned no stream")
                out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                status.text = getString(R.string.exported)
                Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                status.text = getString(R.string.export_failed, e.localizedMessage ?: e.javaClass.simpleName)
            }
        }

    // Ending an event exports first and only ends if that export is WRITTEN, so
    // the CSV and the event it closes travel together. Held separately from
    // pendingCsv because a plain export must never end anything, and saved in
    // instance state for the same reason: the activity can be recreated while
    // the SAF picker is open, and the callback has to still know what it was
    // ending. A recreation that loses this state ends nothing — the safe way to
    // fail.
    private var pendingEndCsv: String? = null
    private var pendingEndId: Long? = null
    private var pendingEndName: String? = null
    private val endExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val csv = pendingEndCsv
            val id = pendingEndId
            val name = pendingEndName
            pendingEndCsv = null; pendingEndId = null; pendingEndName = null
            if (uri == null || csv == null || id == null || name == null) {
                status.text = getString(R.string.end_event_cancelled)
                return@registerForActivityResult
            }
            try {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("resolver returned no stream")
                out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                // The export is the precondition, not a courtesy: a failed write
                // leaves the event open rather than closing it with no record
                // off the device.
                status.text = getString(
                    R.string.export_failed, e.localizedMessage ?: e.javaClass.simpleName)
                return@registerForActivityResult
            }
            db.endEvent(id)
            refreshEvents()
            status.text = getString(R.string.event_ended, name)
        }

    // OpenDocument filters are wide on purpose: file providers hand JSON and CSV
    // over as application/octet-stream often enough that a strict filter makes
    // the very file the user came to pick unselectable.
    private val themeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importTheme(uri)
        }
    private val logoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLogo(uri)
        }
    private val rosterLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importRoster(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) enforces edge-to-edge; older versions
        // default to fitting system windows. Opting in everywhere makes the
        // window behave identically on all supported versions, so the insets
        // padding below is applied exactly once — without this, the status bar
        // overlays the header, or the padding would double (≤14).
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        pendingCsv = savedInstanceState?.getString(KEY_PENDING_CSV)
        savedInstanceState?.getString(KEY_PENDING_END_CSV)?.let { csv ->
            pendingEndCsv = csv
            pendingEndId = savedInstanceState.getLong(KEY_PENDING_END_ID)
            pendingEndName = savedInstanceState.getString(KEY_PENDING_END_NAME)
        }

        db = Db(this)
        beeper = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        nfc = NfcAdapter.getDefaultAdapter(this)

        root = findViewById(R.id.root)
        header = findViewById(R.id.header)
        logoView = findViewById(R.id.logo)
        appTitle = findViewById(R.id.app_title)
        headcountLabel = findViewById(R.id.headcount_label)
        headcount = findViewById(R.id.headcount)
        eventLabel = findViewById(R.id.event_label)
        eventSpinner = findViewById(R.id.event_spinner)
        setupButton = findViewById(R.id.setup)
        newEventButton = findViewById(R.id.new_event)
        endEventButton = findViewById(R.id.end_event)
        exportButton = findViewById(R.id.export_csv)
        status = findViewById(R.id.status)
        rows = ThemedRows()
        findViewById<ListView>(R.id.attendance).adapter = rows

        applyInsets()

        newEventButton.setOnClickListener { newEvent() }
        endEventButton.setOnClickListener { confirmEndEvent() }
        exportButton.setOnClickListener { export() }
        setupButton.setOnClickListener { showSetupMenu() }
        // Second way in, for anyone who reaches for the title first.
        appTitle.setOnLongClickListener { showSetupMenu(); true }
        eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                selectEvent(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {
                eventId = null; eventName = null
            }
        }

        applyTheme()
        updateCount()
        // Asks for a first event itself when there are none, the way the Windows
        // RefreshEvents does — including after the last event is deleted.
        refreshEvents()
    }

    /** The header paints under the status bar and the status line under the
     *  navigation bar, so the primary color reaches both edges the way the
     *  Windows header and status bar do. Base padding from the layout is kept. */
    private fun applyInsets() {
        val headerPad = intArrayOf(
            header.paddingLeft, header.paddingTop, header.paddingRight, header.paddingBottom)
        val statusPad = intArrayOf(
            status.paddingLeft, status.paddingTop, status.paddingRight, status.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, 0, bars.right, 0)
            header.setPadding(headerPad[0], headerPad[1] + bars.top, headerPad[2], headerPad[3])
            status.setPadding(statusPad[0], statusPad[1], statusPad[2], statusPad[3] + bars.bottom)
            insets
        }
    }

    /** Re-reads the theme file and repaints every surface it owns. Cheap enough
     *  to run on import rather than making the operator restart the kiosk. */
    private fun applyTheme() {
        brand = Theme.load(this)
        title = brand.appTitle
        root.setBackgroundColor(brand.background)
        header.setBackgroundColor(brand.primary)
        appTitle.text = brand.appTitle
        appTitle.setTextColor(brand.onPrimary)
        headcountLabel.setTextColor(brand.onPrimary)
        headcount.setTextColor(brand.accent)
        setupButton.setTextColor(brand.onPrimary)
        status.setBackgroundColor(brand.primary)
        status.setTextColor(brand.onPrimary)
        eventLabel.setTextColor(brand.foreground)
        newEventButton.setTextColor(brand.primary)
        endEventButton.setTextColor(brand.primary)
        exportButton.setTextColor(brand.primary)
        val logo = brand.logo
        if (logo != null) {
            logoView.setImageBitmap(logo)
            logoView.visibility = View.VISIBLE
        } else {
            logoView.setImageDrawable(null)
            logoView.visibility = View.GONE
        }
        rows.notifyDataSetChanged()
        (eventSpinner.adapter as? BaseAdapter)?.notifyDataSetChanged()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCsv?.let { outState.putString(KEY_PENDING_CSV, it) }
        // All three or none: a half-restored end would either write a CSV and
        // end nothing, or end an event whose CSV never reached the picker.
        val endCsv = pendingEndCsv
        val endId = pendingEndId
        val endName = pendingEndName
        if (endCsv != null && endId != null && endName != null) {
            outState.putString(KEY_PENDING_END_CSV, endCsv)
            outState.putLong(KEY_PENDING_END_ID, endId)
            outState.putString(KEY_PENDING_END_NAME, endName)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        beeper?.release()
        if (::db.isInitialized) db.close()
    }

    private fun onTap(tag: Tag) {
        if (tapBusy) return
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
            tapBusy = true
            promptIdentity(hash)
        }
    }

    /** An unknown badge asks who it belongs to. With an imported roster that is
     *  a search-and-pick from the names nobody has claimed yet; without one it
     *  is the same free-text prompt the app has always used. */
    private fun promptIdentity(hash: String) {
        val pool = db.rosterNames()
        if (pool.isEmpty()) promptTypedName(hash) else showRosterPicker(pool, hash)
    }

    private fun promptTypedName(hash: String) =
        prompt(getString(R.string.enroll_title), getString(R.string.enroll_message)) { name ->
            if (name.isNullOrBlank()) cancelEnroll() else bindIdentity(hash, name)
        }

    private fun showRosterPicker(pool: List<String>, hash: String) {
        val view = layoutInflater.inflate(R.layout.dialog_roster_picker, null)
        val search = view.findViewById<EditText>(R.id.pick_search)
        val list = view.findViewById<ListView>(R.id.pick_list)
        val shown = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(pool))
        list.adapter = shown

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pick_title)
            .setView(view)
            .setNeutralButton(R.string.pick_type_instead, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelEnroll() }
            .setOnCancelListener { cancelEnroll() }
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                shown.clear()
                shown.addAll(Roster.filterNames(pool, s?.toString().orEmpty()))
                shown.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val name = shown.getItem(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            bindIdentity(hash, name)
        }
        // Wired after show() so the escape hatch dismisses instead of the button
        // closing the dialog before the typed prompt is queued.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                promptTypedName(hash)
            }
        }
        dialog.show()
    }

    /** Binds a name to this badge, however it was chosen, and checks them in. */
    private fun bindIdentity(hash: String, rawName: String) {
        tapBusy = false
        val name = rawName.trim()
        if (name.isEmpty()) {
            status.text = getString(R.string.enrollment_cancelled)
            return
        }
        db.enroll(hash, name)
        // Claimed: this name is no longer waiting in the unmatched pool.
        db.removeRosterName(name)
        checkIn(hash, name)
    }

    private fun cancelEnroll() {
        tapBusy = false
        status.text = getString(R.string.enrollment_cancelled)
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

    private fun showSetupMenu() {
        val items = arrayOf(
            getString(R.string.setup_import_theme),
            getString(R.string.setup_import_logo),
            getString(R.string.setup_reset_theme),
            getString(R.string.setup_import_roster),
            getString(R.string.setup_delete_event))
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> themeLauncher.launch(arrayOf("*/*"))
                    1 -> logoLauncher.launch(arrayOf("image/*"))
                    2 -> confirmResetTheme()
                    3 -> rosterLauncher.launch(arrayOf("*/*"))
                    else -> confirmDeleteEvent()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Deleting an event lives in the Setup menu, behind the same ⋮ as the other
     *  operator-only actions, rather than on the toolbar: the toolbar is what
     *  door staff touch during an event, and a destructive action does not
     *  belong one mis-tap from Export. The Windows twin puts it on the toolbar
     *  next to New Event because that window has no Setup menu to hold it. */
    private fun confirmDeleteEvent() {
        val id = eventId
        val name = eventName
        if (id == null || name == null) {
            status.text = getString(R.string.delete_event_none)
            return
        }
        val count = db.attendanceCount(id)
        val message =
            if (count == 0) getString(R.string.delete_event_confirm_empty, name)
            else getString(
                R.string.delete_event_confirm, name,
                resources.getQuantityString(R.plurals.check_ins, count, count))
        // Taps are ignored while a destructive question is on screen, the same
        // guard the enroll prompt holds — a badge presented mid-confirmation
        // must not stack an enroll dialog on top of it.
        tapBusy = true
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_event_title)
            .setMessage(message)
            // Cancel is the only other way out: the button, back, or a tap
            // outside all leave the event exactly as it was.
            .setPositiveButton(R.string.delete_event_button) { _, _ ->
                db.deleteEvent(id)
                // Re-selects the newest remaining event, or asks for a new one
                // when this was the last.
                refreshEvents()
                status.text = getString(R.string.event_deleted, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { tapBusy = false }
            .show()
    }

    private fun confirmResetTheme() =
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_reset_theme)
            .setMessage(R.string.theme_reset_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Theme.reset(this)
                applyTheme()
                status.text = getString(R.string.theme_reset)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

    private fun importTheme(uri: Uri) {
        when (val r = read(uri, Theme.MAX_THEME_BYTES)) {
            is Read.TooLarge -> status.text = getString(R.string.theme_too_large)
            is Read.Failed -> status.text = getString(R.string.theme_unreadable)
            is Read.Ok -> {
                // Validate before writing: a file that is not JSON never becomes
                // the kiosk's theme, so the theme on disk is always loadable.
                val text = String(r.bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                val valid = try { JSONObject(text); true } catch (_: Exception) { false }
                if (!valid) {
                    status.text = getString(R.string.theme_invalid)
                    return
                }
                try {
                    Theme.writeThemeJson(this, text.toByteArray(Charsets.UTF_8))
                } catch (_: Exception) {
                    status.text = getString(R.string.theme_unreadable)
                    return
                }
                applyTheme()
                status.text = getString(R.string.theme_imported)
                AlertDialog.Builder(this)
                    .setTitle(R.string.setup_title)
                    .setMessage(R.string.theme_logo_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        logoLauncher.launch(arrayOf("image/*"))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun importLogo(uri: Uri) {
        when (val r = read(uri, Theme.MAX_LOGO_BYTES)) {
            is Read.TooLarge -> status.text = getString(R.string.logo_too_large)
            is Read.Failed -> status.text = getString(R.string.theme_unreadable)
            is Read.Ok -> {
                // Bounds-only decode proves it is an image, and proves it is one
                // the kiosk can actually draw, before a byte is written. A small
                // file can still decode to gigabytes.
                val bounds = Theme.logoBounds(r.bytes)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    status.text = getString(R.string.logo_invalid)
                    return
                }
                if (!Theme.isSafeLogoSize(bounds.outWidth, bounds.outHeight)) {
                    status.text = getString(
                        R.string.logo_dimensions, bounds.outWidth, bounds.outHeight)
                    return
                }
                try {
                    Theme.writeLogo(this, r.bytes)
                } catch (_: Exception) {
                    status.text = getString(R.string.logo_invalid)
                    return
                }
                applyTheme()
                status.text = getString(R.string.logo_imported)
            }
        }
    }

    private fun importRoster(uri: Uri) {
        val bytes = when (val r = read(uri, MAX_ROSTER_BYTES)) {
            is Read.TooLarge -> { status.text = getString(R.string.roster_too_large); return }
            is Read.Failed -> { status.text = getString(R.string.roster_unreadable); return }
            is Read.Ok -> r.bytes
        }
        val result = Roster.import(String(bytes, Charsets.UTF_8))
        if (result.error != null) {
            status.text = result.error
            AlertDialog.Builder(this)
                .setTitle(R.string.roster_title)
                .setMessage(result.error)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (result.entries.isEmpty()) {
            status.text = getString(R.string.roster_none)
            return
        }
        var mapped = 0
        var pooled = 0
        for (entry in result.entries) {
            // MAPPED: the credential number resolved to badge bytes, so this
            // person is enrolled now and their first tap just checks them in.
            val uid = entry.credential?.let { Roster.credentialToUidBytes(it) }
            if (uid != null) {
                db.enroll(db.hashUid(uid), entry.name)
                db.removeRosterName(entry.name)
                mapped++
            } else if (db.addRosterName(entry.name)) {
                // PICKER: name only, waiting to be claimed by a badge.
                pooled++
            }
        }
        val summary =
            getString(R.string.roster_imported, result.entries.size, mapped, pooled, result.skipped)
        status.text = summary
        AlertDialog.Builder(this)
            .setTitle(R.string.roster_title)
            .setMessage(summary + "\n\n" + getString(
                R.string.roster_columns,
                result.nameHeader ?: "?",
                result.credentialHeader ?: getString(R.string.roster_no_credential_column)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Reads a picked document with a hard ceiling: an oversized or unreadable
     *  file is refused with a status message, never allowed to take the kiosk
     *  down on its way in. */
    private fun read(uri: Uri, max: Int): Read = try {
        val input = contentResolver.openInputStream(uri)
        if (input == null) Read.Failed else input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return Read.TooLarge
            }
            Read.Ok(out.toByteArray())
        }
    } catch (_: Exception) {
        Read.Failed
    }

    private sealed interface Read {
        class Ok(val bytes: ByteArray) : Read
        object TooLarge : Read
        object Failed : Read
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
        eventSpinner.adapter = ThemedSpinner(events.map { it.second })
        if (events.isNotEmpty()) {
            eventSpinner.setSelection(0) // newest first, mirrors the Windows combo
        } else {
            // Nothing left to check anyone in to — first-launch state. The stale
            // selection is dropped BEFORE the prompt opens: a tap while it is up
            // must not record attendance against an event that no longer exists.
            eventId = null; eventName = null
            rows.clear()
            updateCount()
            newEvent()
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
        exportLauncher.launch(exportFileName(name))
    }

    /** Ending an event closes it for the books: export first, and only end if
     *  that export is actually written. An event that leaves the picker with no
     *  CSV off the device is the one outcome this must never produce, so a
     *  cancelled or failed save aborts the whole thing and changes nothing. */
    private fun confirmEndEvent() {
        val id = eventId
        val name = eventName
        if (id == null || name == null) {
            status.text = getString(R.string.end_event_none)
            return
        }
        val count = db.attendanceCount(id)
        tapBusy = true
        AlertDialog.Builder(this)
            .setTitle(R.string.end_event_title)
            .setMessage(getString(
                R.string.end_event_confirm, name,
                resources.getQuantityString(R.plurals.check_ins, count, count)))
            .setPositiveButton(R.string.end_event_button) { _, _ ->
                pendingEndCsv = db.buildCsv(id, name)
                pendingEndId = id
                pendingEndName = name
                endExportLauncher.launch(exportFileName(name))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { tapBusy = false }
            .show()
    }

    /** SAF uniquifies on collision — an earlier audit snapshot is never
     *  overwritten, whether it came from Export CSV or from ending an event. */
    private fun exportFileName(eventName: String): String {
        val safe = eventName.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "attendance_${safe}_$stamp.csv"
    }

    private fun updateCount() {
        headcount.text = rows.count.toString()
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

    /** Attendance rows in the theme's foreground color. */
    private inner class ThemedRows :
        ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            (v as? TextView)?.setTextColor(brand.foreground)
            return v
        }
    }

    /** The spinner's closed state sits on the themed background, so it takes the
     *  foreground color; the dropdown stays on the platform surface. */
    private inner class ThemedSpinner(names: List<String>) :
        ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, names) {
        init {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            (v as? TextView)?.setTextColor(brand.foreground)
            return v
        }
    }

    private companion object {
        const val KEY_PENDING_CSV = "pending_csv"
        const val KEY_PENDING_END_CSV = "pending_end_csv"
        const val KEY_PENDING_END_ID = "pending_end_id"
        const val KEY_PENDING_END_NAME = "pending_end_name"

        /** A personnel roster is a few thousand names; anything larger is not one. */
        const val MAX_ROSTER_BYTES = 5 * 1024 * 1024
    }
}
