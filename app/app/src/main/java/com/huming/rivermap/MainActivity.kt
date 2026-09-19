package com.huming.rivermap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
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
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    data class RiverItem(
        val key: String,
        val displayName: String,
        val distanceKm: Double,
        val bearingDeg: Double,
        /** All matching segments in the current cone/nearby set (for amber highlight). */
        val features: List<JSONObject>,
        val nearestLat: Double,
        val nearestLon: Double,
        val segmentCount: Int
    )

    private lateinit var mapView: MapView
    private lateinit var nearbyList: TextView
    private lateinit var hintText: TextView
    private lateinit var headingText: TextView
    private lateinit var riverListView: ListView
    private lateinit var btnClearSelection: Button
    private var mapLibreMap: MapLibreMap? = null
    private var userLatLng: LatLng = DEFAULT_CENTER
    private var hasPreciseLocation: Boolean = false
    private var hydroFeatures: JSONArray = JSONArray()
    private var osmFeatures: JSONArray = JSONArray()
    private var riversReady: Boolean = false

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val filterInFlight = AtomicBoolean(false)
    private var pendingFilter = false

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var orientationSensor: Sensor? = null
    private var hasCompass: Boolean = false
    private var currentHeadingDeg: Float? = null
    private var lastPublishedHeading: Float? = null
    /** True only while user is pressing-and-holding the forward-cone button. */
    private var headingHoldActive: Boolean = false
    private var lastFilterElapsedMs: Long = 0L
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val riverItems = mutableListOf<RiverItem>()
    private var selectedKey: String? = null
    private lateinit var riverAdapter: RiverListAdapter

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
        headingText = findViewById(R.id.headingText)
        riverListView = findViewById(R.id.riverListView)
        btnClearSelection = findViewById(R.id.btnClearSelection)

        riverAdapter = RiverListAdapter()
        riverListView.adapter = riverAdapter
        riverListView.setOnItemClickListener { _, _, position, _ ->
            if (position in riverItems.indices) {
                selectRiver(riverItems[position])
            }
        }
        btnClearSelection.setOnClickListener { clearSelection() }

        findViewById<FloatingActionButton>(R.id.fabRecenter).setOnClickListener {
            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, NEAR_ZOOM))
            scheduleViewUpdate(force = true)
        }

        initCompass()
        setupHoldForwardButton()

        mapView.onCreate(savedInstanceState)
        nearbyList.text = getString(R.string.loading_rivers)
        riverAdapter.setStatus(getString(R.string.loading_rivers))
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = false
            map.addOnCameraIdleListener {
                // Region mode: on idle (not every frame) refresh near-layer + list from viewport
                scheduleViewUpdate(force = false)
            }
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

    private fun initCompass() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sm = sensorManager ?: return
        rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        orientationSensor = sm.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        hasCompass = rotationSensor != null || orientationSensor != null
        if (!hasCompass) {
            headingText.text = getString(R.string.heading_unknown)
            Snackbar.make(mapView, getString(R.string.compass_unavailable), Snackbar.LENGTH_LONG).show()
        } else {
            headingText.text = getString(R.string.heading_released)
        }
    }

    private fun mbtilesUri(file: File): String {
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
            riverAdapter.setStatus(getString(R.string.loading_rivers))
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
        scheduleViewUpdate(force = true)
        updateHint()
        updateHeadingUi()
    }

    private fun updateHint() {
        val lat = "%.4f".format(userLatLng.latitude)
        val lon = "%.4f".format(userLatLng.longitude)
        val mode = if (headingHoldActive) {
            "可视区域 + 按住前方锥形"
        } else {
            "可视区域（松开 · 无朝向锥形）"
        }
        hintText.text = if (hasPreciseLocation) {
            "四川离线河网 · 当前位置 $lat, $lon · $mode"
        } else {
            "四川离线河网 · 默认成都 $lat, $lon · $mode"
        }
    }

    private fun updateHeadingUi() {
        if (!hasCompass) {
            headingText.text = getString(R.string.heading_unknown)
            return
        }
        if (!headingHoldActive) {
            headingText.text = getString(R.string.heading_released)
            return
        }
        val h = currentHeadingDeg
        if (h == null) {
            headingText.text = getString(R.string.heading_holding_wait)
        } else {
            headingText.text = getString(
                R.string.heading_fmt,
                h,
                cardinalLabel(h),
                CONE_HALF_DEG.toInt()
            )
        }
    }

    private fun setupHoldForwardButton() {
        val btn = findViewById<Button>(R.id.btnHoldForward)
        if (!hasCompass) {
            btn.isEnabled = false
            btn.alpha = 0.45f
            btn.text = getString(R.string.hold_forward_unavailable)
            return
        }
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onHeadingHoldStarted()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    onHeadingHoldEnded()
                    true
                }
                else -> false
            }
        }
    }

    private fun onHeadingHoldStarted() {
        if (headingHoldActive) return
        headingHoldActive = true
        lastPublishedHeading = null
        updateHint()
        updateHeadingUi()
        registerSensors()
        scheduleViewUpdate(force = true)
    }

    private fun onHeadingHoldEnded() {
        if (!headingHoldActive) return
        headingHoldActive = false
        unregisterSensors()
        lastPublishedHeading = null
        updateHint()
        updateHeadingUi()
        // Freeze: drop cone; list = viewport region only (no heading filter)
        scheduleViewUpdate(force = true)
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
                    liveStyle.addSource(GeoJsonSource(SOURCE_OSM, osmJson))
                    liveStyle.addLayer(
                        LineLayer(LAYER_OSM, SOURCE_OSM).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#4DD0E1")),
                            PropertyFactory.lineWidth(Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 0.8f),
                            Expression.stop(10, 1.2f),
                            Expression.stop(14, 1.8f)
                        )),
                            PropertyFactory.lineOpacity(0.55f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    liveStyle.addSource(GeoJsonSource(SOURCE_HYDRO, hydroJson))
                    liveStyle.addLayer(
                        LineLayer(LAYER_HYDRO, SOURCE_HYDRO).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#26C6DA")),
                            PropertyFactory.lineWidth(Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 1.0f),
                            Expression.stop(10, 1.6f),
                            Expression.stop(14, 2.2f)
                        )),
                            PropertyFactory.lineOpacity(0.55f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    liveStyle.addSource(GeoJsonSource(SOURCE_NEAR, emptyFeatureCollection()))
                    liveStyle.addLayer(
                        LineLayer(LAYER_NEAR, SOURCE_NEAR).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#00E5FF")),
                            PropertyFactory.lineWidth(Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 1.4f),
                            Expression.stop(10, 2.2f),
                            Expression.stop(14, 2.8f)
                        )),
                            PropertyFactory.lineOpacity(0.9f),
                            PropertyFactory.lineBlur(0.15f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    liveStyle.addLayerBelow(
                        LineLayer(LAYER_NEAR_GLOW, SOURCE_NEAR).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#00B8D4")),
                            PropertyFactory.lineWidth(Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 2.2f),
                            Expression.stop(10, 3.4f),
                            Expression.stop(14, 4.2f)
                        )),
                            PropertyFactory.lineOpacity(0.18f),
                            PropertyFactory.lineBlur(0.5f)
                        ),
                        LAYER_NEAR
                    )
                    // Selected river highlight (amber — distinct from cyan/teal)
                    liveStyle.addSource(GeoJsonSource(SOURCE_SELECTED, emptyFeatureCollection()))
                    liveStyle.addLayer(
                        LineLayer(LAYER_SELECTED, SOURCE_SELECTED).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#FFB300")),
                            PropertyFactory.lineWidth(Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 1.8f),
                            Expression.stop(10, 2.8f),
                            Expression.stop(14, 3.4f)
                        )),
                            PropertyFactory.lineOpacity(1.0f),
                            PropertyFactory.lineBlur(0.1f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    liveStyle.addLayerBelow(
                        LineLayer(LAYER_SELECTED_GLOW, SOURCE_SELECTED).withProperties(
                            PropertyFactory.lineColor(Color.parseColor("#FF6D00")),
                            PropertyFactory.lineWidth(Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 2.8f),
                            Expression.stop(10, 4.0f),
                            Expression.stop(14, 5.0f)
                        )),
                            PropertyFactory.lineOpacity(0.22f),
                            PropertyFactory.lineBlur(0.6f)
                        ),
                        LAYER_SELECTED
                    )
                    riversReady = true
                    scheduleViewUpdate(force = true)
                    updateHint()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    riverAdapter.setStatus(getString(R.string.rivers_load_failed))
                    Toast.makeText(this, e.message ?: "load failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readAssetText(name: String): String {
        assets.open(name).bufferedReader().use { return it.readText() }
    }

    private fun scheduleViewUpdate(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFilterElapsedMs < FILTER_THROTTLE_MS) {
            pendingFilter = true
            mainHandler.removeCallbacks(delayedFilterRunnable)
            mainHandler.postDelayed(delayedFilterRunnable, FILTER_THROTTLE_MS - (now - lastFilterElapsedMs))
            return
        }
        pendingFilter = false
        lastFilterElapsedMs = now
        applyForwardConeFilterAsync()
    }

    private val delayedFilterRunnable = Runnable {
        if (pendingFilter || true) {
            pendingFilter = false
            lastFilterElapsedMs = SystemClock.elapsedRealtime()
            applyForwardConeFilterAsync()
        }
    }

    private fun currentViewportBounds(): LatLngBounds? {
        return try {
            mapLibreMap?.projection?.visibleRegion?.latLngBounds
        } catch (_: Exception) {
            null
        }
    }

    /** Fallback bbox around GPS when viewport is not yet available. */
    private fun fallbackRadiusBounds(center: LatLng): LatLngBounds {
        val padDeg = NEARBY_KM / 111.0
        val cosLat = cos(Math.toRadians(center.latitude)).coerceAtLeast(0.2)
        return LatLngBounds.Builder()
            .include(LatLng(center.latitude - padDeg, center.longitude - padDeg / cosLat))
            .include(LatLng(center.latitude + padDeg, center.longitude + padDeg / cosLat))
            .build()
    }

    private fun applyForwardConeFilterAsync() {
        if (!riversReady) {
            riverAdapter.setStatus(getString(R.string.loading_rivers))
            return
        }
        if (!filterInFlight.compareAndSet(false, true)) {
            pendingFilter = true
            return
        }
        riverAdapter.setStatus(
            getString(
                if (headingHoldActive) R.string.filtering_rivers else R.string.filtering_region
            )
        )
        val center = userLatLng
        val heading = currentHeadingDeg
        // Heading cone only while press-and-hold; otherwise viewport region only
        val useCone = headingHoldActive && hasCompass && heading != null
        val viewport = currentViewportBounds() ?: fallbackRadiusBounds(center)
        // Slight pad so rivers clipped at the edge still count
        val padFrac = 0.02
        val latPad = (viewport.latitudeNorth - viewport.latitudeSouth).coerceAtLeast(0.01) * padFrac
        val lonPad = (viewport.longitudeEast - viewport.longitudeWest).coerceAtLeast(0.01) * padFrac
        val minLat = viewport.latitudeSouth - latPad
        val maxLat = viewport.latitudeNorth + latPad
        val minLon = viewport.longitudeWest - lonPad
        val maxLon = viewport.longitudeEast + lonPad
        ioExecutor.execute {
            try {
                val nearCandidates = mutableListOf<Pair<Double, JSONObject>>()
                val ranked = mutableListOf<RiverItem>()

                fun consider(features: JSONArray, index: Int, sourceTag: String) {
                    val featureObj = features.getJSONObject(index)
                    val geom = featureObj.getJSONObject("geometry")
                    val coords = geom.getJSONArray("coordinates")
                    val type = geom.optString("type")
                    // Near-layer: any river intersecting current map viewport
                    if (!bboxIntersects(type, coords, minLon, minLat, maxLon, maxLat)) return

                    val nearest = nearestPointOnGeometry(center, type, coords) ?: return
                    val minKm = nearest.third
                    nearCandidates.add(minKm to featureObj)

                    val bearing = bearingDegrees(
                        center.latitude, center.longitude,
                        nearest.first, nearest.second
                    )
                    // List: viewport ∩ heading cone (cone relative to user location)
                    if (useCone) {
                        val delta = angularDiffDeg(heading!!.toDouble(), bearing)
                        if (delta > CONE_HALF_DEG) return
                    }

                    val props = featureObj.optJSONObject("properties")
                    val displayName = displayNameFor(props, sourceTag, featureKey(props, sourceTag, index))
                    val key = identityKey(props, sourceTag, index, displayName)
                    ranked.add(
                        RiverItem(
                            key = key,
                            displayName = displayName,
                            distanceKm = minKm,
                            bearingDeg = bearing,
                            features = listOf(featureObj),
                            nearestLat = nearest.first,
                            nearestLon = nearest.second,
                            segmentCount = 1
                        )
                    )
                }

                for (i in 0 until hydroFeatures.length()) {
                    consider(hydroFeatures, i, "hydro")
                }
                for (i in 0 until osmFeatures.length()) {
                    consider(osmFeatures, i, "osm")
                }

                ranked.sortWith(
                    compareBy<RiverItem> { it.distanceKm }
                        .thenByDescending { hasRealName(it.displayName) }
                )

                // Aggregate: one row per real river name; unnamed stay unique by segment id
                val groups = LinkedHashMap<String, MutableList<RiverItem>>()
                for (item in ranked) {
                    groups.getOrPut(item.key) { mutableListOf() }.add(item)
                }
                val aggregated = mutableListOf<RiverItem>()
                for ((_, segs) in groups) {
                    val nearest = segs.minBy { it.distanceKm }
                    val allFeatures = segs.flatMap { it.features }
                    aggregated.add(
                        nearest.copy(
                            features = allFeatures,
                            segmentCount = allFeatures.size
                        )
                    )
                }
                aggregated.sortWith(
                    compareBy<RiverItem> { it.distanceKm }
                        .thenByDescending { hasRealName(it.displayName) }
                )
                val display = aggregated.take(LIST_LIMIT).toMutableList()

                // Cap near-layer for overview zooms: closest-to-user within viewport
                nearCandidates.sortBy { it.first }
                val nearOut = JSONArray()
                for (i in 0 until min(nearCandidates.size, NEAR_LAYER_CAP)) {
                    nearOut.put(nearCandidates[i].second)
                }

                val fc = JSONObject()
                    .put("type", "FeatureCollection")
                    .put("features", nearOut)
                val fcStr = fc.toString()
                val headingSnapshot = heading
                val useConeSnapshot = useCone
                mainHandler.post {
                    if (isDestroyed) return@post
                    val style = mapLibreMap?.style ?: return@post
                    (style.getSource(SOURCE_NEAR) as? GeoJsonSource)?.setGeoJson(fcStr)
                    riverItems.clear()
                    riverItems.addAll(display)
                    if (display.isEmpty()) {
                        val status = if (useConeSnapshot && headingSnapshot != null) {
                            "可视区域内${cardinalLabel(headingSnapshot)}方向暂无河段"
                        } else {
                            "当前可视区域内暂无河段"
                        }
                        riverAdapter.setStatus(status)
                    } else {
                        // Keep selection if still in list; refresh amber features
                        val still = display.firstOrNull { it.key == selectedKey }
                        if (selectedKey != null && still == null) {
                            clearSelection(updateList = false)
                        } else if (still != null) {
                            val arr = JSONArray()
                            for (f in still.features) arr.put(f)
                            val selFc = JSONObject()
                                .put("type", "FeatureCollection")
                                .put("features", arr)
                            (style.getSource(SOURCE_SELECTED) as? GeoJsonSource)
                                ?.setGeoJson(selFc.toString())
                        }
                        riverAdapter.setItems(display, selectedKey)
                    }
                    updateHeadingUi()
                }
            } finally {
                filterInFlight.set(false)
                if (pendingFilter) {
                    pendingFilter = false
                    mainHandler.post { scheduleViewUpdate(force = true) }
                }
            }
        }
    }

    private fun hasRealName(name: String): Boolean {
        return name.isNotBlank() &&
            !name.startsWith("未命名") &&
            !name.startsWith("HydroRIVERS") &&
            !name.startsWith("OSM ")
    }

    /** Named rivers group by trimmed name; unnamed stay unique by HYRIV_ID / osm_id. */
    private fun identityKey(
        props: JSONObject?,
        sourceTag: String,
        index: Int,
        displayName: String
    ): String {
        val raw = props?.optString("name").orEmpty().trim()
        if (raw.isNotBlank() && hasRealName(raw)) {
            return "name:" + raw.lowercase()
        }
        return featureKey(props, sourceTag, index)
    }

    private fun featureKey(props: JSONObject?, sourceTag: String, index: Int): String {
        if (props != null) {
            val hydroId = props.opt("HYRIV_ID")
            if (hydroId != null && hydroId.toString().isNotBlank() && hydroId.toString() != "null") {
                return "hydro:$hydroId"
            }
            val osmId = props.optString("osm_id")
            if (osmId.isNotBlank()) return "osm:$osmId"
        }
        return "$sourceTag#$index"
    }

    private fun displayNameFor(props: JSONObject?, sourceTag: String, key: String): String {
        val name = props?.optString("name").orEmpty().trim()
        if (name.isNotBlank()) return name
        if (sourceTag == "hydro") {
            val id = props?.opt("HYRIV_ID")?.toString() ?: key.removePrefix("hydro:")
            return "未命名河段 · #$id"
        }
        val ww = props?.optString("waterway").orEmpty()
        return if (ww.isNotBlank()) "OSM $ww" else "未命名水道"
    }

    private fun selectRiver(item: RiverItem) {
        selectedKey = item.key
        btnClearSelection.visibility = View.VISIBLE
        riverAdapter.setItems(riverItems.toList(), selectedKey)

        val featuresArr = JSONArray()
        for (f in item.features) featuresArr.put(f)
        val fc = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", featuresArr)
        val style = mapLibreMap?.style
        (style?.getSource(SOURCE_SELECTED) as? GeoJsonSource)?.setGeoJson(fc.toString())

        // Focus camera on the selected river only (do not pull toward user location)
        animateCameraToRiver(item)
    }

    /** Bounds of all highlighted segments for [item], or null if geometry cannot be read. */
    private fun boundsForRiverFeatures(item: RiverItem): LatLngBounds? {
        val builder = LatLngBounds.Builder()
        var count = 0
        fun absorbPoint(c: JSONArray) {
            builder.include(LatLng(c.getDouble(1), c.getDouble(0)))
            count++
        }
        fun absorbLine(line: JSONArray) {
            for (i in 0 until line.length()) absorbPoint(line.getJSONArray(i))
        }
        for (feature in item.features) {
            val geom = feature.optJSONObject("geometry") ?: continue
            val type = geom.optString("type")
            val coords = geom.optJSONArray("coordinates") ?: continue
            when (type) {
                "LineString" -> absorbLine(coords)
                "MultiLineString" -> {
                    for (i in 0 until coords.length()) absorbLine(coords.getJSONArray(i))
                }
            }
        }
        if (count == 0) return null
        return try {
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private fun animateCameraToRiver(item: RiverItem) {
        val map = mapLibreMap ?: return
        val nearest = LatLng(item.nearestLat, item.nearestLon)
        val bounds = boundsForRiverFeatures(item)
        try {
            if (bounds != null) {
                val latSpan = bounds.latitudeNorth - bounds.latitudeSouth
                val lonSpan = bounds.longitudeEast - bounds.longitudeWest
                // Degenerate / tiny span: pad around nearest point so newLatLngBounds works
                if (latSpan < 1e-5 && lonSpan < 1e-5) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(nearest, NEAR_ZOOM))
                } else {
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                }
            } else {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(nearest, NEAR_ZOOM))
            }
        } catch (_: Exception) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(nearest, NEAR_ZOOM))
        }
    }

    private fun clearSelection(updateList: Boolean = true) {
        selectedKey = null
        btnClearSelection.visibility = View.GONE
        val style = mapLibreMap?.style
        (style?.getSource(SOURCE_SELECTED) as? GeoJsonSource)?.setGeoJson(emptyFeatureCollection())
        if (updateList) {
            riverAdapter.setItems(riverItems.toList(), null)
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

    /** Returns Triple(lat, lon, distanceKm) of nearest sampled vertex. */
    private fun nearestPointOnGeometry(
        center: LatLng,
        type: String,
        coords: JSONArray
    ): Triple<Double, Double, Double>? {
        var bestLat = 0.0
        var bestLon = 0.0
        var best = Double.MAX_VALUE
        fun considerPoint(c: JSONArray) {
            val lon = c.getDouble(0)
            val lat = c.getDouble(1)
            val d = haversineKm(center.latitude, center.longitude, lat, lon)
            if (d < best) {
                best = d
                bestLat = lat
                bestLon = lon
            }
        }
        fun considerLine(line: JSONArray) {
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
            else -> return null
        }
        if (best == Double.MAX_VALUE) return null
        return Triple(bestLat, bestLon, best)
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

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val θ = Math.toDegrees(atan2(y, x))
        return (θ + 360.0) % 360.0
    }

    private fun angularDiffDeg(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    private fun cardinalLabel(heading: Float): String {
        val h = ((heading % 360f) + 360f) % 360f
        return when {
            h >= 337.5 || h < 22.5 -> "北"
            h < 67.5 -> "东北"
            h < 112.5 -> "东"
            h < 157.5 -> "东南"
            h < 202.5 -> "南"
            h < 247.5 -> "西南"
            h < 292.5 -> "西"
            else -> "西北"
        }
    }

    private fun emptyFeatureCollection(): String =
        """{"type":"FeatureCollection","features":[]}"""

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val heading = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                ((Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0).toFloat()
            }
            Sensor.TYPE_ORIENTATION -> {
                ((event.values[0] % 360f) + 360f) % 360f
            }
            else -> null
        } ?: return

        currentHeadingDeg = heading
        if (!headingHoldActive) return
        val last = lastPublishedHeading
        if (last == null || angularDiffDeg(last.toDouble(), heading.toDouble()) >= HEADING_DELTA_DEG) {
            lastPublishedHeading = heading
            updateHeadingUi()
            scheduleViewUpdate(force = false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Indoor magnetometer drift is expected; UI still shows last heading.
    }

    private fun registerSensors() {
        val sm = sensorManager ?: return
        rotationSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: orientationSensor?.let {
            @Suppress("DEPRECATION")
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(this)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // Compass only while hold-forward is pressed (saves battery / noise)
        if (headingHoldActive) {
            registerSensors()
        } else {
            updateHeadingUi()
        }
    }

    override fun onPause() {
        // End hold if activity backgrounds mid-press
        if (headingHoldActive) {
            headingHoldActive = false
            findViewById<Button?>(R.id.btnHoldForward)?.isPressed = false
            updateHint()
            updateHeadingUi()
            scheduleViewUpdate(force = true)
        }
        unregisterSensors()
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
        mainHandler.removeCallbacks(delayedFilterRunnable)
        ioExecutor.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    private inner class RiverListAdapter : BaseAdapter() {
        private var items: List<RiverItem> = emptyList()
        private var selected: String? = null
        private var statusMessage: String? = null

        fun setItems(list: List<RiverItem>, selectedKey: String?) {
            items = list
            selected = selectedKey
            statusMessage = null
            notifyDataSetChanged()
        }

        fun setStatus(msg: String) {
            items = emptyList()
            statusMessage = msg
            notifyDataSetChanged()
        }

        override fun getCount(): Int =
            if (statusMessage != null) 1 else items.size

        override fun getItem(position: Int): Any =
            if (statusMessage != null) statusMessage!! else items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun isEnabled(position: Int): Boolean = statusMessage == null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_river, parent, false)
            val nameView = view.findViewById<TextView>(R.id.riverName)
            val metaView = view.findViewById<TextView>(R.id.riverMeta)
            val status = statusMessage
            if (status != null) {
                nameView.text = status
                nameView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_muted))
                metaView.text = ""
                view.setBackgroundColor(Color.TRANSPARENT)
                return view
            }
            val item = items[position]
            nameView.text = item.displayName
            nameView.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (item.key == selected) R.color.md_amber else R.color.md_on_dark
                )
            )
            metaView.text = if (item.segmentCount > 1) {
                "%.1f km  ·  %.0f° %s  ·  %d段".format(
                    item.distanceKm,
                    item.bearingDeg,
                    cardinalLabel(item.bearingDeg.toFloat()),
                    item.segmentCount
                )
            } else {
                "%.1f km  ·  %.0f° %s".format(
                    item.distanceKm,
                    item.bearingDeg,
                    cardinalLabel(item.bearingDeg.toFloat())
                )
            }
            view.setBackgroundColor(
                if (item.key == selected) {
                    ContextCompat.getColor(this@MainActivity, R.color.md_selected_bg)
                } else {
                    Color.TRANSPARENT
                }
            )
            return view
        }
    }

    companion object {
        private val DEFAULT_CENTER = LatLng(30.67, 104.06)
        private const val DEFAULT_ZOOM = 8.5
        private const val NEAR_ZOOM = 10.5
        private const val NEARBY_KM = 40.0
        private const val CONE_HALF_DEG = 50.0
        private const val HEADING_DELTA_DEG = 8.0
        private const val FILTER_THROTTLE_MS = 750L
        private const val LIST_LIMIT = 12
        private const val NEAR_LAYER_CAP = 400
        private const val STYLE_ASSET = "style-dark.json"
        private const val MBTILES_FILE = "sichuan-basemap.mbtiles"
        private const val HYDRO_ASSET = "hydrorivers_sichuan.geojson"
        private const val OSM_ASSET = "osm_waterways_sichuan.geojson"
        private const val SOURCE_HYDRO = "rivers-hydro"
        private const val SOURCE_OSM = "rivers-osm"
        private const val SOURCE_NEAR = "rivers-near"
        private const val SOURCE_SELECTED = "rivers-selected"
        private const val LAYER_HYDRO = "rivers-hydro-line"
        private const val LAYER_OSM = "rivers-osm-line"
        private const val LAYER_NEAR = "rivers-near-line"
        private const val LAYER_NEAR_GLOW = "rivers-near-glow"
        private const val LAYER_SELECTED = "rivers-selected-line"
        private const val LAYER_SELECTED_GLOW = "rivers-selected-glow"
    }
}
