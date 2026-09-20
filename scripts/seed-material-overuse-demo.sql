-- =============================================================================
-- Demo Seed Script: Multi-Stage Production Topology with Material Overrun
-- =============================================================================
--
-- Topology:
--
--   Product-A (Finished Product, PO-DEMO-PROD-A, Output: 100, Date: 2026-01-15)
--     ├── Semi-product-B (Sub-assembly B, PO-DEMO-SEMI-B, Output: 100, Date: 2026-01-10)
--     │     ├── Material-D (Raw Material, OVERRUN: Planned 100, Issued 125, Variance +25)
--     │     └── Material-E (Raw Material, Normal: Planned 200, Issued 200)
--     └── Semi-product-C (Sub-assembly C, PO-DEMO-SEMI-C, Output: 100, Date: 2026-01-10)
--           ├── Material-F (Raw Material, Normal: Planned 150, Issued 150)
--           └── Material-G (Raw Material, Normal: Planned 100, Issued 100)
--
-- Frontend Query Parameters:
--   - Target ID: Product-A
--   - Start Date: 2026-01-10
--   - End Date: 2026-01-15
-- =============================================================================

BEGIN;

-- 1. Clean up previously seeded demo records for idempotency
DELETE FROM inventory_usage
WHERE order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C');

DELETE FROM bill_of_material
WHERE product_no IN ('Product-A', 'Semi-product-B', 'Semi-product-C');

DELETE FROM production_order
WHERE production_order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C');


-- 2. Insert Production Orders (Multi-stage routing: sub-assemblies followed by final assembly)
-- Stage 1: Produce sub-assemblies Semi-product-B and Semi-product-C on 2026-01-10
INSERT INTO production_order (
    production_order_no,
    date,
    product_no,
    product_num,
    product_department,
    bom_no
) VALUES 
('PO-DEMO-SEMI-B', '2026-01-10', 'Semi-product-B', 100.000000, 'Machining Dept',  'BOM-SEMI-B'),
('PO-DEMO-SEMI-C', '2026-01-10', 'Semi-product-C', 100.000000, 'Processing Dept', 'BOM-SEMI-C');

-- Stage 2: Assemble final finished product Product-A on 2026-01-15
INSERT INTO production_order (
    production_order_no,
    date,
    product_no,
    product_num,
    product_department,
    bom_no
) VALUES 
('PO-DEMO-PROD-A', '2026-01-15', 'Product-A',      100.000000, 'Assembly Dept',   'BOM-PROD-A');


-- 3. Insert Bill of Materials (Standard planned unit usage)
INSERT INTO bill_of_material (bom_no, product_no, material_no, material_usage)
VALUES
-- BOM for Semi-product-B (Unit usage: Material-D = 1.0, Material-E = 2.0)
('BOM-SEMI-B', 'Semi-product-B', 'Material-D', 1.000000),
('BOM-SEMI-B', 'Semi-product-B', 'Material-E', 2.000000),

-- BOM for Semi-product-C (Unit usage: Material-F = 1.5, Material-G = 1.0)
('BOM-SEMI-C', 'Semi-product-C', 'Material-F', 1.500000),
('BOM-SEMI-C', 'Semi-product-C', 'Material-G', 1.000000),

-- BOM for Product-A (Unit usage: 1.0 each of Semi-product-B and Semi-product-C)
('BOM-PROD-A', 'Product-A',      'Semi-product-B', 1.000000),
('BOM-PROD-A', 'Product-A',      'Semi-product-C', 1.000000);


-- 4. Insert Material Consumption (Actual issued material lines)
INSERT INTO inventory_usage (
    date,
    movement_no,
    order_no,
    product_no,
    material_no,
    material_num,
    material_total_cost
) VALUES
-- Issue lines for PO-DEMO-SEMI-B:
-- Material-D planned total = 100 * 1.0 = 100, actual issued = 125 (Overrun by +25)
('2026-01-10', 'MC-DEMO-B-01', 'PO-DEMO-SEMI-B', 'Semi-product-B', 'Material-D', 125.000000, 1250.000000),
-- Material-E planned total = 100 * 2.0 = 200, actual issued = 200 (Normal)
('2026-01-10', 'MC-DEMO-B-02', 'PO-DEMO-SEMI-B', 'Semi-product-B', 'Material-E', 200.000000, 1000.000000),

-- Issue lines for PO-DEMO-SEMI-C:
-- Material-F planned total = 100 * 1.5 = 150, actual issued = 150 (Normal)
('2026-01-10', 'MC-DEMO-C-01', 'PO-DEMO-SEMI-C', 'Semi-product-C', 'Material-F', 150.000000, 1500.000000),
-- Material-G planned total = 100 * 1.0 = 100, actual issued = 100 (Normal)
('2026-01-10', 'MC-DEMO-C-02', 'PO-DEMO-SEMI-C', 'Semi-product-C', 'Material-G', 100.000000,  800.000000),

-- Issue lines for PO-DEMO-PROD-A:
-- Semi-product-B planned total = 100 * 1.0 = 100, actual issued = 100 (Normal)
('2026-01-15', 'MC-DEMO-A-01', 'PO-DEMO-PROD-A', 'Product-A',      'Semi-product-B', 100.000000, 2250.000000),
-- Semi-product-C planned total = 100 * 1.0 = 100, actual issued = 100 (Normal)
('2026-01-15', 'MC-DEMO-A-02', 'PO-DEMO-PROD-A', 'Product-A',      'Semi-product-C', 100.000000, 2300.000000);

COMMIT;


-- =============================================================================
-- 5. Verification Query: Validate multi-stage planned vs. actual material usage
-- =============================================================================
SELECT 
    p.date AS production_date,
    p.production_order_no,
    p.product_no,
    p.product_num AS order_quantity,
    m.material_no,
    (p.product_num * b.material_usage) AS planned_usage,
    m.material_num AS actual_issued,
    (m.material_num - (p.product_num * b.material_usage)) AS variance_quantity,
    CASE 
        WHEN m.material_num > (p.product_num * b.material_usage) THEN 'YES (OVERRUN)'
        ELSE 'NORMAL'
    END AS is_overrun,
    m.material_total_cost AS issued_total_cost
FROM production_order p
JOIN bill_of_material b
  ON b.bom_no = p.bom_no
 AND b.product_no = p.product_no
JOIN inventory_usage m
  ON m.order_no = p.production_order_no
 AND m.product_no = p.product_no
 AND m.material_no = b.material_no
WHERE p.production_order_no IN ('PO-DEMO-PROD-A', 'PO-DEMO-SEMI-B', 'PO-DEMO-SEMI-C')
ORDER BY p.date ASC, p.production_order_no ASC, m.material_no ASC;
