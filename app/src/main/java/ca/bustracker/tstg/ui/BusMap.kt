package ca.bustracker.tstg.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ca.bustracker.tstg.BuildConfig
import ca.bustracker.tstg.R
import ca.bustracker.tstg.data.ChildInfo
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

/** Downtown Toronto, shown until the portal gives us a stop or a bus. */
private val DEFAULT_CENTER = GeoPoint(43.6532, -79.3832)
private const val DEFAULT_ZOOM = 11.0
private const val SINGLE_POINT_ZOOM = 16.0
private const val MAX_FIT_ZOOM = 17.0

/**
 * Native map of the live bus and the student's stop, drawn from the GPS
 * positions the app already reads from the portal's Where's My Bus page.
 *
 * The map follows the bus on every refresh until the person touches it;
 * the re-centre button turns following back on.
 */
@Composable
fun BusMap(
    child: ChildInfo?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { MapState(context) }
    // Bumped by the re-centre button to fit the view again.
    var recenterRequests by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.map.onResume()
                Lifecycle.Event.ON_PAUSE -> state.map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            state.map.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.map.onPause()
            state.map.onDetach()
        }
    }

    val bus = child?.busPoint()
    val stop = child?.stopPoint()

    LaunchedEffect(bus, stop, child?.busAndRoute, child?.stopName) {
        state.update(child, bus, stop)
    }
    LaunchedEffect(recenterRequests) {
        if (recenterRequests > 0) state.follow = true
        state.fit(bus, stop)
    }

    // clipToBounds: while pinching, osmdroid draws scaled tiles outside its
    // own rectangle, which Compose does not clip for an embedded view. The
    // map then spilled over the card above it and appeared to bounce.
    Box(modifier = modifier.clipToBounds()) {
        AndroidView(factory = { state.map }, modifier = Modifier.fillMaxSize())

        StatusBanner(
            child = child,
            hasBus = bus != null,
            hasStop = stop != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp, start = 12.dp, end = 12.dp),
        )

        if (bus != null || stop != null) {
            SmallFloatingActionButton(
                onClick = { recenterRequests++ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(12.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Re-centre on the bus")
            }
        }

        // Required by the OpenStreetMap tile usage policy.
        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF424242),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(4.dp),
        )
    }
}

@Composable
private fun StatusBanner(
    child: ChildInfo?,
    hasBus: Boolean,
    hasStop: Boolean,
    modifier: Modifier = Modifier,
) {
    val title: String
    val detail: String?
    when {
        hasBus -> {
            title = child?.busAndRoute ?: "Bus location"
            detail = child?.lastPositionText ?: child?.mapDelayText
        }
        child == null -> {
            title = "Loading the bus location…"
            detail = null
        }
        else -> {
            title = "No live bus position right now"
            detail = child.mapDelayText ?: child.lastPositionText
                ?: if (hasStop) "Showing your stop. The bus appears when it's on the road." else null
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun ChildInfo.busPoint(): GeoPoint? {
    val lat = busLatitude ?: return null
    val lon = busLongitude ?: return null
    return GeoPoint(lat, lon)
}

private fun ChildInfo.stopPoint(): GeoPoint? {
    val lat = stopLatitude ?: return null
    val lon = stopLongitude ?: return null
    return GeoPoint(lat, lon)
}

/** Owns the osmdroid view and its two markers across recompositions. */
private class MapState(context: Context) {
    /** False once the person moves the map themselves. */
    var follow = true

    val map: MapView

    private val busMarker: Marker
    private val stopMarker: Marker

    init {
        Configuration.getInstance().apply {
            // Identify the app to the tile servers, as their policy asks, and
            // keep the tile cache in app storage (no storage permission).
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            val base = File(context.cacheDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }
        map = MapView(context).apply {
            // osmdroid draws whole 256px tiles, so the edge tiles stick out
            // past the view. Clip at the view's own render node; otherwise the
            // overhang slides over the card above while dragging, which looks
            // like the whole map box moving.
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BOUNDS
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            // One copy of the world, and no panning past the poles.
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(
                MapView.getTileSystem().maxLatitude,
                MapView.getTileSystem().minLatitude,
                0,
            )
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(DEFAULT_CENTER)
            overlays.add(TouchWatcher { follow = false })
        }
        stopMarker = Marker(map).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_map_stop)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        busMarker = Marker(map).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_map_bus)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
    }

    fun update(child: ChildInfo?, bus: GeoPoint?, stop: GeoPoint?) {
        place(stopMarker, stop, child?.stopName ?: "Your stop")
        // Added last so the bus draws on top of the stop.
        place(busMarker, bus, child?.busAndRoute ?: "Bus")
        map.invalidate()
        if (follow) fit(bus, stop)
    }

    private fun place(marker: Marker, point: GeoPoint?, title: String) {
        if (point == null) {
            map.overlays.remove(marker)
            return
        }
        marker.position = point
        marker.title = title
        if (!map.overlays.contains(marker)) map.overlays.add(marker)
    }

    /** Frames the bus and the stop together, or whichever one we have. */
    fun fit(bus: GeoPoint?, stop: GeoPoint?) {
        val points = listOfNotNull(bus, stop)
        if (points.isEmpty()) return
        val apply = {
            if (points.size == 1 || points[0].distanceToAsDouble(points[1]) < 50.0) {
                map.controller.setZoom(SINGLE_POINT_ZOOM)
                map.controller.setCenter(points.first())
            } else {
                val box = BoundingBox.fromGeoPointsSafe(points)
                val border = (map.width.coerceAtMost(map.height) / 6).coerceAtLeast(48)
                map.zoomToBoundingBox(box, false, border)
                if (map.zoomLevelDouble > MAX_FIT_ZOOM) map.controller.setZoom(MAX_FIT_ZOOM)
            }
        }
        if (map.isLayoutOccurred) {
            apply()
        } else {
            map.addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
        }
    }
}

/** Notices when the person touches the map so we stop auto-following. */
private class TouchWatcher(private val onTouched: () -> Unit) : Overlay() {
    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        // Any touch counts, including a double-tap zoom, so a refresh never
        // yanks the view back while the person is looking around.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouched()
        return false
    }
}
