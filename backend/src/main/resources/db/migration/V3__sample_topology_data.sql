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
INSERT INTO material_consumption (
    material_consumption_no,
    production_order_no,
    product_no,
    material_no,
    material_num,
    material_total_cost
) VALUES (
    'MC-2026-001',
    'PO-2026-002',
    'COMP-01',
    'MAT-STEEL',
    150.000000,
    3000.000000
) ON CONFLICT DO NOTHING;

-- 4. Material consumption for PROD-A (Component COMP-01 and Raw material MAT-PAINT -> PROD-A)
INSERT INTO material_consumption (
    material_consumption_no,
    production_order_no,
    product_no,
    material_no,
    material_num,
    material_total_cost
) VALUES 
('MC-2026-002', 'PO-2026-001', 'PROD-A', 'COMP-01', 50.000000, 3500.000000),
('MC-2026-003', 'PO-2026-001', 'PROD-A', 'MAT-PAINT', 20.000000, 800.000000)
ON CONFLICT DO NOTHING;
