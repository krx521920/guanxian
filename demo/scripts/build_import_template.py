#!/usr/bin/env python3
"""生成「管线智联」会员调查模板 xlsx（无需 openpyxl，纯标准库构造最小合法 XLSX）。"""
import zipfile, os, sys

OUT = os.path.join(os.path.dirname(__file__), '..', 'data', 'member-import-template.xlsx')

headers = [
    '企业名称*', '统一社会信用代码', '企业类别', '联系地址', '联系人', '联系电话',
    '企业简介', '技术业务角色', '适用场景（逗号分隔）', '主要产品/服务（逗号分隔）', '合作需求（逗号分隔）',
]

def col(n):
    s = ''
    while n:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s

cols = [col(i + 1) for i in range(len(headers))]
cells = []
for c, h in zip(cols, headers):
    cells.append(f'<c r="{c}1" t="inlineStr"><is><t>{h}</t></is></c>')

rows = [
    ('示例：回龙观管网服务有限公司', '91110114MA01DEMO01X', '工程服务商', '北京市昌平区回龙观街道龙域北街8号', '张工', '13800138000',
     '提供供热管网巡检与抢修服务。', '供热管网运维商', '热力,供水', '管网巡检服务,应急抢修', '寻找智能井盖传感器供应商'),
]
body = ''
for ri, r in enumerate(rows, start=2):
    body += '<row r="%d">' % ri
    for ci, v in enumerate(r):
        body += f'<c r="{col(ci + 1)}{ri}" t="inlineStr"><is><t>{v}</t></is></c>'
    body += '</row>'

sheet = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
         '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
         f'<sheetData><row r="1">{chr(10).join(cells)}</row>{body}</sheetData></worksheet>')
wb = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
      '<sheets><sheet name="会员调查表" sheetId="1" r:id="rId1"/></sheets></workbook>')
rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
        '</Relationships>')
styles = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>')
ct = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      '<Default Extension="xml" ContentType="application/xml"/>'
      '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
      '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      '</Types>')

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml', ct)
    z.writestr('_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
    z.writestr('xl/workbook.xml', wb)
    z.writestr('xl/_rels/workbook.xml.rels', rels)
    z.writestr('xl/worksheets/sheet1.xml', sheet)
    z.writestr('xl/styles.xml', styles)
print('written:', OUT)
