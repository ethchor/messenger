import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Papers that move: lines that drift on their own, bend towards a finger
/// or the pointer, and ripple out when a message is sent or arrives.
enum LivePaper: String, CaseIterable {
    /// Rings of arcs rising from below, as on qwantarc.com.
    case arcs
    /// The terrain of ethchor.qwantarc.com/vimu: contour lines over a fixed
    /// landscape, and the ground rising under a finger or the pointer.
    case contours

    var title: String {
        switch self {
        case .arcs: "Arcs"
        case .contours: "Contours"
        }
    }
}

/// A message that just went or came: where its ripple starts.
struct LivePulse: Equatable {
    let id: String
    let outgoing: Bool
}

/// What a live paper knows between frames: where the finger or pointer is,
/// where the lines lean, the ripples on their way out, and the hill of the
/// contours. Only `awake` is observed, so touches don't re-render the chat:
/// it wakes the contours' clock, which sleeps once the ground is still.
@Observable
final class LiveTouch {
    /// Whether the contours are still moving.
    var awake = false
    @ObservationIgnored private var hill = SIMD3<Float>(0, 0, 0)
    @ObservationIgnored private var hillGoal = SIMD3<Float>(0, 0, 0)
    @ObservationIgnored private var hillFrame: Date?
    @ObservationIgnored private var swellUntil: Date?

    /// Where the finger or pointer is, in the paper's points.
    @ObservationIgnored var target: CGPoint?
    /// Where the lines lean now, easing after `target`.
    @ObservationIgnored var lean: CGPoint?
    @ObservationIgnored var ripples: [(at: CGPoint, start: Date)] = []
    @ObservationIgnored var size: CGSize = .zero
    @ObservationIgnored var appeared: Date?
    @ObservationIgnored var lastFrame: Date?
    @ObservationIgnored private var release: Date?

    func press(_ point: CGPoint, ripple: Bool) {
        target = point
        release = nil
        if ripple { self.ripple(at: point) }
        hillGoal = SIMD3(Float(point.x), Float(point.y), 1)
        swellUntil = nil
        wake()
    }

    func lift() {
        release = Date().addingTimeInterval(0.7)
        hillGoal.z = 0
        wake()
    }

    private func wake() {
        if !awake { awake = true }
    }

    func ripple(at point: CGPoint) {
        ripples.append((point, Date()))
        if ripples.count > 5 { ripples.removeFirst() }
    }

    func pulse(_ pulse: LivePulse) {
        guard size != .zero else { return }
        let at = CGPoint(x: size.width * (pulse.outgoing ? 0.8 : 0.2), y: size.height - 90)
        ripple(at: at)
        // The ground swells where the message is, unless a finger is down.
        if target == nil {
            hillGoal = SIMD3(Float(at.x), Float(at.y), 1)
            swellUntil = Date().addingTimeInterval(0.7)
            wake()
        }
    }

    /// The contours' hill now: easing after the finger as on the site (the
    /// place at a rate of 9 a second, the height at 3.5), and jumping to the
    /// finger while it's flat, so it rises where you touch.
    func hill(at now: Date) -> SIMD3<Float> {
        if let until = swellUntil, now >= until {
            swellUntil = nil
            hillGoal.z = 0
        }
        let dt = Float(min(max(now.timeIntervalSince(hillFrame ?? now), 0), 0.1))
        hillFrame = now
        let follow = 1 - exp(-dt * 9)
        let rise = 1 - exp(-dt * 3.5)
        if hill.z < 0.02 {
            hill.x = hillGoal.x
            hill.y = hillGoal.y
        }
        let dx = hillGoal.x - hill.x
        let dy = hillGoal.y - hill.y
        let dz = hillGoal.z - hill.z
        hill.x += dx * follow
        hill.y += dy * follow
        hill.z += dz * rise
        if abs(dx) < 0.3, abs(dy) < 0.3, abs(dz) < 0.002, swellUntil == nil {
            hill.z = hillGoal.z
            hillFrame = nil
            DispatchQueue.main.async { [weak self] in self?.awake = false }
        }
        return hill
    }

    /// Lets go of the finger once it has been up a moment.
    fileprivate func settle(_ now: Date) {
        if let release, now >= release {
            target = nil
            self.release = nil
        }
        ripples.removeAll { now.timeIntervalSince($0.start) > 1.8 }
    }
}

/// Draws a live paper. Still when motion is reduced, in Low Power Mode, or
/// as a small swatch.
struct LivePaperView: View {
    let paper: LivePaper
    let color: Color
    let strength: Double
    var touch: LiveTouch
    var still = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let paused = still || reduceMotion || ProcessInfo.processInfo.isLowPowerModeEnabled
        Group {
            switch paper {
            case .arcs:
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: paused)) { timeline in
                    Canvas { context, size in
                        let now = paused ? Date(timeIntervalSinceReferenceDate: 40) : timeline.date
                        drawArcs(&context, size: size, now: now, paused: paused)
                    }
                }
            case .contours:
                // Still ground costs nothing: the clock runs only while the
                // hill moves.
                // Per pixel on the GPU (Contours.metal), as the site's WebGL
                // shader does. Still ground costs nothing: the clock runs
                // only while the hill moves.
                TimelineView(.animation(paused: paused || !touch.awake)) { timeline in
                    let hill = paused ? SIMD3<Float>(0, 0, 0) : touch.hill(at: timeline.date)
                    Rectangle()
                        .fill(.white)
                        .colorEffect(ShaderLibrary.contours(
                            .float3(hill.x, hill.y, hill.z),
                            .color(color.opacity(min(strength * 3.6, 1))),
                            .color(color.opacity(min(strength * 3.6, 1))),
                            .float(Float(displayScale))
                        ))
                }
                .background {
                    GeometryReader { geometry in
                        Color.clear
                            .onAppear { touch.size = geometry.size }
                            .onChange(of: geometry.size) { _, size in touch.size = size }
                    }
                }
            }
        }
        #if os(iOS)
        .background { if !still { TouchObserver(touch: touch) } }
        #else
        .background { if !still { PointerObserver(touch: touch) } }
        #endif
    }

    // MARK: - The frame's state

    private struct Frame {
        var t: Double
        var intro: Double
        var lean: CGPoint
        var leaning: Bool
        var rings: [(at: CGPoint, radius: Double, amp: Double)]
    }

    /// Moves the lean after the finger (or a slow drift), and ages the ripples.
    private func frame(size: CGSize, now: Date, paused: Bool, drift: (Double) -> CGPoint) -> Frame {
        let t = now.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 10_000)
        touch.size = size
        if paused {
            return Frame(t: t, intro: 1, lean: drift(t), leaning: false, rings: [])
        }
        touch.settle(now)
        let appeared = touch.appeared ?? now
        touch.appeared = appeared
        let dt = min(max(now.timeIntervalSince(touch.lastFrame ?? now), 0), 0.1)
        touch.lastFrame = now
        let goal = touch.target ?? drift(t)
        var lean = touch.lean ?? goal
        // The same easing at any frame rate: about 8% of the way per 60th.
        let ease = 1 - pow(1 - (touch.target == nil ? 0.075 : 0.14), dt * 60)
        lean.x += (goal.x - lean.x) * ease
        lean.y += (goal.y - lean.y) * ease
        touch.lean = lean
        let introAge = now.timeIntervalSince(appeared) / 1.4
        let intro = 1 - pow(1 - min(introAge, 1), 3)
        let reach = max(size.width, size.height)
        let rings = touch.ripples.map { ripple -> (CGPoint, Double, Double) in
            let age = now.timeIntervalSince(ripple.start)
            return (ripple.at, age * reach * 0.75, 30 * exp(-age * 2.4))
        }
        return Frame(t: t, intro: intro, lean: lean, leaning: touch.target != nil, rings: rings.map { (at: $0.0, radius: $0.1, amp: $0.2) })
    }

    // MARK: - Arcs

    private func drawArcs(_ context: inout GraphicsContext, size: CGSize, now: Date, paused: Bool) {
        let (w, h) = (size.width, size.height)
        guard w > 0, h > 0 else { return }
        let f = frame(size: size, now: now, paused: paused) { t in
            CGPoint(x: w / 2 + sin(t * 0.21) * w * 0.32, y: h * 0.42 + cos(t * 0.16) * h * 0.18)
        }
        let centre = CGPoint(x: w / 2, y: h * 1.16)
        let maxRadius = hypot(w, h) * 1.05
        var radii: [Double] = []
        var r = h * 0.22
        var gap = 9.0
        while r < maxRadius {
            radii.append(r)
            r += gap
            gap *= 1.045
        }
        let sigma = min(w, h) * 0.2
        let leanRadius = hypot(f.lean.x - centre.x, f.lean.y - centre.y)
        let span = Double.pi / 2 * f.intro
        let breathe = paused ? 0 : f.t * 0.4
        for (i, r0) in radii.enumerated() {
            let radius = r0 + sin(breathe + Double(i) * 0.33) * 1.6
            let near = exp(-pow(radius - leanRadius, 2) / (2 * pow(sigma * 0.35, 2)))
            let next = i + 1 < radii.count ? radii[i + 1] : r0 + 20
            let chosen = f.leaning && leanRadius >= r0 && leanRadius < next
            let steps = max(48, Int(Double.pi * radius / 12))
            var path = Path()
            for k in 0...steps {
                let a = Double.pi * 1.5 - span + 2 * span * Double(k) / Double(steps)
                var x = centre.x + cos(a) * radius
                var y = centre.y + sin(a) * radius
                let dx = f.lean.x - x
                let dy = f.lean.y - y
                let pull = 0.42 * exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
                x += dx * pull
                y += dy * pull
                for ring in f.rings {
                    let rx = x - ring.at.x
                    let ry = y - ring.at.y
                    let d = max(hypot(rx, ry), 1)
                    let push = ring.amp * exp(-pow(d - ring.radius, 2) / 3200)
                    x += rx / d * push
                    y += ry / d * push
                }
                if k == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            let depth = 1 - Double(i) / Double(radii.count)
            let opacity = chosen ? strength * 3.2 : strength * (0.45 + depth * 0.55 + near * 1.6)
            context.stroke(path, with: .color(color.opacity(min(opacity, 1))), lineWidth: chosen ? 1.6 : 1)
        }
    }
}

#if os(iOS)
/// Watches touches anywhere over the paper, even through the chat scrolling
/// on top of it, without taking them: a recognizer on the window that never
/// recognizes and never cancels or delays a touch.
private struct TouchObserver: UIViewRepresentable {
    let touch: LiveTouch

    func makeUIView(context: Context) -> ObserverView {
        let view = ObserverView()
        view.isUserInteractionEnabled = false
        view.touch = touch
        return view
    }

    func updateUIView(_ view: ObserverView, context: Context) {
        view.touch = touch
    }

    final class ObserverView: UIView {
        var touch: LiveTouch?
        private var recognizer: Watcher?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let recognizer {
                recognizer.view?.removeGestureRecognizer(recognizer)
                self.recognizer = nil
            }
            guard let window else { return }
            let watcher = Watcher(target: nil, action: nil)
            watcher.owner = self
            watcher.cancelsTouchesInView = false
            watcher.delaysTouchesBegan = false
            watcher.delaysTouchesEnded = false
            watcher.delegate = watcher
            window.addGestureRecognizer(watcher)
            recognizer = watcher
        }
    }

    final class Watcher: UIGestureRecognizer, UIGestureRecognizerDelegate {
        weak var owner: ObserverView?

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            report(touches, began: true)
        }

        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
            report(touches, began: false)
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
            owner?.touch?.lift()
            state = .failed
        }

        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
            owner?.touch?.lift()
            state = .failed
        }

        private func report(_ touches: Set<UITouch>, began: Bool) {
            guard let owner, let first = touches.first else { return }
            let point = first.location(in: owner)
            guard owner.bounds.contains(point) else { return }
            owner.touch?.press(point, ripple: began)
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }
    }
}
#else
/// Follows the pointer over the paper, even under the chat on top of it, and
/// ripples on a click, without taking any event.
private struct PointerObserver: NSViewRepresentable {
    let touch: LiveTouch

    func makeNSView(context: Context) -> ObserverView {
        let view = ObserverView()
        view.touch = touch
        return view
    }

    func updateNSView(_ view: ObserverView, context: Context) {
        view.touch = touch
    }

    final class ObserverView: NSView {
        var touch: LiveTouch?
        private var monitor: Any?

        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        override var isFlipped: Bool { true }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let monitor { NSEvent.removeMonitor(monitor) }
            monitor = nil
            guard window != nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .leftMouseDown, .leftMouseDragged]) { [weak self] event in
                self?.handle(event)
                return event
            }
            window?.acceptsMouseMovedEvents = true
        }

        private func handle(_ event: NSEvent) {
            guard let touch, event.window === window else { return }
            let point = convert(event.locationInWindow, from: nil)
            guard bounds.contains(point) else {
                if touch.target != nil { touch.lift() }
                return
            }
            touch.press(point, ripple: event.type == .leftMouseDown)
        }

        deinit {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }
}
#endif
