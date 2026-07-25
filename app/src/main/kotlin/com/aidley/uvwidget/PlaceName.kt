package com.aidley.uvwidget

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Turns coordinates into a human-readable place name, for display only.
 *
 * Every caller must cope with a null name: reverse geocoding needs a backend service that not
 * every Android build ships, and it fails offline. The widget never depends on the result — it is
 * there so the settings screen can say "Bath" rather than only "51.3811, -2.3590".
 */
object PlaceName {

    private const val TAG = "PlaceName"

    /** [onResult] runs on the main thread, exactly once, with null when no name could be found. */
    fun lookup(context: Context, coordinates: Coordinates, onResult: (String?) -> Unit) {
        if (!Geocoder.isPresent()) {
            onResult(null)
            return
        }

        val geocoder = Geocoder(context, Locale.getDefault())
        val main = Handler(Looper.getMainLooper())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(
                coordinates.latitude,
                coordinates.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        val name = addresses.firstOrNull()?.let(::describe)
                        main.post { onResult(name) }
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "Reverse geocoding failed: $errorMessage")
                        main.post { onResult(null) }
                    }
                }
            )
        } else {
            // Blocking before API 33, so it must not run on the main thread. A bare thread is
            // enough for a one-shot cosmetic lookup that nothing waits on.
            Thread {
                val name = runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(coordinates.latitude, coordinates.longitude, 1)
                }.onFailure { Log.w(TAG, "Reverse geocoding failed", it) }
                    .getOrNull()
                    ?.firstOrNull()
                    ?.let(::describe)
                main.post { onResult(name) }
            }.start()
        }
    }

    /** The most specific name that is still recognisable: a town is more use than a county. */
    private fun describe(address: Address): String? = address.locality
        ?: address.subLocality
        ?: address.subAdminArea
        ?: address.adminArea
        ?: address.countryName
}
