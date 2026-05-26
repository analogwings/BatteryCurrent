from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


OUTPUT_PATH = "docs/manual/BatteryCurrent_User_Manual_Preliminary.docx"


def set_cell_shading(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_border(cell, color="D9E2EC", size="8"):
    tc_pr = cell._tc.get_or_add_tcPr()
    borders = tc_pr.find(qn("w:tcBorders"))
    if borders is None:
        borders = OxmlElement("w:tcBorders")
        tc_pr.append(borders)
    for edge in ("top", "left", "bottom", "right"):
        tag = f"w:{edge}"
        element = borders.find(qn(tag))
        if element is None:
            element = OxmlElement(tag)
            borders.append(element)
        element.set(qn("w:val"), "single")
        element.set(qn("w:sz"), size)
        element.set(qn("w:space"), "0")
        element.set(qn("w:color"), color)


def set_table_width(table, width_dxa):
    tbl_pr = table._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(width_dxa))
    tbl_w.set(qn("w:type"), "dxa")


def set_cell_width(cell, width_dxa):
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(width_dxa))
    tc_w.set(qn("w:type"), "dxa")


def style_document(doc):
    section = doc.sections[0]
    section.top_margin = Inches(1)
    section.bottom_margin = Inches(1)
    section.left_margin = Inches(1)
    section.right_margin = Inches(1)
    section.header_distance = Inches(0.492)
    section.footer_distance = Inches(0.492)

    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)
    normal.paragraph_format.space_after = Pt(6)
    normal.paragraph_format.line_spacing = 1.25

    for style_name, size, color, before, after in [
        ("Heading 1", 16, "2E74B5", 18, 10),
        ("Heading 2", 13, "2E74B5", 14, 7),
        ("Heading 3", 12, "1F4D78", 10, 5),
    ]:
        style = styles[style_name]
        style.font.name = "Calibri"
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor.from_string(color)
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True


def add_title(doc):
    title = doc.add_paragraph()
    title.paragraph_format.space_after = Pt(4)
    run = title.add_run("BatteryCurrent User Manual")
    run.font.name = "Calibri"
    run.font.size = Pt(24)
    run.font.bold = True
    run.font.color.rgb = RGBColor.from_string("0B2545")

    subtitle = doc.add_paragraph()
    subtitle.paragraph_format.space_after = Pt(14)
    run = subtitle.add_run("Preliminary guide for test users")
    run.font.name = "Calibri"
    run.font.size = Pt(12)
    run.font.italic = True
    run.font.color.rgb = RGBColor.from_string("555555")

    note = doc.add_paragraph()
    note.paragraph_format.space_after = Pt(12)
    run = note.add_run("Draft status: ")
    run.bold = True
    note.add_run(
        "This manual is intended as a first-pass guide. Screenshot placeholders are included for later replacement or annotation."
    )


def add_bullets(doc, items):
    for item in items:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.left_indent = Inches(0.375)
        p.paragraph_format.first_line_indent = Inches(-0.188)
        p.paragraph_format.space_after = Pt(4)
        p.add_run(item)


def add_numbered(doc, items):
    for item in items:
        p = doc.add_paragraph(style="List Number")
        p.paragraph_format.left_indent = Inches(0.375)
        p.paragraph_format.first_line_indent = Inches(-0.188)
        p.paragraph_format.space_after = Pt(4)
        p.add_run(item)


def add_placeholder(doc, title, instructions):
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.allow_autofit = False
    set_table_width(table, 9360)
    cell = table.cell(0, 0)
    set_cell_width(cell, 9360)
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
    set_cell_shading(cell, "F2F4F7")
    set_cell_border(cell, "C7D2E1", "12")
    paragraph = cell.paragraphs[0]
    paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
    paragraph.paragraph_format.space_before = Pt(18)
    paragraph.paragraph_format.space_after = Pt(18)
    run = paragraph.add_run(f"[ Screenshot placeholder: {title} ]")
    run.bold = True
    run.font.color.rgb = RGBColor.from_string("1F4D78")
    paragraph.add_run("\n")
    detail = paragraph.add_run(instructions)
    detail.font.size = Pt(10)
    detail.font.color.rgb = RGBColor.from_string("555555")
    doc.add_paragraph()


def add_key_value_table(doc, rows):
    table = doc.add_table(rows=1, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.allow_autofit = False
    set_table_width(table, 9360)
    set_cell_width(table.rows[0].cells[0], 2700)
    set_cell_width(table.rows[0].cells[1], 6660)

    hdr = table.rows[0].cells
    hdr[0].text = "Item"
    hdr[1].text = "What it does"
    for cell in hdr:
        set_cell_shading(cell, "E8EEF5")
        set_cell_border(cell)
        for paragraph in cell.paragraphs:
            paragraph.runs[0].bold = True

    for key, value in rows:
        cells = table.add_row().cells
        set_cell_width(cells[0], 2700)
        set_cell_width(cells[1], 6660)
        cells[0].text = key
        cells[1].text = value
        for cell in cells:
            set_cell_border(cell)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
    doc.add_paragraph()


def add_footer(doc):
    footer = doc.sections[0].footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = footer.add_run("BatteryCurrent preliminary manual")
    run.font.size = Pt(9)
    run.font.color.rgb = RGBColor.from_string("777777")


def build_manual():
    doc = Document()
    style_document(doc)
    add_title(doc)

    doc.add_heading("1. Overview", level=1)
    doc.add_paragraph(
        "BatteryCurrent is an Android overlay utility for monitoring live battery behavior while using the phone normally. "
        "It shows charging or discharging current, battery temperature, voltage, accumulated charge or energy, battery percentage, and a live history graph."
    )
    add_bullets(doc, [
        "The foreground display is intentionally compact so it can sit over other apps.",
        "The graph popup provides detailed history, zooming, selectable secondary traces, and control buttons.",
        "Sampling is designed to be low power, updating about every 10 seconds.",
        "Battery capacity event logging is handled in the background when qualifying charge or discharge sessions occur.",
    ])

    doc.add_heading("2. Starting the App", level=1)
    doc.add_paragraph(
        "When BatteryCurrent starts, the foreground display appears on the screen and shows the current live values. "
        "On the first run it opens near the center of the display. On later runs it reopens at the last dragged position."
    )
    add_numbered(doc, [
        "Open BatteryCurrent from the app launcher.",
        "Grant the overlay permission if Android asks for it.",
        "Confirm that the foreground display appears and updates with live battery values.",
        "Tap the foreground display to open the graph popup.",
    ])

    add_placeholder(
        doc,
        "Foreground display",
        "Insert a screenshot showing the small floating overlay with time, current, temperature, voltage, energy or charge, and battery percent.",
    )

    doc.add_heading("3. Foreground Display", level=1)
    doc.add_paragraph(
        "The foreground display is the small live readout shown over other apps. It can be dragged to a convenient location unless it is locked."
    )
    add_key_value_table(doc, [
        ("Time", "Elapsed time since the current measurement session began or was reset."),
        ("Current", "Live averaged battery current in mA. Positive and negative values indicate direction of battery flow."),
        ("Temperature", "Battery temperature in degrees C or degrees F."),
        ("Voltage", "Measured battery voltage from Android battery status data."),
        ("Energy / Charge", "Accumulated mWh or mAh since reset, depending on the selected unit."),
        ("Battery", "Battery state of charge as reported by Android."),
    ])

    doc.add_heading("4. Graph Popup", level=1)
    doc.add_paragraph(
        "Tap the foreground display to open the graph popup. The popup shows the main accumulated mAh or mWh trace and an optional right-axis trace."
    )
    add_key_value_table(doc, [
        ("x button", "Closes the graph popup and returns to the foreground display."),
        ("Background", "Hides the foreground overlay while keeping the service running in the notification shade."),
        ("Stop", "Stops monitoring. Use this when you want to fully stop the app."),
        ("Lock / Unlock", "Locks or unlocks the foreground display position."),
        ("mAh / mWh", "Switches the main graph and accumulated readout between charge and energy."),
        ("Reset", "Resets the accumulated mAh or mWh value to zero and starts a new graph timeline."),
        ("degrees button", "Switches temperature display between degrees C and degrees F."),
    ])

    add_placeholder(
        doc,
        "Graph popup",
        "Insert a screenshot showing the graph popup, controls, right-axis selector, and Reset Zoom button if visible.",
    )

    doc.add_heading("5. Reading the Graph", level=1)
    doc.add_paragraph(
        "The left axis shows accumulated mAh or mWh. Rising green sections indicate increasing accumulated value; falling red sections indicate decreasing accumulated value. "
        "The right axis is selectable and can show battery percent, temperature, voltage, or current."
    )
    add_bullets(doc, [
        "Tap the grey right-axis label to cycle through Batt %, Temp, Volt, and mA.",
        "Battery percent uses a fixed 0 to 100 scale.",
        "Voltage uses a fixed 3.5 V to 4.5 V scale.",
        "Temperature and current auto-scale based on the visible data.",
        "For mA mode, positive current is blue and negative current is yellow, with a dashed zero-current reference line.",
        "When zoomed out, voltage, temperature, and current traces are smoothed for readability without changing the stored data.",
    ])

    doc.add_heading("6. Zooming and Moving the Graph", level=1)
    doc.add_paragraph(
        "The popup itself and the graph timeline use different gestures so they do not interfere with each other."
    )
    add_key_value_table(doc, [
        ("One-finger drag", "Moves the popup window."),
        ("Two-finger pinch", "Zooms the graph timeline in or out."),
        ("Two-finger drag", "Pans the visible graph timeline."),
        ("Reset Zoom", "Appears inside the graph after zooming or panning. Tap it to return to the normal auto view."),
    ])

    doc.add_heading("7. Background Operation and Notification", level=1)
    doc.add_paragraph(
        "BatteryCurrent can continue running in the background. In this state, the foreground overlay is hidden, but the notification remains available from the Android pull-down shade."
    )
    add_bullets(doc, [
        "Use Background from the graph popup to hide the overlay while monitoring continues.",
        "Use the notification to bring the foreground display back.",
        "Use Stop from the graph popup when you want to end monitoring completely.",
    ])

    doc.add_heading("8. Battery Capacity Event Logging", level=1)
    doc.add_paragraph(
        "BatteryCurrent records qualifying charge and discharge sessions in the app private folder. The current simplified approach records sessions that span the useful 25% to 75% battery range."
    )
    add_bullets(doc, [
        "A charge event starts near 25% and completes when it reaches 75%.",
        "A discharge event starts near 75% and completes when it reaches 25%.",
        "Plugging or unplugging during a qualifying session cancels that session and waits for the next threshold crossing.",
        "Daily capacity estimates are stored separately and updated only from completed qualifying events.",
        "A small blinking dot before the version number indicates that a qualifying event is in progress.",
    ])

    doc.add_heading("9. Data and Privacy", level=1)
    doc.add_paragraph(
        "BatteryCurrent stores its measurement history and battery capacity event files in the app private data area. "
        "The app is intended to monitor battery behavior locally. It does not need cloud storage for normal operation."
    )
    add_bullets(doc, [
        "Graph history is capped to reduce storage growth.",
        "Capacity event files are stored as CSV files for later inspection.",
        "Deleting app data or uninstalling the app removes the stored history.",
    ])

    doc.add_heading("10. Troubleshooting", level=1)
    add_key_value_table(doc, [
        ("Overlay does not appear", "Check Android overlay permission for BatteryCurrent, then reopen the app."),
        ("Foreground display cannot move", "Open the graph popup and tap Unlock."),
        ("Graph looks crowded", "Use two-finger pinch to zoom in, or switch the right-axis trace to a less noisy parameter."),
        ("No capacity estimate yet", "The app needs completed qualifying charge or discharge sessions before daily estimates are available."),
        ("App was stopped", "Restart BatteryCurrent from the launcher. If the service was fully stopped, it cannot measure the period while it was not running."),
    ])

    doc.add_heading("11. Notes for This Draft", level=1)
    doc.add_paragraph(
        "This manual is preliminary. Before public release, update screenshots, confirm terminology, add Play Store installation notes, and verify any privacy wording against the final app behavior."
    )

    add_footer(doc)
    doc.save(OUTPUT_PATH)


if __name__ == "__main__":
    build_manual()
