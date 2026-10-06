-- =========================================================
-- Sample data for margin topology visualization
-- =========================================================

-- 1. Material consumption for COMP-01 (Raw material MAT-STEEL -> COMP-01)
INSERT INTO inventory_usage (
    date,
    movement_no,
    order_no,
    product_no,
    product_num,
    material_no,
    material_num,
    material_total_cost
) VALUES (
    '2026-01-10',
    'MC-2026-001',
    'PO-2026-002',
    'COMP-01',
    50.000000,
    'MAT-STEEL',
    150.000000,
    3000.000000
) ON CONFLICT DO NOTHING;

-- 2. Material consumption for PROD-A (Component COMP-01 and Raw material MAT-PAINT -> PROD-A)
INSERT INTO inventory_usage (
    date,
    movement_no,
    order_no,
    product_no,
    product_num,
    material_no,
    material_num,
    material_total_cost
) VALUES 
('2026-01-15', 'MC-2026-002', 'PO-2026-001', 'PROD-A', 100.000000, 'COMP-01', 50.000000, 3500.000000),
('2026-01-15', 'MC-2026-003', 'PO-2026-001', 'PROD-A', 100.000000, 'MAT-PAINT', 20.000000, 800.000000)
ON CONFLICT DO NOTHING;

-- =========================================================
-- Two-period reconciliation demo
-- Actual: January 2026. Comparable: January 2025.
-- MAT-STEEL differs by 200; consumed COMP-01 differs by 400.
-- With leafThreshold=100 and stopThreshold=250, the significant path is
-- MAT-STEEL -> COMP-01. MAT-PAINT is unchanged.
-- =========================================================

-- The trace starts from costed products in each period.

INSERT INTO sales_details (
    date, movement_no, product_no, product_num, total_revenue, total_cost
) VALUES
    ('2025-01-15', 'COGS-DEMO-2025', 'PROD-A', 100.000000, 12000.000000, 4500.000000),
    ('2026-01-15', 'COGS-DEMO-2026', 'PROD-A', 100.000000, 12000.000000, 4900.000000)
ON CONFLICT DO NOTHING;

INSERT INTO inventory_usage (
    date, movement_no, order_no, product_no, product_num, material_no, material_num, material_total_cost
) VALUES
    ('2025-01-10', 'MC-2025-001', 'PO-2025-002', 'COMP-01', 50.000000, 'MAT-STEEL', 150.000000, 2800.000000),
    ('2025-01-15', 'MC-2025-002', 'PO-2025-001', 'PROD-A', 100.000000, 'COMP-01', 50.000000, 3100.000000),
    ('2025-01-15', 'MC-2025-003', 'PO-2025-001', 'PROD-A', 100.000000, 'MAT-PAINT', 20.000000, 800.000000)
ON CONFLICT DO NOTHING;
