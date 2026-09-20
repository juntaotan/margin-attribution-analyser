-- =========================================================
-- Sample data for margin topology visualization
-- =========================================================

-- 1. Intermediate Component: COMP-01
INSERT INTO production_order (
    production_order_no,
    date,
    product_no,
    product_num,
    product_department,
    bom_no
) VALUES (
    'PO-2026-002',
    '2026-01-10',
    'COMP-01',
    50.000000,
    'Machining Dept',
    'BOM-COMP-01'
) ON CONFLICT DO NOTHING;

-- 2. Finished Product: PROD-A
INSERT INTO production_order (
    production_order_no,
    date,
    product_no,
    product_num,
    product_department,
    bom_no
) VALUES (
    'PO-2026-001',
    '2026-01-15',
    'PROD-A',
    100.000000,
    'Assembly Dept',
    'BOM-PROD-A'
) ON CONFLICT DO NOTHING;

-- 3. Material consumption for COMP-01 (Raw material MAT-STEEL -> COMP-01)
INSERT INTO inventory_usage (
    date,
    movement_no,
    order_no,
    product_no,
    material_no,
    material_num,
    material_total_cost
) VALUES (
    '2026-01-10',
    'MC-2026-001',
    'PO-2026-002',
    'COMP-01',
    'MAT-STEEL',
    150.000000,
    3000.000000
) ON CONFLICT DO NOTHING;

-- 4. Material consumption for PROD-A (Component COMP-01 and Raw material MAT-PAINT -> PROD-A)
INSERT INTO inventory_usage (
    date,
    movement_no,
    order_no,
    product_no,
    material_no,
    material_num,
    material_total_cost
) VALUES 
('2026-01-15', 'MC-2026-002', 'PO-2026-001', 'PROD-A', 'COMP-01', 50.000000, 3500.000000),
('2026-01-15', 'MC-2026-003', 'PO-2026-001', 'PROD-A', 'MAT-PAINT', 20.000000, 800.000000)
ON CONFLICT DO NOTHING;

-- =========================================================
-- Two-period reconciliation demo
-- Actual: January 2026. Comparable: January 2025.
-- MAT-STEEL differs by 200; consumed COMP-01 differs by 400.
-- With leafThreshold=100 and stopThreshold=250, the significant path is
-- MAT-STEEL -> COMP-01. MAT-PAINT is unchanged.
-- =========================================================

-- The trace starts from products sold in each period. Both periods must
-- therefore contain a sale of the finished product.
INSERT INTO sales_order (
    sale_order_no, date, movement_no, product_no, product_num, product_total_price
) VALUES
    ('SO-DEMO-2025', '2025-01-15', 'SALE-DEMO-2025', 'PROD-A', 100.000000, 12000.000000),
    ('SO-DEMO-2026', '2026-01-15', 'SALE-DEMO-2026', 'PROD-A', 100.000000, 12000.000000)
ON CONFLICT DO NOTHING;

-- Comparable period: the same topology and quantities as January 2026.
INSERT INTO production_order (
    production_order_no, date, product_no, product_num, product_department, bom_no
) VALUES
    ('PO-2025-002', '2025-01-10', 'COMP-01', 50.000000, 'Machining Dept', 'BOM-COMP-01'),
    ('PO-2025-001', '2025-01-15', 'PROD-A', 100.000000, 'Assembly Dept', 'BOM-PROD-A')
ON CONFLICT DO NOTHING;

INSERT INTO inventory_usage (
    date, movement_no, order_no, product_no, material_no, material_num, material_total_cost
) VALUES
    ('2025-01-10', 'MC-2025-001', 'PO-2025-002', 'COMP-01', 'MAT-STEEL', 150.000000, 2800.000000),
    ('2025-01-15', 'MC-2025-002', 'PO-2025-001', 'PROD-A', 'COMP-01', 50.000000, 3100.000000),
    ('2025-01-15', 'MC-2025-003', 'PO-2025-001', 'PROD-A', 'MAT-PAINT', 20.000000, 800.000000)
ON CONFLICT DO NOTHING;
