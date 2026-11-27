package app.aravia.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aravia.protocol.GpsTrack
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The route on an OpenStreetMap-based map tilted in 3D, the same map as on iOS: MapLibre with
 * OpenFreeMap's "liberty" style, whose buildings stand up from OSM heights. Supports interactive
 * 2D/3D tilt toggle and animated route replay (`replayFraction` in 0f..1f).
 */
@Composable
fun RouteMap(
    track: GpsTrack,
    color: Color,
    modifier: Modifier = Modifier,
    tilted: Boolean = true,
    replayFraction: Float? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lineColor = color.toArgb()
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var heading by remember { mutableStateOf<Double?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                mapRef = map
                map.uiSettings.isRotateGesturesEnabled = true
                map.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                    val points = track.points.map { Point.fromLngLat(it.longitude, it.latitude) }
                    style.addSource(GeoJsonSource("aravia-route", LineString.fromLngLats(points)))
                    style.addLayer(LineLayer("aravia-route-line", "aravia-route").withProperties(
                        PropertyFactory.lineColor(lineColor),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ))
                    style.addSource(GeoJsonSource("aravia-replay-dot", FeatureCollection.fromFeatures(emptyList<Feature>())))
                    style.addLayer(CircleLayer("aravia-replay-dot-layer", "aravia-replay-dot").withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(lineColor),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    ))
                    val ll = track.points.map { LatLng(it.latitude, it.longitude) }
                    if (ll.size >= 2) {
                        val bounds = LatLngBounds.Builder().includes(ll).build()
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                        if (tilted) map.animateCamera(CameraUpdateFactory.tiltTo(50.0), 1200)
                    } else if (ll.size == 1) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(ll[0], 16.0))
                    }
                    styleReady = true
                }
            }
        }
    }

    // Toggle 2D/3D camera tilt when not replaying.
    LaunchedEffect(tilted, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || replayFraction != null) return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.tiltTo(if (tilted) 50.0 else 0.0), 500)
    }

    // Update replay dot and camera as replayFraction advances.
    LaunchedEffect(replayFraction, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val dot = style.getSourceAs<GeoJsonSource>("aravia-replay-dot") ?: return@LaunchedEffect
        val frac = replayFraction
        if (frac == null) {
            dot.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            heading = null
            val ll = track.points.map { LatLng(it.latitude, it.longitude) }
            if (ll.size >= 2) {
                val bounds = LatLngBounds.Builder().includes(ll).build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600)
            }
            return@LaunchedEffect
        }
        val sec = track.durationSeconds * frac.coerceIn(0f, 1f).toDouble()
        val pos = track.position(sec)
        val nextH = track.nextHeading(sec, heading)
        heading = nextH
        dot.setGeoJson(Point.fromLngLat(pos.longitude, pos.latitude))
        val cam = CameraPosition.Builder()
            .target(LatLng(pos.latitude, pos.longitude))
            .zoom(17.0)
            .tilt(if (tilted) 58.0 else 0.0)
            .bearing(nextH)
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
