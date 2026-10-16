package com.excel.exportjob.export;

import java.time.LocalDateTime;

public record OrderRow(
        int id,
        String userName,
        String productName,
        String category,
        int amount,
        String status,
        LocalDateTime orderDate
) {
}
