package com.example.pathfinderindoor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EmergencyContact(
    val id: String,
    val name: String,
    val phone: String
)

class EmergencyActivity : AppCompatActivity() {

    private lateinit var etContactName: EditText
    private lateinit var etContactPhone: EditText
    private lateinit var btnSaveContact: Button
    private lateinit var btnCancelEdit: Button
    private lateinit var btnSOS: Button
    private lateinit var btnTestBattery: Button
    private lateinit var contactsContainer: LinearLayout
    private lateinit var tvEmptyContacts: TextView

    private val contacts = mutableListOf<EmergencyContact>()

    private val prefsName = "EmergencySettings"
    private val contactsKey = "emergency_contacts_json"

    private var editingContactId: String? = null
    private var pendingSOS = false
    private var sosInProgress = false

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var locationListener: LocationListener? = null
    private var locationTimeout: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (pendingSOS) {
                pendingSOS = false

                if (
                    results[Manifest.permission.SEND_SMS] == true ||
                    hasPermission(Manifest.permission.SEND_SMS)
                ) {
                    sendSOSNow()
                } else {
                    showToast(R.string.sms_permission_required)
                }
            } else {
                // Startup request for the background low-battery alert.
                if (hasPermission(Manifest.permission.SEND_SMS)) {
                    requestBatteryOptimizationExemption()
                } else {
                    showToast(R.string.sms_permission_required)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency)

        scheduleBatteryCheck()

        etContactName = findViewById(R.id.etContactName)
        etContactPhone = findViewById(R.id.etContactPhone)
        btnSaveContact = findViewById(R.id.btnSaveContact)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        btnSOS = findViewById(R.id.btnSOS)
        btnTestBattery = findViewById(R.id.btnTestBattery)
        contactsContainer = findViewById(R.id.contactsContainer)
        tvEmptyContacts = findViewById(R.id.tvEmptyContacts)

        locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        loadContacts()
        renderContacts()

        btnSaveContact.setOnClickListener {
            addOrUpdateContact()
        }

        btnCancelEdit.setOnClickListener {
            clearContactForm()
        }

        btnSOS.setOnClickListener {
            startSOS()
        }

        // Run the actual BatteryCheckWorker immediately.

        btnTestBattery.setOnClickListener {
            testBatteryAlert()
        }

        startBatteryMonitor()
        ensureSmsPermissionForBatteryAlert()
    }

    // Keeps watching the battery in the background so the SMS goes out as
    // soon as the level reaches 15% or below.
    private fun startBatteryMonitor() {
        try {
            BatteryMonitorService.start(this)
        } catch (e: Exception) {
            Log.e("SahayBattery", "Could not start battery monitor.", e)
        }
    }

    // The background low-battery alert needs SEND_SMS, which is a runtime
    // permission. Ask for it at startup instead of only when SOS is pressed.
    // Notifications are requested too so the monitoring notice is visible.
    private fun ensureSmsPermissionForBatteryAlert() {
        val missing = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            missing.add(Manifest.permission.SEND_SMS)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            requestBatteryOptimizationExemption()
        } else if (!pendingSOS) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Doze, Battery Saver and OEM battery managers can delay the periodic
    // battery check. Ask once to exempt the app so it can run near 15%.
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompted", false)) return

        prefs.edit().putBoolean("battery_opt_prompted", true).apply()

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Log.w("SahayBattery", "Could not open battery optimization settings.", e)
        }
    }

    private fun addOrUpdateContact() {
        val name = etContactName.text.toString().trim()
        val phone = etContactPhone.text.toString().trim()
        val digits = phone.filter { it.isDigit() }

        if (name.isBlank()) {
            etContactName.error = getString(R.string.enter_contact_name)
            return
        }

        if (!Patterns.PHONE.matcher(phone).matches() ||
            digits.length < 7
        ) {
            etContactPhone.error = getString(R.string.enter_valid_phone)
            return
        }

        val duplicate = contacts.any { contact ->
            contact.id != editingContactId &&
                    contact.phone.filter { it.isDigit() } == digits
        }

        if (duplicate) {
            etContactPhone.error = getString(R.string.duplicate_phone)
            return
        }

        if (editingContactId == null) {
            contacts.add(
                EmergencyContact(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    phone = phone
                )
            )
            showToast(R.string.contact_added)
        } else {
            val index = contacts.indexOfFirst {
                it.id == editingContactId
            }

            if (index != -1) {
                contacts[index] = contacts[index].copy(
                    name = name,
                    phone = phone
                )
                showToast(R.string.contact_updated)
            }
        }

        saveContacts()
        clearContactForm()
        renderContacts()
    }

    private fun editContact(contact: EmergencyContact) {
        editingContactId = contact.id
        etContactName.setText(contact.name)
        etContactPhone.setText(contact.phone)

        btnSaveContact.setText(R.string.update_contact)
        btnCancelEdit.visibility = View.VISIBLE
        etContactName.requestFocus()
    }

    private fun deleteContact(contact: EmergencyContact) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_contact_title)
            .setMessage(
                getString(
                    R.string.delete_contact_message,
                    contact.name,
                    contact.phone
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm_delete) { _, _ ->
                contacts.removeAll { it.id == contact.id }

                if (editingContactId == contact.id) {
                    clearContactForm()
                }

                saveContacts()
                renderContacts()
                showToast(R.string.contact_deleted)
            }
            .show()
    }

    private fun clearContactForm() {
        editingContactId = null
        etContactName.text.clear()
        etContactPhone.text.clear()
        etContactName.error = null
        etContactPhone.error = null

        btnSaveContact.setText(R.string.add_emergency_contact)
        btnCancelEdit.visibility = View.GONE
    }

    private fun renderContacts() {
        contactsContainer.removeAllViews()

        tvEmptyContacts.visibility =
            if (contacts.isEmpty()) View.VISIBLE else View.GONE

        contacts.forEach { contact ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFFF1F1F1.toInt())
            }

            val details = TextView(this).apply {
                text = getString(
                    R.string.contact_details,
                    contact.name,
                    contact.phone
                )
                textSize = 16f
                setPadding(4, 4, 4, 8)
            }

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val editButton = Button(this).apply {
                setText(R.string.edit)
                setOnClickListener { editContact(contact) }
            }

            val deleteButton = Button(this).apply {
                setText(R.string.delete)
                setOnClickListener { deleteContact(contact) }
            }

            buttons.addView(editButton)
            buttons.addView(deleteButton)
            card.addView(details)
            card.addView(buttons)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }

            contactsContainer.addView(card, params)
        }
    }

    private fun saveContacts() {
        val array = JSONArray()

        contacts.forEach { contact ->
            array.put(
                JSONObject().apply {
                    put("id", contact.id)
                    put("name", contact.name)
                    put("phone", contact.phone)
                }
            )
        }

        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(contactsKey, array.toString())
            .apply()
    }

    private fun loadContacts() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val saved = prefs.getString(contactsKey, null) ?: return

        try {
            val array = JSONArray(saved)

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("name")
                val phone = item.optString("phone")

                if (name.isNotBlank() && phone.isNotBlank()) {
                    contacts.add(
                        EmergencyContact(
                            id = item.optString(
                                "id",
                                UUID.randomUUID().toString()
                            ),
                            name = name,
                            phone = phone
                        )
                    )
                }
            }
        } catch (_: Exception) {
            showToast(R.string.contacts_read_error)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSOS() {
        if (sosInProgress) return

        if (contacts.isEmpty()) {
            showToast(R.string.no_contact_for_sos)
            return
        }

        val requiredPermissions = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            requiredPermissions.add(Manifest.permission.SEND_SMS)
        }

        if (
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (requiredPermissions.isNotEmpty()) {
            pendingSOS = true
            permissionLauncher.launch(requiredPermissions.toTypedArray())
            return
        }

        sendSOSNow()
    }

    private fun sendSOSNow() {
        if (sosInProgress || contacts.isEmpty()) return

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            showToast(R.string.sms_permission_required)
            return
        }

        sosInProgress = true
        btnSOS.isEnabled = false

        getCurrentLocation { location ->
            val message = if (location != null) {
                val mapsUrl = getString(
                    R.string.maps_url,
                    location.latitude.toString(),
                    location.longitude.toString()
                )
                getString(R.string.sos_message, mapsUrl)
            } else {
                getString(R.string.sos_message_no_location)
            }

            sendSosSms(message)
            sosInProgress = false
            btnSOS.isEnabled = true
        }
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        val hasLocationPermission =
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (!hasLocationPermission) {
            callback(null)
            return
        }

        try {
            val providers = locationManager.getProviders(true)

            val recentLocation = providers
                .mapNotNull { provider ->
                    try {
                        locationManager.getLastKnownLocation(provider)
                    } catch (_: SecurityException) {
                        null
                    }
                }
                .filter { isValidLocation(it) }
                .filter {
                    System.currentTimeMillis() - it.time in 0..120_000
                }
                .maxByOrNull { it.time }

            if (recentLocation != null) {
                callback(recentLocation)
                return
            }

            var completed = false

            fun finish(location: Location?) {
                if (completed) return
                completed = true
                stopLocationUpdates()
                callback(location)
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isValidLocation(location)) finish(location)
                }

                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?
                ) {}

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            locationListener = listener
            locationTimeout = Runnable { finish(null) }
            handler.postDelayed(locationTimeout!!, 20_000L)

            var requested = false

            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        1000L,
                        0f,
                        listener
                    )
                    requested = true
                } catch (_: SecurityException) {
                    // Try another provider.
                } catch (_: IllegalArgumentException) {
                    // Provider is unavailable.
                }
            }

            if (!requested) finish(null)
        } catch (_: SecurityException) {
            callback(null)
        } catch (_: Exception) {
            callback(null)
        }
    }

    private fun isValidLocation(location: Location): Boolean {
        val lat = location.latitude
        val lon = location.longitude

        return lat.isFinite() &&
                lon.isFinite() &&
                lat in -90.0..90.0 &&
                lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)
    }

    private fun stopLocationUpdates() {
        locationTimeout?.let { handler.removeCallbacks(it) }
        locationTimeout = null

        locationListener?.let { listener ->
            try {
                locationManager.removeUpdates(listener)
            } catch (_: SecurityException) {
                // Permission may have changed.
            } catch (_: Exception) {
                // Ignore cleanup errors.
            }
        }

        locationListener = null
    }

    private fun sendSosSms(message: String) {
        val recipients = contacts.toList()
        var submittedCount = 0

        try {
            val smsManager = getSystemService(SmsManager::class.java)

            if (smsManager == null) {
                showToast(R.string.sos_failed)
                return
            }

            recipients.forEach { contact ->
                try {
                    val parts = smsManager.divideMessage(message)

                    if (parts.size > 1) {
                        smsManager.sendMultipartTextMessage(
                            contact.phone,
                            null,
                            parts,
                            null,
                            null
                        )
                    } else {
                        smsManager.sendTextMessage(
                            contact.phone,
                            null,
                            message,
                            null,
                            null
                        )
                    }

                    submittedCount++
                } catch (_: Exception) {
                    // Continue attempting to send to other contacts.
                }
            }
        } catch (_: Exception) {
            showToast(R.string.sos_failed)
            return
        }

        when {
            submittedCount == recipients.size -> {
                showToast(getString(R.string.sos_sent_all, submittedCount))
            }

            submittedCount > 0 -> {
                showToast(
                    getString(
                        R.string.sos_sent_some,
                        submittedCount,
                        recipients.size
                    )
                )
            }

            else -> showToast(R.string.sos_failed)
        }
    }

    private fun testBatteryAlert() {
        if (contacts.isEmpty()) {
            showToast(R.string.no_contact_for_sos)
            return
        }

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            showToast(R.string.sms_permission_required)
            return
        }

        val batteryStatus = registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryStatus?.getIntExtra(
            android.os.BatteryManager.EXTRA_LEVEL, -1
        ) ?: -1

        val scale = batteryStatus?.getIntExtra(
            android.os.BatteryManager.EXTRA_SCALE, -1
        ) ?: -1

        val percent = if (level >= 0 && scale > 0) {
            level * 100 / scale
        } else {
            -1
        }

        Log.d("SahayBattery", "Actual battery percentage: $percent%")
        Log.d("SahayBattery", "Starting low-battery SMS test")

        BatteryMonitorReceiver().onReceive(
            this,
            Intent(BatteryMonitorReceiver.ACTION_TEST_LOW_BATTERY)
        )
    }

    private fun runBatteryCheckNow() {
        Log.d("SahayBattery", "Manual worker check requested")

        val request =
            OneTimeWorkRequestBuilder<BatteryCheckWorker>()
                .build()

        WorkManager.getInstance(applicationContext)
            .enqueue(request)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showToast(messageId: Int) {
        showToast(getString(messageId))
    }

    private fun scheduleBatteryCheck() {
        val request =
            PeriodicWorkRequestBuilder<BatteryCheckWorker>(
                15, TimeUnit.MINUTES
            ).build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "sahay_battery_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }
}