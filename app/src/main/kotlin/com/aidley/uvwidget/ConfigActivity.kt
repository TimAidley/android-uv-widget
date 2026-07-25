package com.aidley.uvwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * Settings for the widget, shown both when a widget is added and from the launcher icon.
 *
 * As well as letting the user choose a location, this screen exists to capture a location fix
 * while the app is in the foreground: from Android 10, background code cannot read the device's
 * location, so the widget's refresh relies on the fix cached here. That is why the screen goes
 * after a fix on arrival rather than waiting to be asked — being open is the whole opportunity.
 */
class ConfigActivity : ComponentActivity() {

    private val prefs by lazy { Prefs(this) }

    private lateinit var setLocationManually: CheckBox
    private lateinit var manualLocationFields: View
    private lateinit var latitudeField: EditText
    private lateinit var longitudeField: EditText
    private lateinit var status: TextView
    private lateinit var graph: UvGraphView

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** Where the in-flight name lookup was for, so a late result cannot overwrite a newer one. */
    private var pendingNameLookupFor: Coordinates? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            captureDeviceLocation()
        } else {
            // Setting this fires the listener, which writes its own status; say what actually
            // happened afterwards so the more specific message is the one left on screen.
            setLocationManually.isChecked = true
            status.setText(R.string.status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If the user backs out of adding a widget, the launcher must hear "cancelled".
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED, resultIntent())
        }

        setLocationManually = findViewById(R.id.set_location_manually)
        manualLocationFields = findViewById(R.id.manual_location_fields)
        latitudeField = findViewById(R.id.latitude)
        longitudeField = findViewById(R.id.longitude)
        status = findViewById(R.id.status)
        graph = findViewById(R.id.uv_graph)

        setLocationManually.isChecked = !prefs.useDeviceLocation
        showManualFields(setLocationManually.isChecked)
        prefs.manualLocation?.let {
            latitudeField.setText(format(it.latitude))
            longitudeField.setText(format(it.longitude))
        }

        setLocationManually.setOnCheckedChangeListener { _, manual ->
            showManualFields(manual)
            if (manual) status.setText(R.string.status_manual) else ensureLocationPermission()
        }

        findViewById<Button>(R.id.refresh_location).setOnClickListener { ensureLocationPermission() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }

        // The code, not just the name: it is what tells two builds of the same version apart.
        findViewById<TextView>(R.id.version).text =
            getString(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        // Go after a fix on arrival. In manual mode take one silently if the permission is
        // already granted — it keeps the cache warm — but never prompt for it there.
        when {
            !setLocationManually.isChecked -> ensureLocationPermission()
            LocationSource.hasLocationPermission(this) -> captureDeviceLocation()
            else -> showCurrentLocation()
        }
        refreshGraph()
    }

    /** The graph needs both a forecast and a place; without either there is nothing to draw. */
    private fun refreshGraph() {
        val location = prefs.effectiveLocation
        val forecast = prefs.forecast?.takeUnless { it.isExpired(System.currentTimeMillis()) }
        graph.setForecast(forecast, location)
        graph.visibility = if (forecast != null && location != null) View.VISIBLE else View.GONE
    }

    private fun ensureLocationPermission() {
        if (LocationSource.hasLocationPermission(this)) {
            captureDeviceLocation()
        } else {
            // Coarse alone: asking for fine as well would put a Precise/Approximate choice in the
            // dialog, and this app has no use for the precise answer. See the manifest.
            requestPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun captureDeviceLocation() {
        // Show the fix the system already holds straight away. Asking for a fresh one takes
        // seconds, and for a forecast covering tens of kilometres the old one is almost always
        // the same answer — so there is no reason to make the user watch a spinner for it.
        val alreadyKnown = LocationSource.lastKnownLocation(this)
        if (alreadyKnown == null) {
            status.setText(R.string.status_locating)
        } else {
            prefs.cachedDeviceLocation = alreadyKnown
            showCurrentLocation()
        }

        LocationSource.requestSingleUpdate(this) { coordinates ->
            if (isFinishing || isDestroyed) return@requestSingleUpdate
            when {
                coordinates != null -> {
                    prefs.cachedDeviceLocation = coordinates
                    showCurrentLocation()
                }
                // Only a genuine dead end is worth reporting: if something was already on
                // screen, leaving it there beats replacing it with a failure notice.
                alreadyKnown == null -> status.setText(R.string.status_no_fix)
            }
        }
    }

    private fun showCurrentLocation() {
        val location = prefs.effectiveLocation
        if (location == null) {
            pendingNameLookupFor = null
            status.setText(R.string.status_no_location)
            return
        }

        // Coordinates first, name second: the name may never arrive, and showing nothing while
        // waiting on a lookup that nothing depends on would be the wrong trade.
        status.text =
            getString(R.string.status_using, format(location.latitude), format(location.longitude))
        lookUpPlaceName(location)
        refreshGraph()
    }

    private fun lookUpPlaceName(location: Coordinates) {
        pendingNameLookupFor = location
        PlaceName.lookup(this, location) { name ->
            if (isFinishing || isDestroyed) return@lookup
            if (name == null || pendingNameLookupFor != location) return@lookup
            status.text = getString(
                R.string.status_using_named,
                name,
                format(location.latitude),
                format(location.longitude)
            )
        }
    }

    private fun save() {
        val manual = setLocationManually.isChecked
        prefs.useDeviceLocation = !manual

        // Only read the coordinate fields when they are on screen. Left-over text in hidden
        // fields must never be able to refuse a save for a reason the user cannot see.
        if (manual) {
            val entered = parseManualLocation()
            if (entered == null) {
                refuse(
                    if (hasManualInput()) R.string.status_invalid_coordinates
                    else R.string.status_no_location
                )
                return
            }
            prefs.manualLocation = entered
        }

        if (prefs.effectiveLocation == null) {
            refuse(R.string.status_no_location)
            return
        }

        // Force a fetch for the new location rather than waiting for the next scheduled run.
        prefs.forecast = null
        UvWidgetRenderer.renderAll(this)
        UvRefreshWorker.schedulePeriodicRefresh(this)
        UvRefreshWorker.refreshNow(this)

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_OK, resultIntent())
        }
        finish()
    }

    /**
     * Refuse to save, and say so where the user is looking. The status line sits above the
     * coordinate fields, so on a short screen it can be scrolled out of sight by the time the
     * Save button is on screen — a silent refusal there reads as the button doing nothing.
     */
    private fun refuse(messageId: Int) {
        status.setText(messageId)
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show()
    }

    private fun showManualFields(visible: Boolean) {
        manualLocationFields.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun hasManualInput(): Boolean =
        latitudeField.text.isNotBlank() || longitudeField.text.isNotBlank()

    private fun parseManualLocation(): Coordinates? {
        val latitude = latitudeField.text.toString().trim().toDoubleOrNull() ?: return null
        val longitude = longitudeField.text.toString().trim().toDoubleOrNull() ?: return null
        return Coordinates(latitude, longitude).takeIf { it.isValid }
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun format(value: Double) = String.format(Locale.US, "%.4f", value)
}
