package com.example.pathfinderindoor.indoor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

/**
 * Manages GPS status, location updates, and Outdoor navigation flow via Google Maps.
 */
class OutdoorLocationManager(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val settingsClient = LocationServices.getSettingsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun isGpsProviderEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    fun checkLocationSettings(
        activity: Activity?,
        requestCode: Int,
        onReady: () -> Unit,
        onResolvable: () -> Unit,
        onError: (String) -> Unit
    ) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        settingsClient.checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                mainHandler.post { onReady() }
            }
            .addOnFailureListener { exception ->
                mainHandler.post {
                    if (exception is ResolvableApiException && activity != null) {
                        try {
                            exception.startResolutionForResult(activity, requestCode)
                            onResolvable()
                        } catch (e: Exception) {
                            onError("Could not prompt for location settings.")
                        }
                    } else {
                        onError("Location is disabled on this device. Please turn on GPS.")
                    }
                }
            }
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation(
        onLocationReceived: (Location?) -> Unit
    ) {
        if (!hasLocationPermission()) {
            onLocationReceived(null)
            return
        }

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        mainHandler.post { onLocationReceived(location) }
                    } else {
                        // Request a single high-accuracy update if lastLocation is null
                        val singleRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                            .setMaxUpdates(1)
                            .build()

                        fusedLocationClient.requestLocationUpdates(
                            singleRequest,
                            object : LocationCallback() {
                                override fun onLocationResult(result: LocationResult) {
                                    fusedLocationClient.removeLocationUpdates(this)
                                    mainHandler.post {
                                        onLocationReceived(result.lastLocation)
                                    }
                                }
                            },
                            Looper.getMainLooper()
                        )
                    }
                }
                .addOnFailureListener {
                    mainHandler.post { onLocationReceived(null) }
                }
        } catch (e: Exception) {
            mainHandler.post { onLocationReceived(null) }
        }
    }

    /**
     * Launches walking navigation to the destination using Google Maps.
     */
    fun startWalkingNavigation(destination: String): Boolean {
        val encodedDestination = Uri.encode(destination.trim())

        // Google Maps walking navigation uri: mode=w is WALKING
        val googleMapsUri = Uri.parse("google.navigation:q=$encodedDestination&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, googleMapsUri).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(mapIntent)
            return true
        } catch (e: Exception) {
            // Fallback to web directions in browser
            val webUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=walking&dir_action=navigate"
            )
            val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return try {
                context.startActivity(webIntent)
                true
            } catch (ex: Exception) {
                Toast.makeText(context, "Google Maps could not be opened.", Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}
