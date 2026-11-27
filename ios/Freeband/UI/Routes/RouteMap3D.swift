import MapLibre
import SwiftUI
import UIKit

/// The route on an OpenStreetMap-based 3D map (MapLibre + OpenFreeMap "liberty", whose buildings
/// are extruded from OSM heights). Apple Maps only has 3D buildings in some cities; OSM has
/// building footprints almost everywhere.
struct RouteMap3D: UIViewRepresentable {
    static let styleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")!

    let track: GpsTrack
    let chunks: [RouteChunk]
    /// Seconds since the start of the track.
    let elapsed: TimeInterval
    /// Follow camera on (heading set) or overview (nil).
    let followHeading: Double?
    /// Bumped to fly back to the overview.
    let overviewToken: Int
    /// Hides MapLibre's own ornaments (for the Story recording).
    var clean = false

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: Self.styleURL)
        map.delegate = context.coordinator
        map.maximumPitch = 70
        map.compassView.isHidden = true
        map.logoView.isHidden = clean
        map.attributionButton.isHidden = clean
        map.allowsRotating = true
        map.allowsTilting = true
        context.coordinator.map = map
        context.coordinator.parent = self
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        c.parent = self
        c.render()
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        weak var map: MLNMapView?
        var parent: RouteMap3D?
        private var styleReady = false
        private var lastOverviewToken = -1

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            guard let parent else { return }
            ensureBuildings(style)

            let all = parent.track.points.map(\.coordinate)
            let ghost = MLNShapeSource(identifier: "aravia-ghost",
                                       shape: MLNPolylineFeature(coordinates: all, count: UInt(all.count)),
                                       options: nil)
            style.addSource(ghost)
            let ghostLayer = MLNLineStyleLayer(identifier: "aravia-ghost", source: ghost)
            ghostLayer.lineColor = NSExpression(forConstantValue: UIColor.black.withAlphaComponent(0.35))
            ghostLayer.lineWidth = NSExpression(forConstantValue: 5)
            ghostLayer.lineCap = NSExpression(forConstantValue: "round")
            ghostLayer.lineJoin = NSExpression(forConstantValue: "round")
            style.addLayer(ghostLayer)

            // One source and layer per speed colour.
            for (i, color) in RouteChunkPalette.colors.enumerated() {
                let src = MLNShapeSource(identifier: "aravia-done-\(i)", shape: nil, options: nil)
                style.addSource(src)
                let layer = MLNLineStyleLayer(identifier: "aravia-done-\(i)", source: src)
                layer.lineColor = NSExpression(forConstantValue: color)
                layer.lineWidth = NSExpression(forConstantValue: 8)
                layer.lineCap = NSExpression(forConstantValue: "round")
                layer.lineJoin = NSExpression(forConstantValue: "round")
                style.addLayer(layer)
            }

            let me = MLNShapeSource(identifier: "aravia-me", shape: nil, options: nil)
            style.addSource(me)
            let halo = MLNCircleStyleLayer(identifier: "aravia-me-halo", source: me)
            halo.circleRadius = NSExpression(forConstantValue: 18)
            halo.circleColor = NSExpression(forConstantValue: RouteChunkPalette.walker)
            halo.circleOpacity = NSExpression(forConstantValue: 0.25)
            halo.circlePitchAlignment = NSExpression(forConstantValue: "map")
            style.addLayer(halo)
            let dot = MLNCircleStyleLayer(identifier: "aravia-me", source: me)
            dot.circleRadius = NSExpression(forConstantValue: 8)
            dot.circleColor = NSExpression(forConstantValue: RouteChunkPalette.walker)
            dot.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            dot.circleStrokeWidth = NSExpression(forConstantValue: 3)
            dot.circlePitchAlignment = NSExpression(forConstantValue: "map")
            style.addLayer(dot)

            styleReady = true
            render()
        }

        /// Liberty ships a "building-3d" extrusion; add one if this style version lacks it.
        private func ensureBuildings(_ style: MLNStyle) { RouteMapStyle.tuneBuildings(style) }

        func render() {
            guard let map, let parent else { return }

            if parent.overviewToken != lastOverviewToken, parent.followHeading == nil {
                lastOverviewToken = parent.overviewToken
                flyToOverview(map, parent.track)
            }
            guard styleReady, let style = map.style else { return }

            let now = parent.track.position(at: parent.elapsed)
            let me = MLNPointFeature()
            me.coordinate = now.coordinate
            (style.source(withIdentifier: "aravia-me") as? MLNShapeSource)?.shape = me

            var buckets = Array(repeating: [MLNPolylineFeature](), count: RouteChunkPalette.colors.count)
            for chunk in parent.chunks where chunk.range.lowerBound <= now.index {
                let upper = min(chunk.range.upperBound, now.index)
                var coords = parent.track.points[chunk.range.lowerBound...upper].map(\.coordinate)
                if chunk.range.contains(now.index) { coords.append(now.coordinate) }
                guard coords.count > 1 else { continue }
                buckets[chunk.paletteIndex].append(MLNPolylineFeature(coordinates: coords, count: UInt(coords.count)))
            }
            for (i, lines) in buckets.enumerated() {
                (style.source(withIdentifier: "aravia-done-\(i)") as? MLNShapeSource)?.shape =
                    MLNShapeCollectionFeature(shapes: lines)
            }

            if let heading = parent.followHeading {
                let cam = MLNMapCamera(lookingAtCenter: now.coordinate, altitude: 260, pitch: 65, heading: heading)
                map.setCamera(cam, animated: false)
            }
        }

        private func flyToOverview(_ map: MLNMapView, _ track: GpsTrack) {
            guard let first = track.points.first else { return }
            var sw = first.coordinate, ne = first.coordinate
            for p in track.points {
                sw.latitude = min(sw.latitude, p.latitude); sw.longitude = min(sw.longitude, p.longitude)
                ne.latitude = max(ne.latitude, p.latitude); ne.longitude = max(ne.longitude, p.longitude)
            }
            let bounds = MLNCoordinateBounds(sw: sw, ne: ne)
            let cam = map.cameraThatFitsCoordinateBounds(bounds, edgePadding: UIEdgeInsets(top: 80, left: 40, bottom: 260, right: 40))
            cam.pitch = 50
            cam.heading = 330
            map.setCamera(cam, withDuration: 1.2, animationTimingFunction: CAMediaTimingFunction(name: .easeInEaseOut))
        }
    }
}

/// Speed colours shared by the map and the legend.
enum RouteChunkPalette {
    /// Slow to fast: river, forest, ochre, annatto, deep annatto.
    static let colors: [UIColor] = [AraviaPalette.rio, AraviaPalette.mata, AraviaPalette.ocre,
                                    AraviaPalette.urucum, AraviaPalette.urucumDeep]
    static let walker = AraviaPalette.urucum

    static func index(_ kmh: Double?) -> Int {
        guard let v = kmh else { return 3 }
        if v < 3 { return 0 }
        if v < 4.5 { return 1 }
        if v < 6 { return 2 }
        if v < 10 { return 3 }
        return 4
    }
}

/// Building look shared by the Routes screen and the Story video.
enum RouteMapStyle {
    /// Every building OpenStreetMap knows stands up from zoom 13 (the style left them flat below
    /// zoom 14), and one with no height data gets the height of a two-storey house instead of
    /// almost nothing, so houses read as houses from the walker's camera. Colours go from clay for
    /// houses to stone for towers.
    static func tuneBuildings(_ style: MLNStyle) {
        let layer: MLNFillExtrusionStyleLayer
        if let existing = style.layers.first(where: { $0 is MLNFillExtrusionStyleLayer }) as? MLNFillExtrusionStyleLayer {
            layer = existing
        } else {
            guard let source = style.sources.first(where: { $0 is MLNVectorTileSource }) else { return }
            layer = MLNFillExtrusionStyleLayer(identifier: "aravia-buildings", source: source)
            layer.sourceLayerIdentifier = "building"
            style.addLayer(layer)
        }
        if let flat = style.layer(withIdentifier: "building") { flat.isVisible = false }
        layer.minimumZoomLevel = 13
        let height: [Any] = ["max", ["coalesce", ["get", "render_height"], 0], 7]
        layer.fillExtrusionHeight = NSExpression(mglJSONObject: height)
        layer.fillExtrusionBase = NSExpression(mglJSONObject: ["coalesce", ["get", "render_min_height"], 0])
        layer.fillExtrusionColor = NSExpression(mglJSONObject: [
            "interpolate", ["linear"], height,
            7, "#D9C2A3",
            15, "#CDBBA6",
            40, "#B9B2AA",
            100, "#A7A9AD",
        ] as [Any])
        layer.fillExtrusionOpacity = NSExpression(forConstantValue: 0.95)
        layer.fillExtrusionHasVerticalGradient = NSExpression(forConstantValue: true)
    }
}
