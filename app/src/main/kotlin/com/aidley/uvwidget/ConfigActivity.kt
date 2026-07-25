package com.aidley.uvwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * Settings for the widget, shown both when a widget is added and from the launcher icon.
 *
 * As well as letting the user choose a location, this screen exists to capture a location fix
 * while the app is in the foreground: from Android 10, background code cannot read the device's
 * location, so the widget's refresh relies on the fix cached here.
 */
class ConfigActivity : ComponentActivity() {

    private val prefs by lazy { Prefs(this) }

    private lateinit var useDeviceLocation: CheckBox
    private lateinit var latitudeField: EditText
    private lateinit var longitudeField: EditText
    private lateinit var status: TextView

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            captureDeviceLocation()
        } else {
            useDeviceLocation.isChecked = false
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

        useDeviceLocation = findViewById(R.id.use_device_location)
        latitudeField = findViewById(R.id.latitude)
        longitudeField = findViewById(R.id.longitude)
        status = findViewById(R.id.status)

        useDeviceLocation.isChecked = prefs.useDeviceLocation
        prefs.manualLocation?.let {
            latitudeField.setText(format(it.latitude))
            longitudeField.setText(format(it.longitude))
        }

        useDeviceLocation.setOnCheckedChangeListener { _, checked ->
            if (checked) ensureLocationPermission() else status.setText(R.string.status_manual)
        }

        findViewById<Button>(R.id.refresh_location).setOnClickListener { ensureLocationPermission() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }

        if (useDeviceLocation.isChecked && LocationSource.hasLocationPermission(this)) {
            captureDeviceLocation()
        } else {
            showCurrentLocation()
        }
    }

    private fun ensureLocationPermission() {
        if (LocationSource.hasLocationPermission(this)) {
            captureDeviceLocation()
        } else {
            requestPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    private fun captureDeviceLocation() {
        status.setText(R.string.status_locating)
        LocationSource.requestSingleUpdate(this) { coordinates ->
            if (isFinishing || isDestroyed) return@requestSingleUpdate
            if (coordinates == null) {
                status.setText(R.string.status_no_fix)
            } else {
                prefs.cachedDeviceLocation = coordinates
                showCurrentLocation()
            }
        }
    }

    private fun showCurrentLocation() {
        val location = prefs.effectiveLocation
        status.text = if (location == null) {
            getString(R.string.status_no_location)
        } else {
            getString(R.string.status_using, format(location.latitude), format(location.longitude))
        }
    }

    private fun save() {
        prefs.useDeviceLocation = useDeviceLocation.isChecked

        val manual = parseManualLocation()
        if (manual == null && hasManualInput()) {
            status.setText(R.string.status_invalid_coordinates)
            return
        }
        prefs.manualLocation = manual

        if (prefs.effectiveLocation == null) {
            status.setText(R.string.status_no_location)
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
