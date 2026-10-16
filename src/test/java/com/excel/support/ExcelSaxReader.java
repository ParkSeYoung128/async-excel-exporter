package com.excel.support;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * 테스트용 엑셀 검증 도구. 10만 행 파일을 DOM(XSSFWorkbook)으로 열면 테스트 JVM 메모리가 부족하므로
 * SAX 로 한 행씩 읽으며 시트별 행 수와 헤더·첫 행·마지막 행만 모은다.
 * 값은 셀 서식이 적용된 문자열이라, 금액 "33,700" 과 날짜 "2026-04-04 19:55:45" 처럼
 * 숫자·날짜 셀로 저장되었는지까지 함께 검증할 수 있다.
 */
public final class ExcelSaxReader {

    public record SheetSummary(String name, int dataRows, List<String> header,
                               List<String> firstDataRow, List<String> lastDataRow) {
    }

    private ExcelSaxReader() {
    }

    public static List<SheetSummary> read(Path file, int columns) throws Exception {
        List<SheetSummary> sheets = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iterator.hasNext()) {
                try (InputStream in = iterator.next()) {
                    Collector collector = new Collector(columns);
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(
                            styles, null, strings, collector, new DataFormatter(), false));
                    parser.parse(new InputSource(in));
                    sheets.add(new SheetSummary(iterator.getSheetName(), collector.dataRows,
                            collector.header, collector.first, collector.last));
                }
            }
        }
        return sheets;
    }

    private static final class Collector implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final int columns;
        private String[] current;
        private int dataRows;
        private List<String> header = List.of();
        private List<String> first = List.of();
        private List<String> last = List.of();

        private Collector(int columns) {
            this.columns = columns;
        }

        @Override
        public void startRow(int rowNum) {
            current = new String[columns];
        }

        @Override
        public void endRow(int rowNum) {
            List<String> values = Arrays.asList(current.clone());
            if (rowNum == 0) {
                header = values;
                return;
            }
            dataRows++;
            if (dataRows == 1) {
                first = values;
            }
            last = values;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = new CellReference(cellReference).getCol();
            if (column < columns) {
                current[column] = formattedValue;
            }
        }
    }
}
