-- Demo: Material-D exceeds the BOM plan for Semi-product-B.
BEGIN;

DELETE FROM inventory_usage
WHERE order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C');
DELETE FROM sales_details
WHERE movement_no = 'COGS-DEMO-PROD-A';
DELETE FROM bill_of_material
WHERE product_no IN ('Product-A', 'Semi-product-B', 'Semi-product-C');

INSERT INTO bill_of_material (bom_no, product_no, material_no, material_usage)
VALUES
    ('BOM-SEMI-B', 'Semi-product-B', 'Material-D', 1.000000),
    ('BOM-SEMI-B', 'Semi-product-B', 'Material-E', 2.000000),
    ('BOM-SEMI-C', 'Semi-product-C', 'Material-F', 1.500000),
    ('BOM-SEMI-C', 'Semi-product-C', 'Material-G', 1.000000),
    ('BOM-PROD-A', 'Product-A', 'Semi-product-B', 1.000000),
    ('BOM-PROD-A', 'Product-A', 'Semi-product-C', 1.000000);

INSERT INTO inventory_usage (
    date, movement_no, order_no, product_no, product_num,
    material_no, material_num, material_total_cost
) VALUES
    ('2026-01-10', 'MC-DEMO-B-01', 'PO-DEMO-SEMI-B', 'Semi-product-B', 100.000000, 'Material-D', 125.000000, 1250.000000),
    ('2026-01-10', 'MC-DEMO-B-02', 'PO-DEMO-SEMI-B', 'Semi-product-B', 100.000000, 'Material-E', 200.000000, 1000.000000),
    ('2026-01-10', 'MC-DEMO-C-01', 'PO-DEMO-SEMI-C', 'Semi-product-C', 100.000000, 'Material-F', 150.000000, 1500.000000),
    ('2026-01-10', 'MC-DEMO-C-02', 'PO-DEMO-SEMI-C', 'Semi-product-C', 100.000000, 'Material-G', 100.000000, 800.000000),
    ('2026-01-15', 'MC-DEMO-A-01', 'PO-DEMO-PROD-A', 'Product-A', 100.000000, 'Semi-product-B', 100.000000, 2250.000000),
    ('2026-01-15', 'MC-DEMO-A-02', 'PO-DEMO-PROD-A', 'Product-A', 100.000000, 'Semi-product-C', 100.000000, 2300.000000);

INSERT INTO sales_details (
    date, movement_no, product_no, product_num, total_revenue, total_cost
) VALUES ('2026-01-15', 'COGS-DEMO-PROD-A', 'Product-A', 100.000000, 12000.000000, 4550.000000);

COMMIT;

SELECT
    m.date,
    m.order_no,
    m.product_no,
    m.product_num,
    m.material_no,
    (m.product_num * b.material_usage) AS planned_usage,
    m.material_num AS actual_issued,
    (m.material_num - m.product_num * b.material_usage) AS variance_quantity
FROM inventory_usage m
JOIN bill_of_material b
  ON b.product_no = m.product_no AND b.material_no = m.material_no
WHERE m.order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C')
ORDER BY m.date, m.order_no, m.material_no;
