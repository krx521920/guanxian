package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.ApiException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class MemberWorkbookService {
    static final String DATA_SHEET = "会员资料";
    static final int MAX_ROWS = 500;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final List<String> HEADERS = List.of(
            "企业名称*", "统一社会信用代码", "企业分类*", "联系地址", "联系人", "联系电话",
            "企业简介", "核心能力（用；分隔）", "产品与服务（用；分隔）", "合作需求（用；分隔）", "可见范围");

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(10L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(5L * 1024 * 1024);
    }

    byte[] createTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet(DATA_SHEET);
            CellStyle headerStyle = headerStyle(workbook);
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(HEADERS.get(index));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, switch (index) {
                    case 0, 2 -> 24 * 256;
                    case 6, 7, 8, 9 -> 34 * 256;
                    default -> 20 * 256;
                });
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, HEADERS.size() - 1));
            var validationHelper = new XSSFDataValidationHelper(sheet);
            var constraint = validationHelper.createExplicitListConstraint(
                    new String[]{"MEMBERS", "ASSOCIATION", "PARTNERS", "PRIVATE", "PUBLIC"});
            var validation = validationHelper.createValidation(
                    constraint, new CellRangeAddressList(1, MAX_ROWS, 10, 10));
            validation.setShowErrorBox(true);
            validation.setErrorStyle(0);
            validation.createErrorBox("可见范围无效", "请从下拉列表选择可见范围");
            sheet.addValidationData(validation);

            Sheet instructions = workbook.createSheet("填写说明");
            String[] lines = {
                    "1. 企业名称、企业分类为必填项。",
                    "2. 每行仅填写一家企业，最多 500 家。",
                    "3. 核心能力、产品与服务、合作需求使用中文或英文分号分隔。",
                    "4. 可见范围默认 MEMBERS；PRIVATE 仅本企业和协会工作人员可见。",
                    "5. 导入后统一进入“待审核”，不会自动认证。",
                    "6. 请勿修改“会员资料”工作表名称和表头，不允许使用公式单元格。"
            };
            for (int index = 0; index < lines.length; index++) {
                instructions.createRow(index).createCell(0).setCellValue(lines[index]);
            }
            instructions.setColumnWidth(0, 90 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("member survey template could not be generated", exception);
        }
    }

    List<ParsedRow> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw invalidFile("文件为空或超过 5 MiB 限制");
        }
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(bytes));
             XSSFWorkbook workbook = new XSSFWorkbook(pkg)) {
            if (workbook.getNumberOfSheets() > 5) {
                throw invalidFile("工作簿工作表数量超过 5 个限制");
            }
            XSSFSheet sheet = workbook.getSheet(DATA_SHEET);
            if (sheet == null) {
                throw invalidFile("缺少“会员资料”工作表");
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw invalidFile("数据区域超过模板的 500 行限制");
            }
            Map<String, Integer> columns = columns(sheet.getRow(0));
            DataFormatter formatter = new DataFormatter(Locale.CHINA, true);
            List<ParsedRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw invalidFile("有效数据行超过 500 行限制");
                }
                List<String> errors = new ArrayList<>();
                String name = value(row, columns.get("企业名称*"), "企业名称", formatter, errors);
                String creditCode = value(row, columns.get("统一社会信用代码"), "统一社会信用代码", formatter, errors);
                String category = value(row, columns.get("企业分类*"), "企业分类", formatter, errors);
                String address = value(row, columns.get("联系地址"), "联系地址", formatter, errors);
                String contactName = value(row, columns.get("联系人"), "联系人", formatter, errors);
                String contactPhone = value(row, columns.get("联系电话"), "联系电话", formatter, errors);
                String introduction = value(row, columns.get("企业简介"), "企业简介", formatter, errors);
                String capabilities = value(row, columns.get("核心能力（用；分隔）"), "核心能力", formatter, errors);
                String products = value(row, columns.get("产品与服务（用；分隔）"), "产品与服务", formatter, errors);
                String needs = value(row, columns.get("合作需求（用；分隔）"), "合作需求", formatter, errors);
                String visibility = value(row, columns.get("可见范围"), "可见范围", formatter, errors);
                MemberUpsertRequest request = new MemberUpsertRequest(
                        name, nullIfBlank(creditCode), category, nullIfBlank(address), nullIfBlank(contactName),
                        nullIfBlank(contactPhone), nullIfBlank(introduction), split(capabilities), split(products),
                        split(needs), nullIfBlank(visibility), null, null);
                rows.add(new ParsedRow(index + 1, request, errors));
            }
            if (rows.isEmpty()) {
                throw invalidFile("工作表中没有可导入的数据行");
            }
            return rows;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidFile("文件不是有效的 XLSX 调查表");
        }
    }

    private static Map<String, Integer> columns(Row header) {
        if (header == null) {
            throw invalidFile("缺少表头");
        }
        DataFormatter formatter = new DataFormatter(Locale.CHINA, true);
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Cell cell : header) {
            if (cell.getCellType() == CellType.FORMULA) {
                throw invalidFile("表头不允许使用公式");
            }
            String text = formatter.formatCellValue(cell).trim();
            if (!text.isEmpty() && found.putIfAbsent(text, cell.getColumnIndex()) != null) {
                throw invalidFile("表头字段重复：" + text);
            }
        }
        List<String> missing = HEADERS.stream().filter(headerName -> !found.containsKey(headerName)).toList();
        if (!missing.isEmpty()) {
            throw invalidFile("表头缺少字段：" + String.join("、", missing));
        }
        return found;
    }

    private static String value(
            Row row, int index, String label, DataFormatter formatter, List<String> errors) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            errors.add(label + "不允许使用公式");
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static boolean isBlank(Row row, DataFormatter formatter) {
        for (int index = 0; index < HEADERS.size(); index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && !formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("[；;\\r\\n]+")).stream()
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static ApiException invalidFile(String message) {
        return new ApiException("INVALID_MEMBER_IMPORT", message, HttpStatus.BAD_REQUEST);
    }

    record ParsedRow(int rowNumber, MemberUpsertRequest data, List<String> errors) {
    }
}
