-- =============================================================================
-- Demo Cleanup Script: Remove Multi-Stage Material Overrun Demo Data
-- =============================================================================
--
-- This script cleans up all demo fixtures inserted by seed-material-overuse-demo.sql:
--   - Material consumption lines for PO-DEMO-PROD-A, PO-DEMO-SEMI-B, PO-DEMO-SEMI-C
--   - Production orders for PO-DEMO-PROD-A, PO-DEMO-SEMI-B, PO-DEMO-SEMI-C
--   - Bill of materials (BOM) for Product-A, Semi-product-B, Semi-product-C
--
-- Deletion order respects database foreign key constraints:
--   1. material_consumption (child table referencing production_order)
--   2. production_order (parent table)
--   3. bill_of_material (independent reference table)
-- =============================================================================

BEGIN;

-- 1. Remove material consumption records (child records with FK to production_order)
DELETE FROM material_consumption
WHERE production_order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C');

-- 2. Remove production order records
DELETE FROM production_order
WHERE production_order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C');

-- 3. Remove bill of material records
DELETE FROM bill_of_material
WHERE product_no IN ('Product-A', 'Semi-product-B', 'Semi-product-C')
   OR bom_no IN ('BOM-PROD-A', 'BOM-SEMI-B', 'BOM-SEMI-C');

COMMIT;


-- =============================================================================
-- Verification: Confirm that all demo fixtures have been removed
-- =============================================================================
SELECT 
    (SELECT COUNT(*) FROM production_order 
     WHERE production_order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C')) AS remaining_orders,
    (SELECT COUNT(*) FROM bill_of_material 
     WHERE product_no IN ('Product-A', 'Semi-product-B', 'Semi-product-C')) AS remaining_bom_lines,
    (SELECT COUNT(*) FROM material_consumption 
     WHERE production_order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C')) AS remaining_consumptions;

