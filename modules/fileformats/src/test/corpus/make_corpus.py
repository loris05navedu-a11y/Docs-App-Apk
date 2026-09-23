"""Fabrique des fichiers « venus d'ailleurs » pour les tests d'import.

Les fichiers sont produits par de vrais logiciels — python-docx, openpyxl,
python-pptx et LibreOffice — plutôt qu'écrits par l'app elle-même : ce sont
leurs particularités (styles hérités, cellules fusionnées, formules
partagées, espaces de noms) qui cassent un lecteur, pas les nôtres.

    python3 make_corpus.py        # écrit dans ../resources/corpus/

LibreOffice (`soffice`) sert à dériver .odt, .doc, .rtf, .html, .ods et .odp.
"""

import os
import shutil
import subprocess
import tempfile
import zipfile

import docx
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor
import openpyxl
from openpyxl.styles import Font, PatternFill
from pptx import Presentation
from pptx.util import Inches

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "resources", "corpus")


def shade(cell, hex_fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_fill)
    tc_pr.append(shd)


def word_report(path):
    """Un rapport comme on en reçoit : titres, listes, tableau fusionné."""
    d = docx.Document()
    d.add_heading("Rapport trimestriel", level=1)
    p = d.add_paragraph("Ce document contient un ")
    p.add_run("tableau").bold = True
    p.add_run(" et des ")
    p.add_run("cellules fusionnées").italic = True
    p.add_run(".")
    d.add_heading("Chiffres", level=2)

    t = d.add_table(rows=4, cols=3)
    t.style = "Table Grid"
    header = t.rows[0].cells
    for cell, text in zip(header, ["Région", "T1", "T2"]):
        cell.text = text
        cell.paragraphs[0].runs[0].bold = True
        shade(cell, "D9E2F3")
    rows = [["Nord", "120", "135"], ["Sud", "98", "101"]]
    for r, values in enumerate(rows, start=1):
        for c, value in enumerate(values):
            t.cell(r, c).text = value
    # Dernière ligne : « Total » sur deux colonnes, fusion horizontale.
    merged = t.cell(3, 0).merge(t.cell(3, 1))
    merged.text = "Total"
    t.cell(3, 2).text = "454"

    d.add_paragraph("Premier point", style="List Bullet")
    d.add_paragraph("Second point", style="List Bullet")
    d.add_paragraph("Étape un", style="List Number")
    d.add_paragraph("Étape deux", style="List Number")

    centred = d.add_paragraph("Fin du rapport")
    centred.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = centred.runs[0]
    run.font.color.rgb = RGBColor(0xC0, 0x00, 0x00)
    run.font.size = Pt(14)

    # Fusion verticale : « Équipe » occupe deux lignes.
    t2 = d.add_table(rows=3, cols=2)
    t2.style = "Table Grid"
    t2.cell(0, 0).text = "Équipe"
    t2.cell(0, 1).text = "Alice"
    t2.cell(1, 1).text = "Bruno"
    t2.cell(0, 0).merge(t2.cell(1, 0))
    t2.cell(2, 0).text = "Seul"
    t2.cell(2, 1).text = "Chloé"
    d.save(path)


WORD_LIKE_BODY = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
 xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
 xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
 xmlns:v="urn:schemas-microsoft-com:vml"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
 mc:Ignorable="wps">
<w:body>
<w:p><w:pPr><w:rPr><w:b/></w:rPr></w:pPr><w:r><w:t>Paragraphe normal</w:t></w:r></w:p>
<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Gras</w:t></w:r><w:r><w:t xml:space="preserve"> puis normal</w:t></w:r></w:p>
<w:p><w:r><w:t>Avant la zone</w:t></w:r><w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wps:txbx><w:txbxContent><w:p><w:r><w:t>Texte de la zone</w:t></w:r></w:p></w:txbxContent></wps:txbx></w:drawing></mc:Choice><mc:Fallback><w:pict><v:textbox><w:txbxContent><w:p><w:r><w:t>Texte de la zone</w:t></w:r></w:p></w:txbxContent></v:textbox></w:pict></mc:Fallback></mc:AlternateContent></w:r><w:r><w:t xml:space="preserve"> après la zone</w:t></w:r></w:p>
<w:sdt><w:sdtPr/><w:sdtContent><w:p><w:r><w:t>Dans un contrôle de contenu</w:t></w:r></w:p></w:sdtContent></w:sdt>
<w:p><w:hyperlink r:id="rId9"><w:r><w:t>un lien</w:t></w:r></w:hyperlink><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>3</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>
<w:tbl><w:tblGrid><w:gridCol/><w:gridCol/><w:gridCol/></w:tblGrid>
<w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>B et C</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p><w:p><w:r><w:t>deuxième ligne</w:t></w:r></w:p></w:tc><w:tc><w:tbl><w:tr><w:tc><w:p><w:r><w:t>imbriqué</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>x</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p/></w:tc><w:tc><w:p><w:r><w:t>3</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>court</w:t></w:r></w:p></w:tc></w:tr>
</w:tbl>
<w:p><w:r><w:t>X</w:t></w:r><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>2</w:t></w:r><w:r><w:t xml:space="preserve"> et H</w:t></w:r><w:r><w:rPr><w:vertAlign w:val="subscript"/></w:rPr><w:t>2</w:t></w:r><w:r><w:t>O</w:t></w:r></w:p>
<w:p><w:ins><w:r><w:t>inséré</w:t></w:r></w:ins><w:del><w:r><w:delText>supprimé</w:delText></w:r></w:del></w:p>
<w:sectPr/>
</w:body></w:document>
"""


def word_like_quirks(path):
    """Les tournures de Word que python-docx n'écrit pas lui-même."""
    base = os.path.join(tempfile.mkdtemp(), "base.docx")
    docx.Document().save(base)
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "word/document.xml":
                data = ("﻿" + WORD_LIKE_BODY).encode("utf-8")
            dst.writestr(item, data)
        # Une image lourde : un lecteur ne doit pas avoir à la décompresser.
        dst.writestr("word/media/image1.png", os.urandom(256 * 1024))


def workbook(path):
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Ventes"
    ws.append(["Produit", "Prix", "Quantité", "Total"])
    ws["A1"].font = Font(bold=True)
    ws["A1"].fill = PatternFill("solid", fgColor="FFFF00")
    for i, (name, price, qty) in enumerate([("Pomme", 1.5, 10), ("Poire", 2, 4), ("Kiwi", 0.5, 30)], start=2):
        ws.append([name, price, qty, f"=B{i}*C{i}"])
    ws["D5"] = "=SUM(D2:D4)"
    ws["A7"] = "Date"
    import datetime
    ws["B7"] = datetime.date(2024, 3, 15)
    ws["B7"].number_format = "DD/MM/YYYY"
    ws["F40"] = "loin"
    s2 = wb.create_sheet("Synthèse 2024")
    s2["A1"] = "Total ventes"
    s2["B1"] = "=Ventes!D5"
    s2["A2"] = "Double"
    s2["B2"] = "=B1*2"
    wb.save(path)


SHARED_SHEET = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<dimension ref="A1:C4"/>
<sheetData>
<row r="1"><c r="A1"><v>1</v></c><c r="B1"><v>10</v></c><c r="C1"><f t="shared" ref="C1:C4" si="0">A1+B1</f><v>11</v></c></row>
<row r="2"><c r="A2"><v>2</v></c><c r="B2"><v>20</v></c><c r="C2"><f t="shared" si="0"/><v>22</v></c></row>
<row r="3"><c><v>3</v></c><c><v>30</v></c><c><f t="shared" si="0"/><v>33</v></c></row>
<row r="4"><c r="A4" t="inlineStr"><is><r><t>Riche </t></r><r><rPr><b/></rPr><t>texte</t></r></is></c><c r="B4"><v>0.30000000000000004</v></c><c r="C4"><f>_xlfn.STDEV.S(A1:A3)</f><v>1</v></c></row>
<row r="1048576"><c r="XFD1048576" s="0"/></row>
</sheetData>
</worksheet>
"""


def excel_shared_formulas(path):
    base = os.path.join(tempfile.mkdtemp(), "base.xlsx")
    openpyxl.Workbook().save(base)
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "xl/worksheets/sheet1.xml":
                data = SHARED_SHEET.encode("utf-8")
            dst.writestr(item, data)


def deck(path):
    prs = Presentation()
    s = prs.slides.add_slide(prs.slide_layouts[0])
    s.shapes.title.text = "Présentation annuelle"
    s.placeholders[1].text = "Sous-titre"
    s = prs.slides.add_slide(prs.slide_layouts[1])
    s.shapes.title.text = "Ordre du jour"
    body = s.placeholders[1].text_frame
    body.text = "Bilan"
    body.add_paragraph().text = "Perspectives"
    s.notes_slide.notes_text_frame.text = "Parler lentement"
    s = prs.slides.add_slide(prs.slide_layouts[5])
    s.shapes.title.text = "Tableau"
    rows, cols = 3, 2
    shape = s.shapes.add_table(rows, cols, Inches(1), Inches(2), Inches(6), Inches(2))
    for r in range(rows):
        for c in range(cols):
            shape.table.cell(r, c).text = f"L{r + 1}C{c + 1}"
    prs.slides.add_slide(prs.slide_layouts[6])  # diapositive vide, sans texte
    prs.save(path)


def convert(source, target_ext, filter_name=None):
    outdir = tempfile.mkdtemp()
    target = target_ext if filter_name is None else f"{target_ext}:{filter_name}"
    subprocess.run(
        ["soffice", "--headless", "--convert-to", target, "--outdir", outdir, source],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    produced = [f for f in os.listdir(outdir)]
    return os.path.join(outdir, produced[0])


def main():
    os.makedirs(OUT, exist_ok=True)
    report = os.path.join(OUT, "rapport-word.docx")
    word_report(report)
    word_like_quirks(os.path.join(OUT, "word-particularites.docx"))

    for ext, name, flt in [
        ("odt", "rapport-libreoffice.odt", None),
        ("doc", "rapport-word97.doc", "MS Word 97"),
        ("rtf", "rapport.rtf", None),
        ("html", "rapport.html", None),
    ]:
        shutil.copy(convert(report, ext, flt), os.path.join(OUT, name))
    # Le même rapport repassé par LibreOffice en .docx : autre producteur, autre XML.
    shutil.copy(convert(os.path.join(OUT, "rapport-libreoffice.odt"), "docx"),
                os.path.join(OUT, "rapport-libreoffice.docx"))

    book = os.path.join(OUT, "classeur-excel.xlsx")
    workbook(book)
    excel_shared_formulas(os.path.join(OUT, "formules-partagees.xlsx"))
    shutil.copy(convert(book, "ods"), os.path.join(OUT, "classeur-libreoffice.ods"))

    slides = os.path.join(OUT, "presentation-powerpoint.pptx")
    deck(slides)
    shutil.copy(convert(slides, "odp"), os.path.join(OUT, "presentation-libreoffice.odp"))

    # Textes d'origine Windows : un ANSI (cp1252) et un UTF-16 avec BOM.
    sample = "Élève, café, où, « guillemets » — 25 €\n"
    with open(os.path.join(OUT, "windows-ansi.txt"), "wb") as f:
        f.write(sample.encode("cp1252"))
    with open(os.path.join(OUT, "windows-utf16.txt"), "wb") as f:
        f.write(sample.encode("utf-16"))
    with open(os.path.join(OUT, "excel-francais.csv"), "wb") as f:
        f.write("Nom;Montant\nCafé;3,5\nThé;2\n".encode("cp1252"))


if __name__ == "__main__":
    main()
