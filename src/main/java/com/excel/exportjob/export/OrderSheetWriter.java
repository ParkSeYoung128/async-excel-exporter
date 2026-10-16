package com.excel.exportjob.export;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 주문 행을 시트에 기록한다.
 * <ul>
 *   <li>셀 타입: 금액은 숫자(#,##0), 주문일시는 날짜 셀. 문자열로 넣으면 엑셀에서 정렬·합계가 안 된다.</li>
 *   <li>스타일은 처음에 한 번만 만들어 재사용한다 (행마다 만들면 통합문서의 스타일 개수 한도에 걸린다).</li>
 *   <li>열 너비는 고정한다. autoSizeColumn 은 모든 셀 너비를 추적해 스트리밍의 이점을 깎는다.</li>
 *   <li>시트당 행 한도(1,048,576 - 헤더 1행)를 넘으면 다음 시트를 만들어 이어 쓴다.</li>
 * </ul>
 */
public class OrderSheetWriter {

    static final int MAX_DATA_ROWS_PER_SHEET = SpreadsheetVersion.EXCEL2007.getMaxRows() - 1;

    private static final String SHEET_NAME = "orders";
    private static final String[] HEADERS = {"주문ID", "주문자", "상품명", "카테고리", "결제금액(원)", "주문상태", "주문일시"};
    private static final int[] COLUMN_WIDTHS = {10, 12, 30, 12, 16, 12, 22};

    private final SXSSFWorkbook workbook;
    private final int maxDataRowsPerSheet;
    private final CellStyle headerStyle;
    private final CellStyle amountStyle;
    private final CellStyle dateStyle;

    private SXSSFSheet sheet;
    private int sheetCount;
    private int rowsInSheet;
    private int totalRows;

    public OrderSheetWriter(SXSSFWorkbook workbook) {
        this(workbook, MAX_DATA_ROWS_PER_SHEET);
    }

    /** 시트 분할을 작은 값으로 검증하기 위한 생성자 */
    OrderSheetWriter(SXSSFWorkbook workbook, int maxDataRowsPerSheet) {
        this.workbook = workbook;
        this.maxDataRowsPerSheet = maxDataRowsPerSheet;

        DataFormat format = workbook.createDataFormat();

        Font bold = workbook.createFont();
        bold.setBold(true);
        this.headerStyle = workbook.createCellStyle();
        headerStyle.setFont(bold);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);

        this.amountStyle = workbook.createCellStyle();
        amountStyle.setDataFormat(format.getFormat("#,##0"));

        this.dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(format.getFormat("yyyy-mm-dd hh:mm:ss"));
    }

    public void write(OrderRow order) {
        if (sheet == null || rowsInSheet >= maxDataRowsPerSheet) {
            startNewSheet();
        }
        Row row = sheet.createRow(++rowsInSheet);
        row.createCell(0).setCellValue(order.id());
        row.createCell(1).setCellValue(order.userName());
        row.createCell(2).setCellValue(order.productName());
        row.createCell(3).setCellValue(order.category());

        Cell amount = row.createCell(4);
        amount.setCellValue(order.amount());
        amount.setCellStyle(amountStyle);

        row.createCell(5).setCellValue(order.status());

        Cell orderDate = row.createCell(6);
        orderDate.setCellValue(order.orderDate());
        orderDate.setCellStyle(dateStyle);

        totalRows++;
    }

    /** 마지막 시트의 자동 필터를 마무리한다. 데이터가 없어도 헤더만 있는 시트를 남긴다. */
    public void finish() {
        if (sheet == null) {
            startNewSheet();
        }
        applyAutoFilter();
    }

    public int totalRows() {
        return totalRows;
    }

    private void startNewSheet() {
        applyAutoFilter();
        sheetCount++;
        sheet = workbook.createSheet(sheetCount == 1 ? SHEET_NAME : SHEET_NAME + "_" + sheetCount);
        for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
            sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
        }
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }
        sheet.createFreezePane(0, 1);
        rowsInSheet = 0;
    }

    private void applyAutoFilter() {
        if (sheet != null) {
            sheet.setAutoFilter(new CellRangeAddress(0, rowsInSheet, 0, HEADERS.length - 1));
        }
    }
}
