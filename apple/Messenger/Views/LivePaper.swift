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
    /// Contour lines, like a hiking map, over slowly shifting hills.
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
/// where the lines lean, and the ripples on their way out. A plain class,
/// not observed: the paper redraws on its own clock, and touches shouldn't
/// re-render the chat.
final class LiveTouch {
    /// Where the finger or pointer is, in the paper's points.
    var target: CGPoint?
    /// Where the lines lean now, easing after `target`.
    var lean: CGPoint?
    var ripples: [(at: CGPoint, start: Date)] = []
    var size: CGSize = .zero
    var appeared: Date?
    var lastFrame: Date?
    private var release: Date?

    func press(_ point: CGPoint, ripple: Bool) {
        target = point
        release = nil
        if ripple { self.ripple(at: point) }
    }

    func lift() {
        release = Date().addingTimeInterval(0.7)
    }

    func ripple(at point: CGPoint) {
        ripples.append((point, Date()))
        if ripples.count > 5 { ripples.removeFirst() }
    }

    func pulse(_ pulse: LivePulse) {
        guard size != .zero else { return }
        ripple(at: CGPoint(x: size.width * (pulse.outgoing ? 0.8 : 0.2), y: size.height - 90))
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

    var body: some View {
        let paused = still || reduceMotion || ProcessInfo.processInfo.isLowPowerModeEnabled
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: paused)) { timeline in
            Canvas { context, size in
                let now = paused ? Date(timeIntervalSinceReferenceDate: 40) : timeline.date
                switch paper {
                case .arcs: drawArcs(&context, size: size, now: now, paused: paused)
                case .contours: drawContours(&context, size: size, now: now, paused: paused)
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

    // MARK: - Contours

    private func drawContours(_ context: inout GraphicsContext, size: CGSize, now: Date, paused: Bool) {
        let (w, h) = (size.width, size.height)
        guard w > 0, h > 0 else { return }
        let f = frame(size: size, now: now, paused: paused) { t in
            CGPoint(x: w * (0.5 + 0.3 * sin(t * 0.13)), y: h * (0.45 + 0.25 * cos(t * 0.11)))
        }
        let field = ContourField(t: paused ? 40 : f.t, size: size, lean: f.lean, leaning: f.leaning ? 1 : 0.35, rings: f.rings)
        let step = 11.0
        let columns = Int(ceil(w / step)) + 1
        let rows = Int(ceil(h / step)) + 1
        var values = [Double](repeating: 0, count: columns * rows)
        for row in 0..<rows {
            for column in 0..<columns {
                values[row * columns + column] = field.value(Double(column) * step, Double(row) * step)
            }
        }
        let base = -2.0
        let gap = 0.17
        var lines = Path()
        var index = Path()
        var near = Path()
        let reach = 95.0
        for row in 0..<(rows - 1) {
            for column in 0..<(columns - 1) {
                let a = values[row * columns + column]           // top left
                let b = values[row * columns + column + 1]       // top right
                let c = values[(row + 1) * columns + column + 1] // bottom right
                let d = values[(row + 1) * columns + column]     // bottom left
                let low = min(a, b, c, d)
                let high = max(a, b, c, d)
                var level = Int(ceil((low - base) / gap))
                let x = Double(column) * step
                let y = Double(row) * step
                while base + Double(level) * gap <= high {
                    let value = base + Double(level) * gap
                    for (p, q) in marchingSquares(a, b, c, d, value) {
                        let from = CGPoint(x: x + p.x * step, y: y + p.y * step)
                        let to = CGPoint(x: x + q.x * step, y: y + q.y * step)
                        let mid = CGPoint(x: (from.x + to.x) / 2, y: (from.y + to.y) / 2)
                        if f.leaning, hypot(mid.x - f.lean.x, mid.y - f.lean.y) < reach {
                            near.move(to: from)
                            near.addLine(to: to)
                        } else if level % 5 == 0 {
                            index.move(to: from)
                            index.addLine(to: to)
                        } else {
                            lines.move(to: from)
                            lines.addLine(to: to)
                        }
                    }
                    level += 1
                }
            }
        }
        let round = StrokeStyle(lineWidth: 0.9, lineCap: .round)
        context.stroke(lines, with: .color(color.opacity(strength * 0.95)), style: round)
        context.stroke(index, with: .color(color.opacity(min(strength * 1.9, 1))), style: StrokeStyle(lineWidth: 1.3, lineCap: .round))
        context.stroke(near, with: .color(color.opacity(min(strength * 3, 1))), style: StrokeStyle(lineWidth: 1.3, lineCap: .round))
    }
}

/// Hills and valleys that wander, a bump under the finger, and the rings of
/// ripples: the height whose contours are drawn.
private struct ContourField {
    let t: Double
    let size: CGSize
    let lean: CGPoint
    let leaning: Double
    let rings: [(at: CGPoint, radius: Double, amp: Double)]

    /// Three wandering hills, as (x, y, height, spread) in points.
    private var hills: [(Double, Double, Double, Double)] {
        let (w, h) = (size.width, size.height)
        return [
            (w * (0.3 + 0.18 * sin(t * 0.07)), h * (0.3 + 0.12 * cos(t * 0.05)), 1.3, w * 0.42),
            (w * (0.75 + 0.15 * cos(t * 0.06)), h * (0.65 + 0.15 * sin(t * 0.045)), -1.1, w * 0.5),
            (w * (0.45 + 0.25 * sin(t * 0.04 + 2)), h * (0.85 + 0.1 * cos(t * 0.08)), 0.9, w * 0.35),
        ]
    }

    func value(_ x: Double, _ y: Double) -> Double {
        let u = x / 230
        let v = y / 230
        var z = 0.45 * sin(u * 1.3 + t * 0.05) * cos(v * 1.1 - t * 0.04)
        z += 0.35 * sin((u + v) * 0.8 + t * 0.03)
        for (hx, hy, height, spread) in hills {
            let d2 = (x - hx) * (x - hx) + (y - hy) * (y - hy)
            z += height * exp(-d2 / (2 * spread * spread))
        }
        let ld2 = (x - lean.x) * (x - lean.x) + (y - lean.y) * (y - lean.y)
        z += 0.9 * leaning * exp(-ld2 / (2 * 75 * 75))
        for ring in rings {
            let d = hypot(x - ring.at.x, y - ring.at.y)
            z += ring.amp / 30 * 0.5 * exp(-pow(d - ring.radius, 2) / (2 * 28 * 28))
        }
        return z
    }
}

/// The segments where one cell crosses `level`, in the cell's unit square.
/// Corners: a top left, b top right, c bottom right, d bottom left.
private func marchingSquares(_ a: Double, _ b: Double, _ c: Double, _ d: Double, _ level: Double) -> [(CGPoint, CGPoint)] {
    var index = 0
    if a > level { index |= 8 }
    if b > level { index |= 4 }
    if c > level { index |= 2 }
    if d > level { index |= 1 }
    if index == 0 || index == 15 { return [] }
    func t(_ p: Double, _ q: Double) -> Double { (level - p) / (q - p) }
    let top = CGPoint(x: t(a, b), y: 0)
    let right = CGPoint(x: 1, y: t(b, c))
    let bottom = CGPoint(x: t(d, c), y: 1)
    let left = CGPoint(x: 0, y: t(a, d))
    let centreHigh = (a + b + c + d) / 4 > level
    switch index {
    case 1, 14: return [(left, bottom)]
    case 2, 13: return [(bottom, right)]
    case 3, 12: return [(left, right)]
    case 4, 11: return [(top, right)]
    case 6, 9: return [(top, bottom)]
    case 7, 8: return [(left, top)]
    case 5: return centreHigh ? [(left, top), (bottom, right)] : [(left, bottom), (top, right)]
    case 10: return centreHigh ? [(left, bottom), (top, right)] : [(left, top), (bottom, right)]
    default: return []
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
