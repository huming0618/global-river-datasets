package com.huming.rivermap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var nearbyList: TextView
    private lateinit var hintText: TextView
    private var mapLibreMap: MapLibreMap? = null
    private var userLatLng: LatLng = DEFAULT_CENTER
    private var hasPreciseLocation: Boolean = false
    private var allRiverFeatures: JSONArray = JSONArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            requestLocation()
        } else {
            useDefaultChengdu(getString(R.string.location_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        nearbyList = findViewById(R.id.nearbyList)
        hintText = findViewById(R.id.hintText)
        findViewById<FloatingActionButton>(R.id.fabRecenter).setOnClickListener {
            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, NEAR_ZOOM))
            applySpatialFilter(userLatLng)
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = false
            map.setStyle(Style.Builder().fromUri("asset://style-dark.json")) { style ->
                loadRiversOntoStyle(style)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
                ensureLocationPermission()
            }
        }
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        nearbyList.text = getString(R.string.locating)
        val client = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    userLatLng = LatLng(loc.latitude, loc.longitude)
                    hasPreciseLocation = true
                    onUserLocationReady()
                } else {
                    client.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            userLatLng = LatLng(last.latitude, last.longitude)
                            hasPreciseLocation = true
                            onUserLocationReady()
                        } else {
                            useDefaultChengdu(getString(R.string.location_failed))
                        }
                    }.addOnFailureListener {
                        useDefaultChengdu(getString(R.string.location_failed))
                    }
                }
            }
            .addOnFailureListener {
                useDefaultChengdu(getString(R.string.location_failed))
            }
    }

    private fun useDefaultChengdu(message: String) {
        userLatLng = DEFAULT_CENTER
        hasPreciseLocation = false
        Snackbar.make(mapView, message, Snackbar.LENGTH_LONG).show()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        onUserLocationReady()
    }

    @SuppressLint("MissingPermission")
    private fun onUserLocationReady() {
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, NEAR_ZOOM))
        if (hasPreciseLocation) {
            try {
                val locationComponent = map.locationComponent
                locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(this, style).build()
                )
                locationComponent.isLocationComponentEnabled = true
                locationComponent.cameraMode = CameraMode.NONE
                locationComponent.renderMode = RenderMode.COMPASS
            } catch (_: Exception) {
                // Location component optional if Play Services unavailable
            }
        }
        applySpatialFilter(userLatLng)
        val lat = "%.4f".format(userLatLng.latitude)
        val lon = "%.4f".format(userLatLng.longitude)
        hintText.text = if (hasPreciseLocation) {
            "离线示意数据 · 当前位置 $lat, $lon · 附近 ${NEARBY_KM.toInt()} km"
        } else {
            "离线示意数据 · 默认成都 $lat, $lon · 附近 ${NEARBY_KM.toInt()} km"
        }
    }

    private fun loadRiversOntoStyle(style: Style) {
        val geojson = assets.open(RIVERS_ASSET).bufferedReader().use { it.readText() }
        val root = JSONObject(geojson)
        allRiverFeatures = root.getJSONArray("features")

        // All rivers (dim cyan)
        style.addSource(GeoJsonSource(SOURCE_ALL, geojson))
        style.addLayer(
            LineLayer(LAYER_ALL, SOURCE_ALL).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#26C6DA")),
                PropertyFactory.lineWidth(2.0f),
                PropertyFactory.lineOpacity(0.35f),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
            )
        )

        // Nearby highlight (electric cyan)
        style.addSource(GeoJsonSource(SOURCE_NEAR, emptyFeatureCollection()))
        style.addLayer(
            LineLayer(LAYER_NEAR, SOURCE_NEAR).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#00E5FF")),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineBlur(0.4f),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
            )
        )

        // Extra glow layer
        style.addLayerBelow(
            LineLayer(LAYER_NEAR_GLOW, SOURCE_NEAR).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#00B8D4")),
                PropertyFactory.lineWidth(10.0f),
                PropertyFactory.lineOpacity(0.25f),
                PropertyFactory.lineBlur(1.2f)
            ),
            LAYER_NEAR
        )
    }

    private fun applySpatialFilter(center: LatLng) {
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        val nearby = JSONArray()
        val ranked = mutableListOf<Pair<String, Double>>()

        for (i in 0 until allRiverFeatures.length()) {
            val feature = allRiverFeatures.getJSONObject(i)
            val name = feature.optJSONObject("properties")?.optString("name") ?: "未命名"
            val geom = feature.getJSONObject("geometry")
            val coords = geom.getJSONArray("coordinates")
            val minKm = minDistanceKmToLine(center, coords)
            if (minKm <= NEARBY_KM) {
                nearby.put(feature)
                ranked.add(name to minKm)
            }
        }

        val fc = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", nearby)
        (style.getSource(SOURCE_NEAR) as? GeoJsonSource)?.setGeoJson(fc.toString())

        ranked.sortBy { it.second }
        if (ranked.isEmpty()) {
            nearbyList.text = getString(R.string.no_nearby, NEARBY_KM.toInt())
        } else {
            nearbyList.text = ranked.take(8).joinToString("\n") { (name, km) ->
                "• $name  ·  ${"%.1f".format(km)} km"
            }
        }
    }

    private fun minDistanceKmToLine(center: LatLng, coords: JSONArray): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            val lon = c.getDouble(0)
            val lat = c.getDouble(1)
            val d = haversineKm(center.latitude, center.longitude, lat, lon)
            if (d < best) best = d
        }
        return best
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun emptyFeatureCollection(): String =
        """{"type":"FeatureCollection","features":[]}"""

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    companion object {
        private val DEFAULT_CENTER = LatLng(30.67, 104.06)
        private const val DEFAULT_ZOOM = 10.5
        private const val NEAR_ZOOM = 11.2
        private const val NEARBY_KM = 40.0
        private const val RIVERS_ASSET = "rivers-chengdu.geojson"
        private const val SOURCE_ALL = "rivers-all"
        private const val SOURCE_NEAR = "rivers-near"
        private const val LAYER_ALL = "rivers-all-line"
        private const val LAYER_NEAR = "rivers-near-line"
        private const val LAYER_NEAR_GLOW = "rivers-near-glow"
    }
}
