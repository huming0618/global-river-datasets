package com.huming.rivermap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var nearbyList: TextView
    private lateinit var hintText: TextView
    private var mapLibreMap: MapLibreMap? = null
    private var userLatLng: LatLng = DEFAULT_CENTER
    private var hasPreciseLocation: Boolean = false
    private var hydroFeatures: JSONArray = JSONArray()
    private var osmFeatures: JSONArray = JSONArray()
    private var riversReady: Boolean = false

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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
            applySpatialFilterAsync(userLatLng)
        }

        mapView.onCreate(savedInstanceState)
        nearbyList.text = getString(R.string.loading_rivers)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = false
            ioExecutor.execute {
                val mbtiles = ensureMbtilesOnDisk()
                val styleJson = assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
                    .replace("__MBTILES_URI__", mbtilesUri(mbtiles))
                mainHandler.post {
                    map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
                        loadRiverLayersAsync(style)
                        ensureLocationPermission()
                    }
                }
            }
        }
    }

    private fun mbtilesUri(file: File): String {
        // MapLibre expects mbtiles:// + absolute path (three slashes for Unix abs paths).
        return "mbtiles://" + file.absolutePath
    }

    private fun ensureMbtilesOnDisk(): File {
        val dest = File(filesDir, MBTILES_FILE)
        if (dest.exists() && dest.length() > 1_000_000L) {
            return dest
        }
        assets.open(MBTILES_FILE).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, bufferSize = 1024 * 256)
            }
        }
        return dest
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
        if (!riversReady) {
            nearbyList.text = getString(R.string.loading_rivers)
        }
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
        applySpatialFilterAsync(userLatLng)
        updateHint()
    }

    private fun updateHint() {
        val lat = "%.4f".format(userLatLng.latitude)
        val lon = "%.4f".format(userLatLng.longitude)
        hintText.text = if (hasPreciseLocation) {
            "四川离线河网 · 当前位置 $lat, $lon · 附近 ${NEARBY_KM.toInt()} km"
        } else {
            "四川离线河网 · 默认成都 $lat, $lon · 附近 ${NEARBY_KM.toInt()} km"
        }
    }

    private fun loadRiverLayersAsync(style: Style) {
        ioExecutor.execute {
            try {
                val hydroJson = readAssetText(HYDRO_ASSET)
                val osmJson = readAssetText(OSM_ASSET)
                val hydroRoot = JSONObject(hydroJson)
                val osmRoot = JSONObject(osmJson)
                hydroFeatures = hydroRoot.getJSONArray("features")
                osmFeatures = osmRoot.getJSONArray("features")
                mainHandler.post {
                    if (isDestroyed) return@post
                    val liveStyle = mapLibreMap?.style ?: style
                    // OSM supplement (thinner / teal)
                    liveStyle.addSource(GeoJsonSource(SOURCE_OSM, osmJson))
                    liveStyle.addLayer(
                        LineLayer(LAYER_OSM, SOURCE_OSM).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#4DD0E1")),
                            PropertyFactory.lineWidth(1.4f),
                            PropertyFactory.lineOpacity(0.55f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    // HydroRIVERS primary (cyan)
                    liveStyle.addSource(GeoJsonSource(SOURCE_HYDRO, hydroJson))
                    liveStyle.addLayer(
                        LineLayer(LAYER_HYDRO, SOURCE_HYDRO).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#26C6DA")),
                            PropertyFactory.lineWidth(2.2f),
                            PropertyFactory.lineOpacity(0.55f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    liveStyle.addSource(GeoJsonSource(SOURCE_NEAR, emptyFeatureCollection()))
                    liveStyle.addLayer(
                        LineLayer(LAYER_NEAR, SOURCE_NEAR).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#00E5FF")),
                            PropertyFactory.lineWidth(4.5f),
                            PropertyFactory.lineOpacity(0.95f),
                            PropertyFactory.lineBlur(0.4f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    liveStyle.addLayerBelow(
                        LineLayer(LAYER_NEAR_GLOW, SOURCE_NEAR).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#00B8D4")),
                            PropertyFactory.lineWidth(10.0f),
                            PropertyFactory.lineOpacity(0.25f),
                            PropertyFactory.lineBlur(1.2f)
                        ),
                        LAYER_NEAR
                    )
                    riversReady = true
                    applySpatialFilterAsync(userLatLng)
                    updateHint()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    nearbyList.text = getString(R.string.rivers_load_failed)
                    Toast.makeText(this, e.message ?: "load failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readAssetText(name: String): String {
        // AGP decompresses *.geojson.gz into assets/*.geojson at merge time.
        assets.open(name).bufferedReader().use { return it.readText() }
    }

    private fun applySpatialFilterAsync(center: LatLng) {
        if (!riversReady) {
            nearbyList.text = getString(R.string.loading_rivers)
            return
        }
        nearbyList.text = getString(R.string.filtering_rivers)
        ioExecutor.execute {
            val padDeg = NEARBY_KM / 111.0
            val minLat = center.latitude - padDeg
            val maxLat = center.latitude + padDeg
            val cosLat = cos(Math.toRadians(center.latitude)).coerceAtLeast(0.2)
            val minLon = center.longitude - padDeg / cosLat
            val maxLon = center.longitude + padDeg / cosLat

            val nearby = JSONArray()
            val ranked = mutableListOf<Pair<String, Double>>()

            fun consider(feature: JSONArray, index: Int, sourceLabel: String) {
                val featureObj = feature.getJSONObject(index)
                val geom = featureObj.getJSONObject("geometry")
                val coords = geom.getJSONArray("coordinates")
                val type = geom.optString("type")
                if (!bboxIntersects(type, coords, minLon, minLat, maxLon, maxLat)) return
                val minKm = minDistanceKm(center, type, coords)
                if (minKm <= NEARBY_KM) {
                    nearby.put(featureObj)
                    val props = featureObj.optJSONObject("properties")
                    var name = props?.optString("name").orEmpty()
                    if (name.isBlank()) {
                        val ord = props?.optInt("ORD_STRA", 0) ?: 0
                        val ww = props?.optString("waterway").orEmpty()
                        name = when {
                            ord > 0 -> "HydroRIVERS 河段(序$ord)"
                            ww.isNotBlank() -> "OSM $ww"
                            else -> sourceLabel
                        }
                    }
                    ranked.add(name to minKm)
                }
            }

            for (i in 0 until hydroFeatures.length()) {
                consider(hydroFeatures, i, "HydroRIVERS")
            }
            for (i in 0 until osmFeatures.length()) {
                consider(osmFeatures, i, "OSM")
            }

            ranked.sortBy { it.second }
            // Deduplicate display names keeping closest
            val seen = LinkedHashSet<String>()
            val display = mutableListOf<Pair<String, Double>>()
            for (item in ranked) {
                if (seen.add(item.first)) display.add(item)
                if (display.size >= 10) break
            }

            val fc = JSONObject()
                .put("type", "FeatureCollection")
                .put("features", nearby)
            val fcStr = fc.toString()
            mainHandler.post {
                if (isDestroyed) return@post
                val style = mapLibreMap?.style ?: return@post
                (style.getSource(SOURCE_NEAR) as? GeoJsonSource)?.setGeoJson(fcStr)
                if (display.isEmpty()) {
                    nearbyList.text = getString(R.string.no_nearby, NEARBY_KM.toInt())
                } else {
                    nearbyList.text = display.joinToString("\n") { (name, km) ->
                        "• $name  ·  ${"%.1f".format(km)} km"
                    }
                }
            }
        }
    }

    private fun bboxIntersects(
        type: String,
        coords: JSONArray,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double
    ): Boolean {
        var fMinLon = Double.POSITIVE_INFINITY
        var fMinLat = Double.POSITIVE_INFINITY
        var fMaxLon = Double.NEGATIVE_INFINITY
        var fMaxLat = Double.NEGATIVE_INFINITY
        fun absorbPoint(c: JSONArray) {
            val lon = c.getDouble(0)
            val lat = c.getDouble(1)
            fMinLon = min(fMinLon, lon)
            fMaxLon = max(fMaxLon, lon)
            fMinLat = min(fMinLat, lat)
            fMaxLat = max(fMaxLat, lat)
        }
        fun absorbLine(line: JSONArray) {
            for (i in 0 until line.length()) absorbPoint(line.getJSONArray(i))
        }
        when (type) {
            "LineString" -> absorbLine(coords)
            "MultiLineString" -> {
                for (i in 0 until coords.length()) absorbLine(coords.getJSONArray(i))
            }
            else -> return true
        }
        return !(fMaxLon < minLon || fMinLon > maxLon || fMaxLat < minLat || fMinLat > maxLat)
    }

    private fun minDistanceKm(center: LatLng, type: String, coords: JSONArray): Double {
        var best = Double.MAX_VALUE
        fun considerPoint(c: JSONArray) {
            val d = haversineKm(center.latitude, center.longitude, c.getDouble(1), c.getDouble(0))
            if (d < best) best = d
        }
        fun considerLine(line: JSONArray) {
            // Sample vertices; for short segments this approximates closest approach well enough.
            val step = when {
                line.length() > 40 -> 3
                line.length() > 20 -> 2
                else -> 1
            }
            var i = 0
            while (i < line.length()) {
                considerPoint(line.getJSONArray(i))
                i += step
            }
            if (line.length() > 0) considerPoint(line.getJSONArray(line.length() - 1))
        }
        when (type) {
            "LineString" -> considerLine(coords)
            "MultiLineString" -> {
                for (i in 0 until coords.length()) considerLine(coords.getJSONArray(i))
            }
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
        ioExecutor.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    companion object {
        private val DEFAULT_CENTER = LatLng(30.67, 104.06)
        private const val DEFAULT_ZOOM = 8.5
        private const val NEAR_ZOOM = 10.5
        private const val NEARBY_KM = 40.0
        private const val STYLE_ASSET = "style-dark.json"
        private const val MBTILES_FILE = "sichuan-basemap.mbtiles"
        private const val HYDRO_ASSET = "hydrorivers_sichuan.geojson"
        private const val OSM_ASSET = "osm_waterways_sichuan.geojson"
        private const val SOURCE_HYDRO = "rivers-hydro"
        private const val SOURCE_OSM = "rivers-osm"
        private const val SOURCE_NEAR = "rivers-near"
        private const val LAYER_HYDRO = "rivers-hydro-line"
        private const val LAYER_OSM = "rivers-osm-line"
        private const val LAYER_NEAR = "rivers-near-line"
        private const val LAYER_NEAR_GLOW = "rivers-near-glow"
    }
}
