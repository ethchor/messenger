import CoreLocation
import Observation
#if os(iOS)
import UIKit
#endif

/// Feeds this device's position to the core while we share our location in
/// any chat, and stops when we don't. Positions go out when we've moved, or
/// every so often while standing still, so people watching see we're live.
@MainActor
@Observable
final class LocationService: NSObject {
    private(set) var authorization: CLAuthorizationStatus
    /// The latest position of this device, for distances on the map.
    private(set) var current: CLLocation?
    private(set) var isRunning = false

    /// Called with each position worth sending.
    @ObservationIgnored var onFix: ((LocationFix) -> Void)?

    @ObservationIgnored private let manager: CLLocationManager
    @ObservationIgnored private var lastSent: (location: CLLocation, at: Date)?
    @ObservationIgnored private var inBackground = false
    /// iOS paused the updates because we stood still for a while.
    @ObservationIgnored private var paused = false

    override init() {
        let manager = CLLocationManager()
        self.manager = manager
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        // Every fix, also while standing still: `handle` decides what goes
        // out, and the heartbeat needs fresh positions to show we're live.
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .other
        #if os(iOS)
        allowPausingIfWeCanResume()
        UIDevice.current.isBatteryMonitoringEnabled = true
        #endif
    }

    var isDenied: Bool {
        authorization == .denied || authorization == .restricted
    }

    /// Whether sharing carries on while the app is closed (iPhone: "Always").
    var sharesInBackground: Bool {
        #if os(iOS)
        return authorization == .authorizedAlways
        #else
        return true
        #endif
    }

    /// Asks for access: first while using the app, then (on iPhone) always,
    /// so sharing keeps going in the background.
    func requestAccess() {
        switch authorization {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        #if os(iOS)
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
        #endif
        default:
            break
        }
    }

    /// Runs the location service while `sharing` is true.
    func setSharing(_ sharing: Bool) {
        guard sharing != isRunning else { return }
        isRunning = sharing
        if sharing {
            if authorization == .notDetermined {
                manager.requestWhenInUseAuthorization()
            }
            #if os(iOS)
            // Keeps updates coming when the app goes to the background
            // (iOS shows the blue location pill meanwhile).
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
            if authorization == .authorizedAlways {
                // Wakes the app after big moves even if iOS ended it.
                manager.startMonitoringSignificantLocationChanges()
            }
            #endif
            manager.startUpdatingLocation()
        } else {
            manager.stopUpdatingLocation()
            #if os(iOS)
            manager.stopMonitoringSignificantLocationChanges()
            manager.allowsBackgroundLocationUpdates = false
            #endif
            lastSent = nil
            paused = false
        }
    }

    /// Sends the current position at once (sharing just started); without a
    /// recent one, the next fix goes out as soon as it comes.
    func sendNow() {
        lastSent = nil
        if let current, Date().timeIntervalSince(current.timestamp) < 60 {
            deliver(current)
        }
    }

    func appWentToBackground(_ background: Bool) {
        inBackground = background
        #if os(iOS)
        // Coarser fixes (Wi-Fi and cell, GPS mostly off) while the app is
        // closed: plenty for the people watching, far easier on the battery.
        manager.desiredAccuracy = background ? kCLLocationAccuracyHundredMeters : kCLLocationAccuracyNearestTenMeters
        if !background {
            resumeIfPaused()
        }
        #endif
    }

    #if os(iOS)
    /// iOS pauses updates while we stand still, and only a significant move
    /// (which needs "Always" access) wakes us to resume them. Without it, a
    /// pause would end sharing until the app is opened again, so then we
    /// don't let it pause.
    private func allowPausingIfWeCanResume() {
        manager.pausesLocationUpdatesAutomatically = manager.authorizationStatus == .authorizedAlways
    }

    private func resumeIfPaused() {
        guard paused, isRunning else { return }
        paused = false
        manager.startUpdatingLocation()
    }
    #endif

    private func handle(_ location: CLLocation) {
        // Negative accuracy means the position is invalid.
        guard location.horizontalAccuracy >= 0 else { return }
        current = location
        guard isRunning else { return }
        #if os(iOS)
        // A significant move woke us after a pause: we're on the way again.
        resumeIfPaused()
        #endif
        if let lastSent {
            let elapsed = Date().timeIntervalSince(lastSent.at)
            let moved = location.distance(from: lastSent.location)
            let better = location.horizontalAccuracy < lastSent.location.horizontalAccuracy / 2
            // Closer together while open and moving; sparser in the background.
            let (minGap, minMove, heartbeat): (TimeInterval, CLLocationDistance, TimeInterval) =
                inBackground ? (30, 100, 300) : (5, 15, 30)
            let due = elapsed >= heartbeat || (elapsed >= minGap && (moved >= minMove || better))
            guard due else { return }
        }
        deliver(location)
    }

    private func deliver(_ location: CLLocation) {
        lastSent = (location, Date())
        onFix?(LocationFix(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            accuracyM: Float(location.horizontalAccuracy),
            measuredAtMs: Int64(location.timestamp.timeIntervalSince1970 * 1000),
            speedMps: location.speed >= 0 ? Float(location.speed) : nil,
            courseDeg: location.course >= 0 && location.course < 360 ? Float(location.course) : nil,
            batteryPercent: Self.batteryPercent
        ))
    }

    private static var batteryPercent: UInt8? {
        #if os(iOS)
        let level = UIDevice.current.batteryLevel
        return level >= 0 ? UInt8((level * 100).rounded()) : nil
        #else
        return nil
        #endif
    }
}

extension LocationService: CLLocationManagerDelegate {
    // The manager was made on the main thread, so it calls back there.
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        MainActor.assumeIsolated { handle(location) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated {
            authorization = status
            #if os(iOS)
            allowPausingIfWeCanResume()
            if isRunning && status == .authorizedAlways {
                self.manager.startMonitoringSignificantLocationChanges()
            }
            #endif
        }
    }

    #if os(iOS)
    nonisolated func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        MainActor.assumeIsolated { paused = true }
    }

    nonisolated func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        MainActor.assumeIsolated { paused = false }
    }
    #endif

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Temporary (no fix yet, or a one-off request failed); the next update retries.
    }
}
