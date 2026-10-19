package dev.margintrace.margin_attribution_backend.warehouse.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Revenue and cost for one sold product movement. */
@Entity
@IdClass(SalesDetailId.class)
@Table(
        name = "sales_details",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_sales_details_movement_product",
                        columnNames = {"movement_no", "product_no", "date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesDetail {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "movement_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String movementNo;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal productQuantity;

    @Column(name = "total_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal totalCost;

    @Column(name = "total_revenue", nullable = false, precision = 18, scale = 6)
    private BigDecimal totalRevenue;

    /**
     * @param date the sale date
     * @param movementNo the movement number
     * @param productNo the product sold
     * @param productQuantity the quantity sold; must be positive
     * @param totalRevenue the revenue; must not be negative
     * @param totalCost the cost of the goods sold; must not be negative
     * @return a new sales line with trimmed business keys
     * @throws IllegalArgumentException if a value is missing or invalid
     */
    public static SalesDetail of(
            LocalDate date,
            String movementNo,
            String productNo,
            BigDecimal productQuantity,
            BigDecimal totalRevenue,
            BigDecimal totalCost) {
        SalesDetail detail = new SalesDetail();
        detail.date = requireDate(date);
        detail.movementNo = requireBusinessKey(movementNo, "Movement number");
        detail.productNo = requireBusinessKey(productNo, "Product number");

        if (productQuantity == null || productQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Product quantity must be greater than zero");
        }
        if (totalCost == null || totalCost.signum() < 0) {
            throw new IllegalArgumentException("Total cost must not be negative");
        }
        if (totalRevenue == null || totalRevenue.signum() < 0) {
            throw new IllegalArgumentException("Total revenue must not be negative");
        }

        detail.productQuantity = productQuantity;
        detail.totalRevenue = totalRevenue;
        detail.totalCost = totalCost;
        return detail;
    }

    private static LocalDate requireDate(LocalDate value) {
        if (value == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        return value;
    }

    private static String requireBusinessKey(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        String normalized = value.trim();
        if (normalized.length() > BUSINESS_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(fieldName + " must not exceed 100 characters");
        }
        return normalized;
    }
}
