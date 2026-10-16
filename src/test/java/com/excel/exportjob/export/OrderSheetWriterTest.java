package com.excel.exportjob.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrderSheetWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("금액은 숫자(#,##0), 주문일시는 날짜 셀로 쓰고 헤더 고정·자동 필터를 건다")
    void writesTypedCellsWithHeaderFreezeAndFilter() throws Exception {
        Path file = writeRows(10, 2);

        try (XSSFWorkbook workbook = new XSSFWorkbook(file.toFile())) {
            XSSFSheet sheet = workbook.getSheet("orders");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("주문ID");
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFont().getBold()).isTrue();

            Cell amount = sheet.getRow(1).getCell(4);
            assertThat(amount.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(amount.getNumericCellValue()).isEqualTo(10_000);
            assertThat(amount.getCellStyle().getDataFormatString()).isEqualTo("#,##0");

            Cell orderDate = sheet.getRow(1).getCell(6);
            assertThat(orderDate.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(orderDate)).isTrue();
            assertThat(orderDate.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 30, 15));

            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
        }
    }

    @Test
    @DisplayName("시트 행 한도를 넘으면 다음 시트에 헤더와 함께 이어 쓴다")
    void rollsOverToNextSheet() throws Exception {
        Path file = writeRows(3, 5);

        try (XSSFWorkbook workbook = new XSSFWorkbook(file.toFile())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("orders");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("orders_2");
            assertThat(workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue()).isEqualTo("주문ID");
            assertThat(workbook.getSheetAt(1).getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheetAt(1).getRow(2).getCell(0).getNumericCellValue()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("데이터가 없어도 헤더만 있는 시트를 남긴다")
    void emptyData_keepsHeaderOnlySheet() throws Exception {
        Path file = writeRows(10, 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(file.toFile())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @SuppressWarnings("deprecation")
    private Path writeRows(int maxRowsPerSheet, int rows) throws Exception {
        Path file = tempDir.resolve("orders.xlsx");
        SXSSFWorkbook workbook = new SXSSFWorkbook(null, 100, false, false);
        try {
            OrderSheetWriter writer = new OrderSheetWriter(workbook, maxRowsPerSheet);
            for (int i = 1; i <= rows; i++) {
                writer.write(new OrderRow(i, "김민준", "리프트 1일권", "스키", 10_000 * i, "confirmed",
                        LocalDateTime.of(2026, 1, 1, 9, 30, 15)));
            }
            writer.finish();
            assertThat(writer.totalRows()).isEqualTo(rows);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        } finally {
            workbook.dispose();
            workbook.close();
        }
        return file;
    }
}
