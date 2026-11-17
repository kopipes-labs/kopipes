"""Generates the docs diagrams as SVG, in the style of the payments example's diagrams.

Run from the repository root: `python3 docs/images/diagrams.py`. It rewrites the SVGs next to it.
"""
import sys
from html import escape
from pathlib import Path

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent

NAVY = "#141A2E"
GREY = "#5B6270"
MUTED = "#8A909C"
LINE = "#3D4657"
PANEL = "#F5F3EE"
PANEL_LINE = "#E2DED4"
PURPLE = "#8B5CF6"
PINK = "#E5489B"
PINK_FILL = "#FCEFF6"
ORANGE = "#C4511A"
ORANGE_FILL = "#FBF1EA"
PURPLE_FILL = "#F4F0FE"

STYLE = f"""
  <style>
    .sans {{ font-family: Helvetica, Arial, sans-serif; }}
    .mono {{ font-family: SFMono-Regular, Menlo, Consolas, monospace; }}
    .kicker {{ font-family: SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; font-weight: 700; letter-spacing: 2px; }}
    .title {{ font-family: Helvetica, Arial, sans-serif; font-size: 16px; font-weight: 700; fill: {NAVY}; }}
    .sub {{ font-family: Helvetica, Arial, sans-serif; font-size: 13px; fill: {GREY}; }}
    .code {{ font-family: SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; fill: {NAVY}; }}
    .label {{ font-family: Helvetica, Arial, sans-serif; font-size: 13px; fill: {NAVY}; }}
    .note {{ font-family: Helvetica, Arial, sans-serif; font-size: 13px; font-style: italic; fill: {GREY}; }}
  </style>"""


def defs():
    out = ["<defs>"]
    for name, color in [("a", NAVY), ("p", PINK), ("o", ORANGE), ("v", PURPLE)]:
        out.append(
            f'<marker id="{name}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" '
            f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{color}"/></marker>'
        )
    out.append("</defs>")
    return "".join(out)


class Svg:
    def __init__(self, w, h, kicker, title):
        self.w, self.h = w, h
        self.parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
            f'role="img" aria-label="{escape(title)}">',
            STYLE,
            defs(),
            f'<rect width="{w}" height="{h}" fill="#FFFFFF"/>',
            f'<text x="40" y="44" class="kicker" fill="{ORANGE}">{escape(kicker)}</text>',
            f'<text x="40" y="80" class="sans" font-size="28" font-weight="700" fill="{NAVY}">{escape(title)}</text>',
        ]

    def add(self, s):
        self.parts.append(s)

    def panel(self, x, y, w, h, kicker=None, color=PANEL_LINE, fill=PANEL, dash=False, r=14, kx=None):
        d = ' stroke-dasharray="6 6"' if dash else ""
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" stroke="{color}" stroke-width="2"{d}/>')
        if kicker:
            self.add(f'<text x="{kx if kx else x + 20}" y="{y + 28}" class="kicker" fill="{color}">{escape(kicker)}</text>')

    def hexagon(self, cx, cy, w, h, d, color, fill, dash=False, sw=2.5):
        pts = [(cx - w / 2, cy), (cx - w / 2 + d, cy - h / 2), (cx + w / 2 - d, cy - h / 2),
               (cx + w / 2, cy), (cx + w / 2 - d, cy + h / 2), (cx - w / 2 + d, cy + h / 2)]
        p = " ".join(f"{a:.1f},{b:.1f}" for a, b in pts)
        dd = ' stroke-dasharray="7 7"' if dash else ""
        self.add(f'<polygon points="{p}" fill="{fill}" stroke="{color}" stroke-width="{sw}"{dd}/>')

    def node(self, x, y, w, h, kicker, title, lines=(), accent=PURPLE, fill="#FFFFFF", code=(), shape="rect", dash=False):
        if shape == "rect":
            dd = ' stroke-dasharray="5 5"' if dash else ""
            self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" stroke="{accent}" stroke-width="2"{dd}/>')
            tx = x + 16
        else:  # pointed both ends, like an ESG in the payments diagram
            d = 22
            pts = [(x, y + h / 2), (x + d, y), (x + w - d, y), (x + w, y + h / 2), (x + w - d, y + h), (x + d, y + h)]
            p = " ".join(f"{a:.1f},{b:.1f}" for a, b in pts)
            self.add(f'<polygon points="{p}" fill="{fill}" stroke="{accent}" stroke-width="2"/>')
            tx = x + d + 8
        ty = y + 24
        if kicker:
            self.add(f'<text x="{tx}" y="{ty}" class="kicker" fill="{accent}">{escape(kicker)}</text>')
            ty += 22
        if title:
            self.add(f'<text x="{tx}" y="{ty}" class="title">{escape(title)}</text>')
            ty += 20
        for line in lines:
            self.add(f'<text x="{tx}" y="{ty}" class="sub">{escape(line)}</text>')
            ty += 17
        for line in code:
            self.add(f'<text x="{tx}" y="{ty}" class="code">{escape(line)}</text>')
            ty += 16

    def arrow(self, d, color=NAVY, marker="a", dash=False, both=False, width=1.8):
        dd = ' stroke-dasharray="6 5"' if dash else ""
        start = f' marker-start="url(#{marker})"' if both else ""
        self.add(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}"{dd} marker-end="url(#{marker})"{start}/>')

    def label(self, x, y, text, anchor="middle", cls="label", fill=None, bg=True):
        f = f' fill="{fill}"' if fill else ""
        if bg:
            wpx = len(text) * 6.6 + 10
            bx = x - wpx / 2 if anchor == "middle" else (x - 5 if anchor == "start" else x - wpx + 5)
            self.add(f'<rect x="{bx:.1f}" y="{y - 13}" width="{wpx:.1f}" height="18" fill="#FFFFFF" opacity="0.92" rx="3"/>')
        self.add(f'<text x="{x}" y="{y}" text-anchor="{anchor}" class="{cls}"{f}>{escape(text)}</text>')

    def text(self, x, y, text, cls="label", anchor="start", fill=None):
        f = f' fill="{fill}"' if fill else ""
        self.add(f'<text x="{x}" y="{y}" text-anchor="{anchor}" class="{cls}"{f}>{escape(text)}</text>')

    def caption(self, y, bold, rest):
        self.add(f'<rect x="40" y="{y - 22}" width="5" height="32" fill="{PURPLE}"/>')
        self.add(f'<rect x="40" y="{y - 6}" width="5" height="16" fill="{ORANGE}"/>')
        self.add(f'<text x="62" y="{y}" class="sans" font-size="17" fill="{NAVY}"><tspan font-weight="700">'
                 f'{escape(bold)}</tspan> {escape(rest)}</text>')

    def save(self, name):
        self.add("</svg>")
        (OUT / name).write_text("\n".join(self.parts) + "\n")


# 1. How a batch flows ---------------------------------------------------------------------------------------------
def batch_flow():
    s = Svg(1160, 530, "KOPIPES · HOW A BATCH FLOWS", "From a Lambda batch to AWS, one unit of work at a time")
    y, h = 140, 110
    s.node(40, y, 180, h, "AWS EVENT", "Lambda batch", ["Kinesis · SQS · SNS", "DynamoDB stream · S3"], accent=ORANGE)
    s.node(260, y, 180, h, "ADAPTER", "Decode", ["records → units of work", "with your EventCodec"])
    s.node(480, y, 180, h, "ASSEMBLER", "Fan out", ["the same flow to", "every pipeline"])
    s.node(700, 112, 210, 74, "PIPELINE · COLLECT", "flavor + your rules", accent=PURPLE, fill=PURPLE_FILL)
    s.node(700, 204, 210, 74, "PIPELINE · EVALUATE", "flavor + your rules", accent=PURPLE, fill=PURPLE_FILL)
    s.node(950, y, 170, h, "SINKS", "Write, publish", ["DynamoDB · S3", "EventBridge"], accent=PINK)
    my = y + h / 2
    s.arrow(f"M220,{my} H258")
    s.arrow(f"M440,{my} H478")
    s.arrow(f"M660,{my} H675 V149 H698")
    s.arrow(f"M660,{my} H675 V241 H698")
    s.arrow(f"M910,149 H928 V{my} H948")
    s.arrow(f"M910,241 H928 V{my}")
    # faults
    s.node(700, 340, 210, 92, "FAULT MANAGER", "FaultEvent", ["error + snapshot +", "the original record"], accent=PINK, fill=PINK_FILL)
    s.node(950, 340, 170, 92, "FAULT MONITOR", "Stored in S3", ["until the bug is fixed"], accent=PINK)
    s.arrow("M805,280 V338", color=PINK, marker="p", dash=True)
    s.label(805, 315, "a record fails", fill=PINK)
    s.arrow("M910,386 H948", color=PINK, marker="p")
    s.arrow("M1035,432 V462 H130 V252", color=PINK, marker="p", dash=True)
    s.label(560, 467, "resubmit-events sends the record back to the function that failed it", fill=PINK)
    s.caption(512, "One bad record never blocks the batch.", "It becomes a fault; the rest of the batch carries on.")
    s.save("BatchFlow.svg")


# 2. Control service -----------------------------------------------------------------------------------------------
def control_service():
    s = Svg(1100, 620, "PATTERN · CONTROL SERVICE", "Record first, then act from the table's stream")
    s.panel(40, 110, 1020, 240, "CONTROL SERVICE", color=PURPLE, fill=PANEL)
    y, h = 160, 130
    s.node(80, y, 260, h, "λ LISTENER", "1 · Collect", ["saves each event it hears,", "under its own id"], code=["CollectPipeline"])
    s.node(420, y, 260, h, "DYNAMODB", "Events table", ["the events microstore:", "what the service has heard"], code=["pk · sk · ttl · stream"], accent=ORANGE)
    s.node(760, y, 260, h, "λ TRIGGER", "2 · Correlate  3 · Evaluate", ["groups events by entity, runs", "the rules, emits new events"], code=["CorrelatePipeline", "EvaluatePipeline"])
    s.arrow("M340,205 H418")
    s.label(379, 198, "put")
    s.arrow("M680,205 H758")
    s.label(719, 198, "stream")
    s.arrow("M758,262 H682")
    s.label(720, 280, "correlate", bg=False)
    # hub
    s.panel(280, 420, 540, 120, "EVENT HUB", color=PINK, fill=PINK_FILL)
    s.node(310, 455, 220, 70, "KINESIS", "Channel s1", accent=PINK)
    s.node(570, 455, 220, 70, "EVENTBRIDGE", "Bus", accent=PINK)
    s.arrow("M570,490 H532", color=PINK, marker="p")
    s.label(551, 512, "rule", bg=False, fill=PINK)
    s.arrow("M420,455 V385 H210 V292")
    s.label(315, 380, "events in")
    s.arrow("M890,292 V385 H680 V453")
    s.label(785, 380, "higher-order events out")
    s.caption(590, "Write first, act later.", "A redelivered event writes the same item again, and no rule acts on an unrecorded fact.")
    s.save("ControlService.svg")


# 3. Event hub -----------------------------------------------------------------------------------------------------
def event_hub():
    s = Svg(1120, 560, "PATTERN · EVENT HUB", "Services publish to the bus and read from a channel")
    for i, (k, t) in enumerate([("PUBLISH", "Control service"), ("PUBLISH", "BFF trigger"), ("PUBLISH", "Ingress gateways")]):
        s.node(40, 140 + i * 100, 190, 70, k, t, accent=PURPLE)
    s.panel(290, 110, 520, 360, "EVENT HUB · EventHub construct", color=PINK, fill=PINK_FILL)
    s.node(320, 240, 190, 90, "EVENTBRIDGE", "Bus", ["routes by rules"], accent=PINK)
    s.node(590, 150, 190, 90, "KINESIS", "Channel s1", ["ordered per key"], accent=PINK)
    s.node(590, 360, 190, 90, "ARCHIVE · LOGS", "Archive, logs", ["replay · debugging"], accent=PINK)
    for i in range(3):
        yy = 175 + i * 100
        s.arrow(f"M230,{yy} H262 V285 H318")
    s.arrow("M510,265 H550 V195 H588", color=PINK, marker="p")
    s.arrow("M415,330 V405 H588", color=PINK, marker="p")
    s.label(530, 230, "rule", bg=False, fill=PINK)
    s.node(880, 150, 200, 90, "CONSUME", "Listeners", ["each service reads", "the channel"], accent=PURPLE)
    s.node(880, 260, 200, 90, "EventLake", "Event lake", ["every event but faults", "Firehose → S3"], accent=ORANGE)
    s.node(880, 370, 200, 90, "FaultMonitor", "Fault monitor", ["faults only", "Firehose → S3 · SNS"], accent=ORANGE)
    s.arrow("M780,195 H878")
    s.arrow("M510,305 H878", color=PINK, marker="p")
    s.arrow("M850,305 V415 H878", color=PINK, marker="p")
    s.label(700, 297, "rules", bg=False, fill=PINK)
    s.caption(530, "Services never call each other.", "The hub routes every event; each service reads at its own pace.")
    s.save("EventHub.svg")


# 4. Autonomous system ---------------------------------------------------------------------------------------------
def autonomous_system():
    s = Svg(1120, 600, "ARCHITECTURE · AUTONOMOUS SYSTEM", "A system is a set of autonomous subsystems")
    s.node(110, 104, 220, 50, None, None, accent=PANEL_LINE)
    s.text(130, 135, "USER", cls="kicker", fill=PURPLE)
    s.text(188, 135, "Customer app", cls="title")
    s.panel(40, 180, 1040, 330, "AUTONOMOUS SYSTEM", color=ORANGE, fill=ORANGE_FILL)
    subs = [(220, "Payments", "submits and settles"), (580, "Accounts", "owns customer accounts"), (940, "Notifications", "tells customers")]
    for cx, name, what in subs:
        s.hexagon(cx, 345, 260, 220, 55, PURPLE, "#FFFFFF")
        s.text(cx, 300, "SUBSYSTEM", cls="kicker", anchor="middle", fill=PURPLE)
        s.text(cx, 325, name, cls="title", anchor="middle")
        s.text(cx, 347, what, cls="sub", anchor="middle")
        s.text(cx, 378, "its own event hub", cls="code", anchor="middle")
        s.text(cx, 396, "its own services", cls="code", anchor="middle")
        s.text(cx, 414, "its own account", cls="code", anchor="middle")
    s.arrow("M220,154 V233")
    s.label(220, 196, "API")
    s.arrow("M352,345 H446", color=PINK, marker="p")
    s.label(400, 330, "external", fill=PINK, bg=False)
    s.label(400, 368, "events", fill=PINK, bg=False)
    s.arrow("M712,345 H806", color=PINK, marker="p")
    s.label(760, 330, "external", fill=PINK, bg=False)
    s.label(760, 368, "events", fill=PINK, bg=False)
    s.text(560, 490, "Subsystems share only external events and APIs, with stable, versioned contracts.", cls="note", anchor="middle")
    s.caption(565, "Subsystems are bulkheads.", "A failure or a change inside one stays inside it.")
    s.save("AutonomousSystem.svg")


# 5. Autonomous subsystem ------------------------------------------------------------------------------------------
def autonomous_subsystem():
    s = Svg(1200, 720, "ARCHITECTURE · AUTONOMOUS SUBSYSTEM", "Gateways at the edges, the core in the middle")
    s.node(480, 104, 240, 50, None, None, accent=PANEL_LINE)
    s.text(500, 135, "USER", cls="kicker", fill=PURPLE)
    s.text(556, 135, "Web or mobile app", cls="title")
    s.hexagon(600, 410, 900, 480, 120, PURPLE, PANEL)
    s.text(250, 225, "INGRESS · PRODUCE", cls="kicker", fill=PURPLE)
    s.text(950, 225, "EGRESS · CONSUME", cls="kicker", fill=PURPLE, anchor="end")
    s.hexagon(600, 410, 380, 400, 70, PURPLE, "none", dash=True, sw=2)
    s.text(482, 252, "CORE", cls="kicker", fill=PURPLE)
    s.node(455, 262, 290, 66, "BFF", "API + its own read model", accent=PURPLE)
    s.node(440, 378, 320, 64, "EVENT HUB", "Bus · channels · lake · faults", accent=PINK, fill=PINK_FILL)
    s.node(455, 492, 290, 66, "CONTROL SERVICE", "Runs the business process", accent=PURPLE)
    s.arrow("M600,154 V260", both=True)
    s.arrow("M600,330 V376", both=True)
    s.arrow("M600,444 V490", both=True)
    s.text(612, 357, "publish · consume", cls="sub")
    s.text(612, 471, "consume · publish", cls="sub")
    # ingress
    s.node(215, 290, 190, 76, "INGRESS ESG", "outside → events", accent=PINK, shape="hex", code=["TranslatePipeline"])
    s.node(220, 460, 190, 76, "INGRESS ESG", "outside → events", accent=PINK, shape="hex", code=["TranslatePipeline"])
    s.node(795, 290, 190, 76, "EGRESS ESG", "events → calls", accent=PINK, shape="hex", code=["ActionPipeline"])
    s.node(790, 460, 190, 76, "EGRESS ESG", "events → outside", accent=ORANGE, shape="hex", code=["CdcPipeline"])
    s.node(20, 300, 140, 56, "LEGACY", "Core system", accent="#AAB2C1", dash=True)
    s.node(20, 470, 140, 56, "SAAS", "Third party", accent="#AAB2C1", dash=True)
    s.node(1040, 300, 140, 56, "SAAS", "Third party", accent="#AAB2C1", dash=True)
    s.node(1040, 470, 140, 56, "SUBSYSTEM", "Another one", accent="#AAB2C1", dash=True)
    s.arrow("M160,328 H213")
    s.arrow("M160,498 H218")
    s.arrow("M985,328 H1038")
    s.arrow("M980,498 H1038")
    s.text(600, 626, "Every gateway publishes to or consumes from the hub; arrows to it are left out.", cls="note", anchor="middle")
    s.caption(690, "The ports are the events.", "Ingress gateways turn the outside world into facts, the core reacts, egress gateways act.")
    s.save("AutonomousSubsystem.svg")


# 6. Regional health check -----------------------------------------------------------------------------------------
def regional_health_check():
    s = Svg(1290, 860, "PATTERN · REGIONAL HEALTH CHECK", "Is this region healthy enough to take traffic?")
    s.panel(30, 110, 1230, 300, "TRACER LOOP · a synthetic event crosses every service the region uses", color=PURPLE)
    w, h, xs = 150, 66, [55, 260, 465, 670, 875, 1080]
    r1, r2 = 160, 310
    row1 = [("SYNTHETICS", "Canary", ORANGE), ("API GATEWAY", "Check health", PURPLE), ("DYNAMODB", "Tracer table", PURPLE),
            ("LAMBDA", "Table trigger", PURPLE), ("S3", "Tracer bucket", PURPLE), ("SNS", "Topic", PURPLE)]
    row2 = [("SQS", "Queue", PURPLE), ("LAMBDA", "S3 trigger", PURPLE), ("EVENTBRIDGE", "Bus", PINK),
            ("KINESIS", "Stream", PINK), ("LAMBDA", "Kinesis trigger", PURPLE)]
    for x, (k, t, c) in zip(xs, row1):
        s.node(x, r1, w, h, k, t, accent=c)
    for x, (k, t, c) in zip(reversed(xs), row2):
        s.node(x, r2, w, h, k, t, accent=c)
    l1 = ["probe", "update", "stream", "put", "notify"]
    for i in range(5):
        x0 = xs[i] + w
        s.arrow(f"M{x0},{r1 + h / 2} H{xs[i + 1] - 2}", dash=(i == 0))
        s.label((x0 + xs[i + 1]) / 2, r1 + h / 2 - 9, l1[i], bg=False, cls="sub")
    s.arrow(f"M{xs[5] + w / 2},{r1 + h} V{r2 - 2}")
    s.label(xs[5] + w / 2 + 10, (r1 + h + r2) / 2 + 4, "deliver", anchor="start", bg=False)
    l2 = ["trigger", "put", "rule", "trigger"]
    rx = list(reversed(xs))
    for i in range(4):
        s.arrow(f"M{rx[i]},{r2 + h / 2} H{rx[i + 1] + w + 2}")
        s.label((rx[i] + rx[i + 1] + w) / 2, r2 + h / 2 - 9, l2[i], bg=False, cls="sub")
    s.arrow(f"M{xs[1] + w / 2},{r2} V{r1 + h + 30} H{xs[2] + w / 2} V{r1 + h + 2}")
    s.label((xs[1] + xs[2] + w) / 2, r1 + h + 25, "update: the loop closed", bg=False)
    # health signal
    s.panel(30, 440, 1230, 330, "HEALTH SIGNAL · CloudWatch alarms, combined by Route 53", color=PINK, fill=PINK_FILL)
    a, b, mid = 490, 680, 585
    cols = [60, 330, 640]
    s.node(cols[0], a, 220, 70, "API GATEWAY", "5XXError metric", accent=ORANGE)
    s.node(cols[1], a, 220, 70, "ALARM", "API Gateway 5XX", accent=PINK)
    s.node(cols[2], a, 230, 70, "ROUTE 53", "API health check", accent=PURPLE)
    s.node(cols[0], b, 220, 70, "DYNAMODB", "SystemErrors metric", accent=ORANGE)
    s.node(cols[1], b, 220, 70, "ALARM", "DynamoDB errors", accent=PINK)
    s.node(cols[2], b, 230, 70, "ROUTE 53", "DynamoDB health check", accent=PURPLE)
    s.node(cols[1] + 10, mid - 2, 200, 70, "COMPOSITE ALARM", "Regional alarm", accent=PINK)
    s.node(960, mid - 50, 270, 112, "ROUTE 53 · OUTPUT", "Regional health check", ["healthy only when both", "child checks are healthy"], accent=PURPLE, fill="#FFFFFF")
    for yy in (a, b):
        s.arrow(f"M{cols[0] + 220},{yy + 35} H{cols[1] - 2}")
        s.arrow(f"M{cols[1] + 220},{yy + 35} H{cols[2] - 2}")
        s.arrow(f"M{cols[2] + 230},{yy + 35} H900 V{mid + (-20 if yy == a else 20)} H958")
    s.arrow(f"M{cols[1] + 110},{a + 70} V{mid - 4}")
    s.arrow(f"M{cols[1] + 110},{b} V{mid + 70}")
    s.text(cols[1] + 222, mid + 40, "stack output: the regional alarm", cls="note")
    s.text(1095, mid + 85, "the region's health signal", cls="note", anchor="middle")
    s.caption(820, "Conservative by design.", "Server-side errors in either dependency make the region unhealthy; missing data doesn't.")
    s.save("RegionalHealthCheck.svg")


batch_flow()
control_service()
event_hub()
autonomous_system()
autonomous_subsystem()
regional_health_check()
