import MapKit
import SwiftUI

extension SharedLocation {
    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var measuredAt: Date {
        Date(timeIntervalSince1970: TimeInterval(measuredAtMs) / 1000)
    }

    /// Older than ten minutes: probably not where they are now.
    func isStale(at now: Date = Date()) -> Bool {
        now.timeIntervalSince(measuredAt) > 600
    }
}

/// "Until 17:30", "Until you stop".
func sharingUntilText(_ untilMs: Int64?) -> String {
    guard let untilMs else { return "Until you stop" }
    let date = Date(timeIntervalSince1970: TimeInterval(untilMs) / 1000)
    if Calendar.current.isDateInToday(date) {
        return "Until \(date.formatted(date: .omitted, time: .shortened))"
    }
    return "Until \(date.formatted(date: .abbreviated, time: .shortened))"
}

// MARK: - Chat info

/// Share my location (and see who shares theirs) in a chat's info.
struct ChatLocationSection: View {
    @Environment(AppModel.self) private var model
    let conversationId: String
    @State private var working = false
    @State private var error: String?
    @State private var showMap = false

    private var share: LocationShare? { model.locationShare(in: conversationId) }

    private var theirs: [SharedLocation] {
        model.sharedLocations.filter { $0.conversationId == conversationId }
    }

    var body: some View {
        Section {
            if let share {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Sharing your location")
                        Text(sharingUntilText(share.untilMs))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "location.fill").foregroundStyle(.tint)
                }
                Button("Stop sharing my location", role: .destructive) {
                    Task { await stop() }
                }
                .disabled(working)
            } else {
                Menu {
                    Button("For 1 hour") { Task { await start(until: Date().addingTimeInterval(3600)) } }
                    Button("Until end of day") { Task { await start(until: endOfToday) } }
                    Button("Until I stop") { Task { await start(until: nil) } }
                } label: {
                    Label("Share my location", systemImage: "location")
                }
                .disabled(working)
            }
            if !theirs.isEmpty {
                Button {
                    showMap = true
                } label: {
                    Label(
                        theirs.count == 1
                            ? "\(theirs[0].username) is sharing their location"
                            : "\(theirs.count) people are sharing their location",
                        systemImage: "map"
                    )
                }
            }
            if model.location.isDenied {
                Text("Location access is off for Messenger. Turn it on in System Settings to share your location.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            } else if share != nil && !model.location.sharesInBackground {
                Button("Keep sharing when Messenger is closed") { model.location.requestAccess() }
            }
            if let error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
        } header: {
            Text("Location")
        } footer: {
            Text("Your location is end-to-end encrypted. Only the people in this chat see it, only while you share, and you can stop at any time.")
        }
        .sheet(isPresented: $showMap) {
            PeopleMapView()
        }
    }

    private var endOfToday: Date {
        let calendar = Calendar.current
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: Date())) ?? Date()
        return tomorrow.addingTimeInterval(-60)
    }

    private func start(until: Date?) async {
        working = true
        defer { working = false }
        do {
            try await model.startSharingLocation(in: conversationId, until: until)
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }

    private func stop() async {
        working = true
        defer { working = false }
        do {
            try await model.stopSharingLocation(in: conversationId)
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }
}

// MARK: - Map

/// Everyone sharing their location with you, on one map.
struct PeopleMapView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var position: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var selection: String?

    var body: some View {
        NavigationStack {
            Map(position: $position, selection: $selection) {
                UserAnnotation()
                ForEach(model.sharedLocations, id: \.username) { person in
                    MapCircle(center: person.coordinate, radius: CLLocationDistance(max(person.accuracyM, 5)))
                        .foregroundStyle(model.ink(for: person.username).color.opacity(0.12))
                        .stroke(model.ink(for: person.username).color.opacity(0.4), lineWidth: 1)
                    Annotation(person.username, coordinate: person.coordinate, anchor: .center) {
                        PersonPin(person: person, selected: selection == person.username)
                    }
                    .tag(person.username)
                }
            }
            .mapControls {
                MapUserLocationButton()
                MapCompass()
                MapScaleView()
            }
            .safeAreaInset(edge: .bottom) { panel }
            .navigationTitle("Map")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onChange(of: selection) { _, name in
                guard let name, let person = model.sharedLocations.first(where: { $0.username == name }) else { return }
                withAnimation {
                    position = .region(MKCoordinateRegion(center: person.coordinate, latitudinalMeters: 1500, longitudinalMeters: 1500))
                }
            }
            // Shows everyone (and us) when the map opens and when someone
            // starts or stops sharing, unless the map was moved by hand.
            .onChange(of: model.sharedLocations.map(\.username), initial: true) {
                guard !position.positionedByUser, selection == nil else { return }
                frameEveryone()
            }
            // Keeps "updated … ago" honest and notices shares that ended.
            .task {
                while !Task.isCancelled {
                    await model.refreshLocations()
                    try? await Task.sleep(nanoseconds: 15_000_000_000)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 720, minHeight: 560)
        #endif
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.sharedLocations.isEmpty {
                HStack(alignment: .top, spacing: 12) {
                    IllustrationArt(illustration: .map)
                        .frame(width: 64, height: 48)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Nobody is sharing their location with you")
                            .font(.subheadline.weight(.semibold))
                        Text("To share yours, open a chat's info and tap Share my location.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            ForEach(model.sharedLocations, id: \.username) { person in
                PersonRow(person: person, me: model.location.current) {
                    selection = person.username
                } directions: {
                    openDirections(to: person)
                }
            }
            if !model.locationShares.isEmpty {
                Divider()
                ForEach(model.locationShares, id: \.conversationId) { share in
                    HStack {
                        Image(systemName: "location.fill").foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("You're sharing with \(share.title)").font(.subheadline)
                            Text(sharingUntilText(share.untilMs)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Stop") {
                            Task { try? await model.stopSharingLocation(in: share.conversationId) }
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: 560, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(12)
    }

    private func frameEveryone() {
        var points = model.sharedLocations.map(\.coordinate)
        guard !points.isEmpty else {
            position = .userLocation(fallback: .automatic)
            return
        }
        if let me = model.location.current?.coordinate {
            points.append(me)
        }
        withAnimation {
            position = .region(Self.region(fitting: points))
        }
    }

    /// A region around all the points, with room at the edges, and at least
    /// a kilometre or so across so one person isn't shown at street level.
    static func region(fitting points: [CLLocationCoordinate2D]) -> MKCoordinateRegion {
        let latitudes = points.map(\.latitude)
        let longitudes = points.map(\.longitude)
        let (south, north) = (latitudes.min() ?? 0, latitudes.max() ?? 0)
        let (west, east) = (longitudes.min() ?? 0, longitudes.max() ?? 0)
        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: (south + north) / 2, longitude: (west + east) / 2),
            span: MKCoordinateSpan(
                latitudeDelta: min(max((north - south) * 1.6, 0.012), 170),
                longitudeDelta: min(max((east - west) * 1.6, 0.012), 360)
            )
        )
    }

    private func openDirections(to person: SharedLocation) {
        let item = MKMapItem(placemark: MKPlacemark(coordinate: person.coordinate))
        item.name = person.username
        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDefault])
    }
}

/// An avatar on the map. Greyed out when the position is old.
private struct PersonPin: View {
    let person: SharedLocation
    let selected: Bool

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            Avatar(title: person.username, size: 38)
                .overlay(Circle().stroke(.white, lineWidth: 3))
                .shadow(color: .black.opacity(0.25), radius: selected ? 6 : 3, y: 1)
                .scaleEffect(selected ? 1.18 : 1)
                .saturation(person.isStale(at: context.date) ? 0 : 1)
                .opacity(person.isStale(at: context.date) ? 0.7 : 1)
                .animation(.spring(duration: 0.25), value: selected)
        }
        .accessibilityLabel("\(person.username)'s location")
    }
}

private struct PersonRow: View {
    let person: SharedLocation
    let me: CLLocation?
    let focus: () -> Void
    let directions: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Avatar(title: person.username, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(person.username).font(.headline)
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    Text(detail(now: context.date))
                        .font(.caption)
                        .foregroundStyle(person.isStale(at: context.date) ? Color.orange : Color.secondary)
                }
            }
            Spacer(minLength: 8)
            if let battery = person.batteryPercent {
                Label("\(battery)%", systemImage: batterySymbol(battery))
                    .font(.caption)
                    .foregroundStyle(battery <= 15 ? Color.red : Color.secondary)
            }
            Button(action: directions) {
                Image(systemName: "arrow.triangle.turn.up.right.circle.fill")
                    .font(.title2)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
            .accessibilityLabel("Directions to \(person.username)")
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: focus)
    }

    private func detail(now: Date) -> String {
        var parts: [String] = []
        if now.timeIntervalSince(person.measuredAt) < 60 {
            parts.append("Updated just now")
        } else {
            let formatter = RelativeDateTimeFormatter()
            formatter.unitsStyle = .abbreviated
            parts.append("Updated \(formatter.localizedString(for: person.measuredAt, relativeTo: now))")
        }
        if let me {
            let metres = me.distance(from: CLLocation(latitude: person.latitude, longitude: person.longitude))
            parts.append(Measurement(value: metres, unit: UnitLength.meters).formatted(.measurement(width: .abbreviated, usage: .road)) + " away")
        }
        if let speed = person.speedMps, speed > 1.5 {
            let measurement = Measurement(value: Double(speed), unit: UnitSpeed.metersPerSecond)
            parts.append(measurement.formatted(.measurement(width: .abbreviated, usage: .general, numberFormatStyle: .number.precision(.fractionLength(0)))))
        }
        return parts.joined(separator: " · ")
    }

    private func batterySymbol(_ level: UInt8) -> String {
        switch level {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }
}
