-- Remove fixtures inserted by seed-material-overuse-demo.sql.
BEGIN;
DELETE FROM inventory_usage
WHERE order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C');
DELETE FROM sales_details
WHERE movement_no = 'COGS-DEMO-PROD-A';
DELETE FROM bill_of_material
WHERE product_no IN ('Product-A', 'Semi-product-B', 'Semi-product-C');
COMMIT;

SELECT
    (SELECT COUNT(*) FROM bill_of_material
     WHERE product_no IN ('Product-A', 'Semi-product-B', 'Semi-product-C')) AS remaining_bom_lines,
    (SELECT COUNT(*) FROM inventory_usage
     WHERE order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C')) AS remaining_consumptions,
    (SELECT COUNT(*) FROM sales_details
     WHERE movement_no = 'COGS-DEMO-PROD-A') AS remaining_cost_lines;
