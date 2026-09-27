CREATE OR REPLACE VIEW order_margin_summary AS
SELECT
    s.id,
    s.sale_order_no,
    s.date,
    s.movement_no,
    s.product_no,
    s.product_num,
    s.product_total_price AS revenue,
    COALESCE(c.total_cost, 0) AS total_cost,
    (s.product_total_price - COALESCE(c.total_cost, 0)) AS gross_margin,
    CASE
        WHEN s.product_total_price > 0
        THEN ROUND(((s.product_total_price - COALESCE(c.total_cost, 0)) / s.product_total_price * 100), 2)
        ELSE 0
    END AS gross_margin_percent
FROM sales_order s
LEFT JOIN cost_details c ON s.sale_order_no = c.sale_order_no;

-- Align historical placeholder seeds to sample data period (January 2026)
UPDATE report_placeholder_config
SET duration = '2026-01-01 to 2026-01-31',
    prompt = REPLACE(prompt, '2026-08-01 to 2026-08-31', '2026-01-01 to 2026-01-31'),
    updated_at = CURRENT_TIMESTAMP
WHERE duration = '2026-08-01 to 2026-08-31';
